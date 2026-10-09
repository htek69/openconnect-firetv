# 経路品質の低下による切り替え 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 判定の根拠を「受信速度」から「プローブの応答時間」へ移し、待機中の誤判定を止める。あわせて一周後の行先を「応答時間が最良だった候補」にし、設定可能な間隔での再開を足す。

**Architecture:** 既存の死活プローブを1周期5回の計時付きに拡張し、応答時間の列から「経路が劣化している」を判定する純粋ロジックを作る。受信速度は帯（下限〜上限）の補助条件に降ごす。候補ごとの応答時間はメモリ上の一周の記録に貯め、一周の終わりに最良へ戻す。検知後は既存の `failOver` 経路（Ruling 25 の2段階切替）をそのまま使う。

**Tech Stack:** Kotlin（Android 非依存の純粋ロジック＋既存の `FailoverService`）、kotlinx.serialization（設定の保存）、JUnit4（JVM 単体テスト）、Compose for TV（設定画面）

**Spec:** `docs/superpowers/specs/2026-10-09-path-quality-failover-design.md`
（旧仕様 `docs/superpowers/specs/2026-09-27-throughput-failover-design.md` の §4-2 と §4-5 を置き換える。旧仕様の他の節は生きている）

## Global Constraints

計画1・計画2・計画3 と同じ制約がすべて適用される。本計画で特に効くもの:

- **`FailoverController` と判定ロジックは Android API を参照しない**
- **既存の Java コードは変更しない**
- **依存を追加しない。バージョンを変えない。`material3` は依存に無い**
- **ログを足さない。** `Log.*` を1つも足さない。認証経路の近くには特に
- **認証情報に触れない。** 値は読まない
- **新しい切替経路・新しいシグナリング経路を作らない**
- **保存キー `slow_link_settings_v1` を変えない**（`DECISIONS` 項目7）
- **`demandTxKbps` は削除する**（`DECISIONS` 項目1 は「この軸に解が無い」として引き継ぐ）
- **既定は OFF のまま。** `enabled = false`
- **安全策 S1〜S4 と裁定 16 / 22 / 25 / 30 / 33 / 35a / 35b / 39 / 44 / 48 / 67 / 72 / 74 / 75 / 77 / 78 / 80 / 82 / 83 / 84 / 85 / 86 / 87 / 90 / 92 / 93 / 94 / 95 / R20 / R21 を壊さない**
- **`DECISIONS` 項目14（折り返さない）と項目16（最初の `Connected` だけで猶予を記録）を壊さない**
- **実在のサーバのホスト名・IP アドレスをどのファイルにも書かない**
- **ビルドは Docker のイメージ（ダイジェスト固定）でのみ行う。壊れていたら報告して止まる**

ビルドコマンド（以下 `BUILD` と記す）:

```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$PWD":/app \
  -v "/c/Users/htek6/.gradle-cache":/gradle-home \
  -v "/c/Users/htek6/.android-docker":/opt/android-sdk/.android \
  -e GRADLE_USER_HOME=/gradle-home -w /app \
  mingc/android-build-box@sha256:47a26138302605eb8a37b024e8a263af0812c14800813458bc674df47cc26331 \
  sh gradlew :app:testDebugUnitTest assembleDebug --console=plain
```

---

## File Structure

| ファイル | 責務 | 変更 |
|---|---|---|
| `failover/ProbeOutcome.kt` | プローブ1周期の結果（到達可否と所要時間の列）。純粋 | **新規** |
| `failover/PathQualityDetector.kt` | 応答時間と受信速度から「劣化」を判定する純粋ロジック | **新規** |
| `failover/LapRecord.kt` | 一周のあいだの候補ごとの応答時間の記録と、最良の候補の選び方。純粋 | **新規** |
| `failover/Ports.kt` | `HealthProbe` に計時付きの既定実装を足す | 変更 |
| `failover/FailoverModels.kt` | `SlowLinkSettings` の項目を入れ替える | 変更 |
| `failover/TcpHealthProbe.kt` | 1周期5回の接続と計時 | 変更 |
| `failover/FailoverController.kt` | 判定の供給関数の型、一周後の行先、再開の計時 | 変更 |
| `failover/FailoverService.kt` | プローブ結果の配線、設定の読み直し | 変更 |
| `tv/SettingsScreen.kt` | 項目4つのステッパーと導出文 | 変更 |
| `tv/SettingsText.kt` | 導出文の組み立て（純関数） | **新規** |

**判定ロジックを3ファイルに分けた理由。** 判定（`PathQualityDetector`）・一周の記録（`LapRecord`）・プローブの結果（`ProbeOutcome`）は寿命も変更の理由も違う。1ファイルに混ぜると、一周の規則を変えるたびに判定のテストを読み直すことになる。

**`SlowLinkDetector.kt` は削除せず残す。** 受信速度の窓平均の計算と、裁定R20 の「測れないときは窓を捨てる」は `PathQualityDetector` が内部で使う。性質テスト（`SlowLinkDetectorPropertyTest`）も残す（`DECISIONS` 項目13）。

---

## Task 1: プローブ1周期の結果を表す型

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/ProbeOutcome.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/ProbeOutcomeTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `data class ProbeOutcome(val reachable: Boolean, val rttMs: List<Long>)`、`ProbeOutcome.medianRttMs: Long?`、`ProbeOutcome.spreadMs: Long?`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeOutcomeTest {

    @Test
    fun `サンプルが無ければ中央値もばらつきも null`() {
        val o = ProbeOutcome(reachable = false, rttMs = emptyList())
        assertNull(o.medianRttMs)
        assertNull(o.spreadMs)
    }

    @Test
    fun `奇数個の中央値は真ん中の値`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(30L, 10L, 20L))
        assertEquals(20L, o.medianRttMs)
    }

    @Test
    fun `偶数個の中央値は中央2つの平均（切り捨て）`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(10L, 20L, 30L, 41L))
        // (20 + 30) / 2 = 25
        assertEquals(25L, o.medianRttMs)
    }

    @Test
    fun `ばらつきは最大と最小の差`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(10L, 95L, 20L))
        assertEquals(85L, o.spreadMs)
    }

    @Test
    fun `サンプルが1つならばらつきは0`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(42L))
        assertEquals(42L, o.medianRttMs)
        assertEquals(0L, o.spreadMs)
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`Unresolved reference 'ProbeOutcome'` で失敗する。

- [ ] **Step 3: 実装する**

```kotlin
package net.openconnect_vpn.android.failover

/**
 * プローブ1周期の結果。
 *
 * 仕様書 §2「測り方」: 1周期で複数回接続し、各回の所要時間を記録する。
 * 30秒間隔のプローブでは60秒の窓にサンプルが2個しか入らず、ばらつきを見るには
 * 足りないため、周期内で複数回取る。
 *
 * **[reachable] の意味は従来と同じ**（1回でも成功すれば到達可能）。死活判定の
 * 強さを変えないための約束で、安全策 S2 や裁定33 の期限の前後関係を動かさない。
 *
 * **失敗した接続も所要時間に数える**（呼び出し側がタイムアウト値を入れる）。
 * 間欠的にタイムアウトする経路は実際に劣化しており、その事実を捨てる理由が無い。
 */
data class ProbeOutcome(
    val reachable: Boolean,
    val rttMs: List<Long>,
) {
    /** 所要時間の中央値。サンプルが無ければ null。偶数個なら中央2つの平均（切り捨て）。 */
    val medianRttMs: Long?
        get() {
            if (rttMs.isEmpty()) return null
            val sorted = rttMs.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2
            }
        }

    /** 所要時間の最大と最小の差。サンプルが無ければ null。 */
    val spreadMs: Long?
        get() = if (rttMs.isEmpty()) null else (rttMs.max() - rttMs.min())
}
```

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`ProbeOutcomeTest` の5件が通り、既存の453件も通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/ProbeOutcome.kt app/src/test/java/net/openconnect_vpn/android/failover/ProbeOutcomeTest.kt
git commit -m "feat(failover): プローブ1周期の結果（到達可否と所要時間の列）を表す型"
```

---

## Task 2: 設定の項目を入れ替える

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverModels.kt:36-39`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/SlowLinkSettingsTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `SlowLinkSettings(enabled, slowRxKbps, rxFloorKbps, degradedRttMs, degradedJitterMs, rearmAfterLapMin)`

仕様書 §4-A の表のとおり。**`demandTxKbps` は無い**（もともと `SlowLinkSettings` に無く、`SlowLinkThresholds` 側の定数だった。Task 3 で消える）。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仕様書 §4-A: 保存キー `slow_link_settings_v1` は据え置き（`DECISIONS` 項目7）。
 * **新旧どちらの向きでも落ちないこと**を固定する。
 */
class SlowLinkSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `既定値は仕様書 4-A の表どおり`() {
        val s = SlowLinkSettings()
        assertEquals(false, s.enabled)
        assertEquals(1000, s.slowRxKbps)
        assertEquals(50, s.rxFloorKbps)
        assertEquals(250, s.degradedRttMs)
        assertEquals(150, s.degradedJitterMs)
        assertEquals(0, s.rearmAfterLapMin)
    }

    @Test
    fun `古い版が書いた JSON を読める（新しい項目は既定値になる）`() {
        val old = """{"enabled":true,"slowRxKbps":2000}"""
        val s = json.decodeFromString<SlowLinkSettings>(old)
        assertEquals(true, s.enabled)
        assertEquals(2000, s.slowRxKbps)
        assertEquals(50, s.rxFloorKbps)
        assertEquals(250, s.degradedRttMs)
    }

    @Test
    fun `消えた項目が混じった JSON を読んでも落ちない`() {
        // demandTxKbps は改訂で消えた項目。古い版が書いていても読み飛ばす。
        val old = """{"enabled":true,"slowRxKbps":1000,"demandTxKbps":5}"""
        val s = json.decodeFromString<SlowLinkSettings>(old)
        assertEquals(true, s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }

    @Test
    fun `往復しても一致する`() {
        val s = SlowLinkSettings(
            enabled = true,
            slowRxKbps = 1500,
            rxFloorKbps = 100,
            degradedRttMs = 300,
            degradedJitterMs = 200,
            rearmAfterLapMin = 60,
        )
        assertEquals(s, json.decodeFromString<SlowLinkSettings>(json.encodeToString(s)))
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`No value passed for parameter 'rxFloorKbps'` 等で失敗する。

- [ ] **Step 3: 実装する**

`FailoverModels.kt` の `SlowLinkSettings` を置き換える。

```kotlin
/**
 * 仕様書 §4-A: 経路品質による切替の設定。**4項目すべてを利用者が変更できる。**
 *
 * 既定値は旧仕様 §2-2 の実測から引いたが、**その実測は1台の端末・1つの宛先・
 * 1つの時間帯のものである**（改訂版 §3-2 の限界）。埋め込みで固定しない。
 *
 * **保存キーは `slow_link_settings_v1` のまま据え置く**（`DECISIONS` 項目7）。
 * 新しい項目には既定値があるので、古い版が書いた JSON を読んでも落ちない。
 * 改訂で消えた `demandTxKbps` は `GroupStore` の `ignoreUnknownKeys` が読み飛ばす。
 */
@Serializable
data class SlowLinkSettings(
    val enabled: Boolean = false,
    /** 「遅い」と見なす受信速度の上限（kbps）。旧仕様から変更なし。 */
    val slowRxKbps: Int = 1000,
    /**
     * 「使っている」と見なす受信速度の下限（kbps）。これを下回るあいだは判定しない。
     * **`0` は下限なし。**
     *
     * **待機中の誤判定を防ぐ要の値である。** 2026-10-09 に、待機中の受信
     * 0.16 kbps（中央値）で切替が起きた事故（`docs/MANUAL-TEST.md` 節 10-5-1）は
     * この下限が無かったために起きた。下げると戻る。
     */
    val rxFloorKbps: Int = 50,
    /**
     * 応答時間の中央値の上限（ms）。これを超えたら「経路が遅い」側の条件が成立。
     * 実測の目安: 素の回線 約70ms、遅かった接続先 140〜380ms（旧仕様 §2-2）。
     */
    val degradedRttMs: Int = 250,
    /**
     * 応答時間のばらつき（最大−最小）の上限（ms）。
     * 実測の目安: 素の回線 約5ms、遅かった接続先 140〜336ms（同）。
     */
    val degradedJitterMs: Int = 150,
    /**
     * 一周して止まったあと、再開するまでの時間（分）。**`0` は再開しない。**
     *
     * 仕様書 §2-B: **再開は「切り替える」ことではない。** 一周の記録を消して
     * 資格を戻すだけで、切替には判定の3条件が別途必要である。
     */
    val rearmAfterLapMin: Int = 0,
)
```

`GroupStore` の `json` が `ignoreUnknownKeys` を持っているかを確認し、無ければ足す（消えた項目を読み飛ばすため）。

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`SlowLinkSettingsTest` の4件が通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverModels.kt app/src/main/java/net/openconnect_vpn/android/failover/GroupStore.kt app/src/test/java/net/openconnect_vpn/android/failover/SlowLinkSettingsTest.kt
git commit -m "feat(failover): 設定に応答時間・受信下限・再開間隔の項目を足す（キーは据え置き）"
```

---

## Task 3: 経路品質の判定ロジック

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/PathQualityDetector.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/PathQualityDetectorTest.kt`

**Interfaces:**
- Consumes: `ProbeOutcome`（Task 1）、`SlowLinkSettings`（Task 2）、既存の `SlowLinkDetector` / `IfaceBytes`
- Produces: `class PathQualityDetector(settings: SlowLinkSettings)` と `fun onSample(atMs: Long, bytes: IfaceBytes)` / `fun onProbe(atMs: Long, outcome: ProbeOutcome)` / `fun isDegraded(): Boolean` / `fun reset()` / `fun rttSamples(): List<Long>`

**判定（仕様書 §2）:** 窓（60秒）について次の**すべて**。

```
応答時間の中央値     > degradedRttMs
応答時間の最大−最小  > degradedJitterMs
rxFloorKbps < 受信速度（窓の平均） < slowRxKbps
```

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仕様書 §2 の判定。**実機で取った数字を入力に固定する**のがこのテストの要点で、
 * 旧仕様の受信速度だけの判定が実機で成立しなかった経緯は
 * `docs/MANUAL-TEST.md` 節 10-5-1 にある。
 */
class PathQualityDetectorTest {

    private val settings = SlowLinkSettings(
        enabled = true,
        slowRxKbps = 1000,
        rxFloorKbps = 50,
        degradedRttMs = 250,
        degradedJitterMs = 150,
    )

    /** 窓（60秒）を受信 [rxKbps] で埋め、各周期に [rtt] を与える。 */
    private fun fill(d: PathQualityDetector, rxKbps: Long, rtt: List<Long>) {
        var rx = 0L
        // 5秒ごとに13サンプル（span 60秒）。1周期=30秒ごとにプローブ2回。
        for (i in 0..12) {
            val atMs = i * 5_000L
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = rtt))
            rx += rxKbps * 5_000L / 8
        }
    }

    @Test
    fun `実測の遅い接続先で発火する`() {
        // 旧仕様 §2-2: 遅かった接続先は avg 137〜379ms / mdev 140〜336ms。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L, 280L, 310L))
        assertTrue(d.isDegraded())
    }

    @Test
    fun `素の回線の値では発火しない`() {
        // 旧仕様 §2-2: 素の回線は avg 73.5ms / mdev 5.0ms。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(70L, 74L, 73L, 71L, 75L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `2026-10-09 の待機中の誤判定は発火しない（受信が下限未満）`() {
        // 節 10-5-1: 待機中の受信は 0.16 kbps（中央値）。経路は健全だった。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 0, rtt = listOf(70L, 74L, 73L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `待機中で経路が悪くても発火しない（受信の下限が効く）`() {
        // 自己レビューで見つけた穴（仕様書 §2 の注記）。送信の条件を削除する
        // だけでは、待機中かつ経路劣化で発火してしまう。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 0, rtt = listOf(300L, 90L, 600L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `受信が上限を超えていれば経路が悪くても発火しない`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 4000, rtt = listOf(300L, 90L, 600L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `中央値だけ超えてばらつきが小さければ発火しない`() {
        // 地理的に遠い宛先。常時遅いがばらつかない。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(400L, 402L, 398L, 401L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `ばらつきだけ大きくて中央値が小さければ発火しない`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(60L, 70L, 400L, 65L, 62L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `窓が埋まる前は発火しない`() {
        val d = PathQualityDetector(settings)
        d.onSample(0L, IfaceBytes(0L, 0L))
        d.onProbe(0L, ProbeOutcome(reachable = true, rttMs = listOf(300L, 90L, 600L)))
        d.onSample(10_000L, IfaceBytes(530_000L, 0L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `応答時間のサンプルが無ければ発火しない`() {
        val d = PathQualityDetector(settings)
        var rx = 0L
        for (i in 0..12) {
            d.onSample(i * 5_000L, IfaceBytes(rx, 0L))
            rx += 424L * 5_000L / 8
        }
        assertFalse(d.isDegraded())
    }

    @Test
    fun `reset で初期状態に戻る`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.isDegraded())
        d.reset()
        assertFalse(d.isDegraded())
    }

    @Test
    fun `rxFloorKbps が 0 なら下限なしとして扱う`() {
        val d = PathQualityDetector(settings.copy(rxFloorKbps = 0))
        fill(d, rxKbps = 0, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.isDegraded())
    }

    @Test
    fun `rttSamples は窓の中のサンプルを返す`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L))
        assertTrue(d.rttSamples().isNotEmpty())
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`Unresolved reference 'PathQualityDetector'` で失敗する。

- [ ] **Step 3: 実装する**

```kotlin
package net.openconnect_vpn.android.failover

/**
 * 仕様書 §2: 応答時間と受信速度から「経路が劣化している」を判定する。
 *
 * **時計を持たない。** 時刻は [onSample] / [onProbe] の引数で受け取るので、
 * Android にも実時間にも依存しない（既存の [SlowLinkDetector] と同じ作法）。
 *
 * **なぜ応答時間が本体なのか。** 受信速度は `min(容量, 需要)` であり、受動観測では
 * 「回線が細い」と「誰も要求していない」を分離できない。旧仕様は送信速度を需要の
 * 代用にしたが、上り主体の通信で崩れた（実機で受信 5 / 送信 86 kbps の誤判定。
 * `docs/MANUAL-TEST.md` 節 10-5-1）。**応答時間は需要に依存しない。**
 *
 * **受信速度は帯の条件として残す。** 下限（[SlowLinkSettings.rxFloorKbps]）は
 * **待機中の誤判定を防ぐ要**である。これが無いと「待機中かつ経路劣化」で発火する
 * （仕様書 §2 の注記。初稿に実際にあった穴）。
 */
class PathQualityDetector(private val settings: SlowLinkSettings) {

    /** 受信速度の窓は既存の判定器を使い回す（窓の刈り取りと裁定R20 を踏襲）。 */
    private val rx = SlowLinkDetector(
        SlowLinkThresholds(
            windowSec = WINDOW_SEC,
            slowRxKbps = settings.slowRxKbps,
        ),
    )

    private class RttSample(val atMs: Long, val rttMs: Long)

    private val rtt = ArrayDeque<RttSample>()

    private val windowMs: Long get() = WINDOW_SEC * 1000L

    fun onSample(atMs: Long, bytes: IfaceBytes) {
        rx.onSample(atMs, bytes)
    }

    /**
     * プローブ1周期の結果を受け取る。**到達できなかった周期のサンプルも入れる**
     * （呼び出し側がタイムアウト値を所要時間として渡す。[ProbeOutcome] の KDoc）。
     */
    fun onProbe(atMs: Long, outcome: ProbeOutcome) {
        for (v in outcome.rttMs) rtt.addLast(RttSample(atMs, v))
        val cutoffMs = atMs - windowMs
        while (rtt.isNotEmpty() && rtt.first().atMs < cutoffMs) rtt.removeFirst()
    }

    fun reset() {
        rx.reset()
        rtt.clear()
    }

    /** 窓の中の応答時間のサンプル（一周の記録が使う。[LapRecord]）。 */
    fun rttSamples(): List<Long> = rtt.map { it.rttMs }

    fun isDegraded(): Boolean {
        // 受信速度の帯。上限は既存の判定器が持つが、**送信の条件は使わない**ので
        // ここでは窓の平均を直接見る。
        val avg = rx.windowAverage() ?: return false
        if (avg.rxKbps >= settings.slowRxKbps) return false
        if (settings.rxFloorKbps > 0 && avg.rxKbps <= settings.rxFloorKbps) return false

        val samples = rtt.map { it.rttMs }
        if (samples.isEmpty()) return false
        val o = ProbeOutcome(reachable = true, rttMs = samples)
        val median = o.medianRttMs ?: return false
        val spread = o.spreadMs ?: return false
        return median > settings.degradedRttMs && spread > settings.degradedJitterMs
    }

    private companion object {
        /**
         * 窓の長さ（秒）。**利用者が変えられる項目にしない。**
         * 検知までの時間は仕様書と画面の文面に出ており（「60秒ぶんを均して」）、
         * ここを可変にすると二重管理になる。仕様書 §2-A のとおり、
         * **比較の記録を増やすのは窓を長くすることではなく採取回数を増やすこと**である。
         */
        const val WINDOW_SEC = 60
    }
}
```

`SlowLinkDetector` に `windowAverage(): WindowAverage?` を足す（`data class WindowAverage(val rxKbps: Long, val txKbps: Long)`）。既存の `isSlow()` は**残す**（性質テストが使っている。`DECISIONS` 項目13）。

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`PathQualityDetectorTest` の12件が通り、既存の `SlowLinkDetectorTest` / `SlowLinkDetectorPropertyTest` も通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/PathQualityDetector.kt app/src/main/java/net/openconnect_vpn/android/failover/SlowLinkDetector.kt app/src/test/java/net/openconnect_vpn/android/failover/PathQualityDetectorTest.kt
git commit -m "feat(failover): 応答時間を本体にした経路品質の判定ロジック"
```

---

## Task 4: 一周の記録と最良の候補の選び方

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/LapRecord.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/LapRecordTest.kt`

**Interfaces:**
- Consumes: `ProbeOutcome`（Task 1）
- Produces: `class LapRecord`、`fun record(uuid: String, rttMs: List<Long>)`、`fun bestUuid(order: List<String>, jitterToleranceMs: Int, minSamples: Int): String?`、`fun switches(): Int`、`fun noteSwitch()`、`fun stoppedAtMs(): Long?`、`fun noteStopped(atMs: Long)`、`fun clear()`

**順位付け（仕様書 §2-A）:** 中央値の昇順 → 差が `jitterToleranceMs` 未満ならばらつきの小さい方 → それも同じならグループの順序で先の方。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 仕様書 §2-A: 一周の終わりに、応答時間が最良だった候補へ戻す。
 *
 * **なぜ応答時間で比較できるのか。** 応答時間は需要に依存しないので候補をまたいで
 * 比較できる。受信速度は `min(容量, 需要)` なので、候補A を 4 Mbps の動画視聴中に
 * 測り候補E を音声のみで測れば、比較しているのは需要であって候補の良し悪しでは
 * ない。**窓を長くしてもこの交絡は消えない。**
 */
class LapRecordTest {

    private val order = listOf("a", "b", "c")

    @Test
    fun `記録が無ければ最良は null`() {
        val r = LapRecord()
        assertNull(r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `サンプルが minSamples 未満の候補は比較から外す`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 20L, 30L))  // 3 < 5
        assertNull(r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `中央値が小さい候補を選ぶ`() {
        val r = LapRecord()
        r.record("a", listOf(400L, 400L, 400L, 400L, 400L))
        r.record("b", listOf(100L, 100L, 100L, 100L, 100L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `中央値の差が許容未満ならばらつきの小さい方を選ぶ`() {
        val r = LapRecord()
        // 中央値 200 と 250（差 50 < 150）。ばらつきは a=400、b=20。
        r.record("a", listOf(10L, 200L, 200L, 200L, 410L))
        r.record("b", listOf(245L, 250L, 250L, 250L, 265L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `中央値もばらつきも同じならグループの順序で先の方を選ぶ`() {
        val r = LapRecord()
        r.record("c", listOf(100L, 100L, 100L, 100L, 100L))
        r.record("a", listOf(100L, 100L, 100L, 100L, 100L))
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `グループに居ない候補は比較から外す`() {
        val r = LapRecord()
        r.record("zzz", listOf(10L, 10L, 10L, 10L, 10L))
        r.record("b", listOf(500L, 500L, 500L, 500L, 500L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `同じ候補を複数回記録したらサンプルは足し合わせる`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 10L, 10L))
        r.record("a", listOf(10L, 10L))
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `切替の回数を数える`() {
        val r = LapRecord()
        assertEquals(0, r.switches())
        r.noteSwitch()
        r.noteSwitch()
        assertEquals(2, r.switches())
    }

    @Test
    fun `止まった時刻を覚える`() {
        val r = LapRecord()
        assertNull(r.stoppedAtMs())
        r.noteStopped(12_345L)
        assertEquals(12_345L, r.stoppedAtMs())
    }

    @Test
    fun `clear で全部消える`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 10L, 10L, 10L, 10L))
        r.noteSwitch()
        r.noteStopped(1L)
        r.clear()
        assertEquals(0, r.switches())
        assertNull(r.stoppedAtMs())
        assertNull(r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`Unresolved reference 'LapRecord'` で失敗する。

- [ ] **Step 3: 実装する**

```kotlin
package net.openconnect_vpn.android.failover

/**
 * 仕様書 §2-A / §2-B: 一周のあいだの記録。
 *
 * **メモリ上だけで、永続化しない。** 候補ごとの測定時刻はずれるので
 * （候補A は T+0、候補F は T+6分）、**比較は一周の中だけで行い、一周が
 * 終わったら捨てる。** 横断的に「この候補はいつも良い」を学習するのは
 * 別の話（仕様書 §5 の将来の入口）。
 */
class LapRecord {

    private val rttByUuid = mutableMapOf<String, MutableList<Long>>()
    private var switchCount = 0
    private var stoppedAt: Long? = null

    /** [uuid] の候補について応答時間のサンプルを足す。 */
    fun record(uuid: String, rttMs: List<Long>) {
        if (rttMs.isEmpty()) return
        rttByUuid.getOrPut(uuid) { mutableListOf() }.addAll(rttMs)
    }

    /**
     * 最良の候補。**[order] に居て、サンプルが [minSamples] 以上ある候補だけ**を
     * 比較する。該当が無ければ null（呼び出し側はグループの先頭へ戻す）。
     *
     * 順位付け: 中央値の昇順 → 差が [jitterToleranceMs] 未満なら「有意に違わない」
     * と見なしてばらつきの小さい方 → それも同じなら [order] で先の方。
     *
     * **新しい定数を導入しない。** 「有意に違うか」の尺度は
     * [SlowLinkSettings.degradedJitterMs] が既に与えている（呼び出し側が渡す）。
     */
    fun bestUuid(order: List<String>, jitterToleranceMs: Int, minSamples: Int): String? {
        val entries = order.mapIndexedNotNull { index, uuid ->
            val samples = rttByUuid[uuid] ?: return@mapIndexedNotNull null
            if (samples.size < minSamples) return@mapIndexedNotNull null
            val o = ProbeOutcome(reachable = true, rttMs = samples)
            val median = o.medianRttMs ?: return@mapIndexedNotNull null
            val spread = o.spreadMs ?: return@mapIndexedNotNull null
            Entry(uuid, index, median, spread)
        }
        if (entries.isEmpty()) return null

        var best = entries.first()
        for (e in entries.drop(1)) {
            if (betterThan(e, best, jitterToleranceMs)) best = e
        }
        return best.uuid
    }

    private class Entry(
        val uuid: String,
        /** [order] での位置。同値のときの決定的な順位付けに使う。 */
        val index: Int,
        val median: Long,
        val spread: Long,
    )

    /**
     * 仕様書 §2-A の順位付け: 中央値の昇順 → 差が [jitterToleranceMs] 未満なら
     * 「有意に違わない」と見なしてばらつきの小さい方 → それも同じなら [Entry.index]
     * で先の方。
     *
     * **新しい定数を導入しないこと。** 「有意に違うか」の尺度は
     * [SlowLinkSettings.degradedJitterMs] が既に与えている。
     */
    private fun betterThan(a: Entry, b: Entry, jitterToleranceMs: Int): Boolean {
        val diff = kotlin.math.abs(a.median - b.median)
        if (diff >= jitterToleranceMs) return a.median < b.median
        if (a.spread != b.spread) return a.spread < b.spread
        return a.index < b.index
    }

    fun switches(): Int = switchCount

    fun noteSwitch() {
        switchCount++
    }

    fun stoppedAtMs(): Long? = stoppedAt

    fun noteStopped(atMs: Long) {
        stoppedAt = atMs
    }

    fun clear() {
        rttByUuid.clear()
        switchCount = 0
        stoppedAt = null
    }
}
```

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`LapRecordTest` の10件が通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/LapRecord.kt app/src/test/java/net/openconnect_vpn/android/failover/LapRecordTest.kt
git commit -m "feat(failover): 一周の記録と、応答時間が最良だった候補の選び方"
```

---

## Task 5: プローブの計時（port と実装）

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/Ports.kt:12-15`
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/TcpHealthProbe.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/HealthProbeTimedTest.kt`

**Interfaces:**
- Consumes: `ProbeOutcome`（Task 1）
- Produces: `HealthProbe.probeTimed(target, timeoutMs, attempts): ProbeOutcome`（既定実装あり）

**既存の `probe(): Boolean` は残す。** 既定実装で `probeTimed` に委譲し、**既存の呼び出し元と `FakeHealthProbe` を無改変で通す**（裁定72/93 と同じ既定値の作法）。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProbeTimedTest {

    /** 既定実装の検証用。`probe` だけを実装した古い形の HealthProbe。 */
    private class OldStyleProbe(private val reachable: Boolean) : HealthProbe {
        var calls = 0
        override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
            calls++
            return reachable
        }
    }

    @Test
    fun `probe だけを実装した HealthProbe でも probeTimed が動く`() = runBlocking {
        val p = OldStyleProbe(reachable = true)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertTrue(o.reachable)
        assertEquals(3, p.calls)
        assertEquals(3, o.rttMs.size)
    }

    @Test
    fun `到達できなければ reachable は false`() = runBlocking {
        val p = OldStyleProbe(reachable = false)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertFalse(o.reachable)
        assertEquals(3, o.rttMs.size)
    }

    @Test
    fun `attempts が 1 未満でも 1 回は試す`() = runBlocking {
        val p = OldStyleProbe(reachable = true)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 0)
        assertEquals(1, p.calls)
        assertEquals(1, o.rttMs.size)
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`Unresolved reference 'probeTimed'` で失敗する。

- [ ] **Step 3: 実装する**

`Ports.kt` の `HealthProbe` を置き換える。

```kotlin
interface HealthProbe {
    /** [target] へ疎通できたら true。例外は投げず false を返す。 */
    suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean

    /**
     * 仕様書 §2「測り方」: [attempts] 回続けて疎通確認を行い、**各回の所要時間**を
     * 返す。到達可否の意味は [probe] と同じで、**1回でも成功すれば到達可能**。
     *
     * **既定実装は [probe] を [attempts] 回呼んで時間を測るだけ**である。
     * これにより**既存の実装とテストの Fake は無改変で通る**（裁定72/93 と同じ
     * 既定値の作法）。実測の精度が要る本番実装（[TcpHealthProbe]）はこれを
     * 上書きする。
     *
     * **失敗した回も所要時間を数える。** 間欠的にタイムアウトする経路は実際に
     * 劣化しており、その事実を捨てる理由が無い（[ProbeOutcome] の KDoc）。
     */
    suspend fun probeTimed(target: ProbeTarget, timeoutMs: Int, attempts: Int): ProbeOutcome {
        val n = attempts.coerceAtLeast(1)
        var any = false
        val times = ArrayList<Long>(n)
        for (i in 0 until n) {
            val startNs = System.nanoTime()
            val ok = probe(target, timeoutMs)
            times.add((System.nanoTime() - startNs) / 1_000_000L)
            if (ok) any = true
        }
        return ProbeOutcome(reachable = any, rttMs = times)
    }
}
```

`TcpHealthProbe` に `probeTimed` を上書きし、1回の接続ごとに `System.nanoTime()` で計測する。**失敗した回は `timeoutMs` を所要時間として入れる**（実際の経過より短く終わる場合もあるが、「劣化の証拠」として扱う側に倒す）。既存の `probe` はそのまま残す。

```kotlin
override suspend fun probeTimed(
    target: ProbeTarget,
    timeoutMs: Int,
    attempts: Int,
): ProbeOutcome = withContext(Dispatchers.IO) {
    val n = attempts.coerceAtLeast(1)
    var any = false
    val times = ArrayList<Long>(n)
    for (i in 0 until n) {
        val startNs = System.nanoTime()
        val ok = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
        val elapsed = (System.nanoTime() - startNs) / 1_000_000L
        // 失敗した回は「少なくともタイムアウトぶん悪い」として扱う。
        times.add(if (ok) elapsed else maxOf(elapsed, timeoutMs.toLong()))
        if (ok) any = true
    }
    ProbeOutcome(reachable = any, rttMs = times)
}
```

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`HealthProbeTimedTest` の3件が通り、**既存の `FakeHealthProbe` を使うテストが1件も壊れない**ことを確認する。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/Ports.kt app/src/main/java/net/openconnect_vpn/android/failover/TcpHealthProbe.kt app/src/test/java/net/openconnect_vpn/android/failover/HealthProbeTimedTest.kt
git commit -m "feat(failover): プローブに計時付きの probeTimed を足す（既存の probe は据え置き）"
```

---

## Task 6: 状態機械への組み込み（一周後の行先と再開）

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerLapTest.kt`

**Interfaces:**
- Consumes: `LapRecord`（Task 4）、`SlowLinkSettings`（Task 2）
- Produces: `FailoverController` に供給関数 `slowLinkSettingsProvider: () -> SlowLinkSettings = { SlowLinkSettings() }` と `lapRttProvider: () -> List<Long> = { emptyList() }` を足す。既存の `slowLinkProvider: () -> Boolean` は**残す**（`PathQualityDetector.isDegraded()` を渡す）

**変えること（仕様書 §2-A / §2-B）:**

1. `Healthy` のあいだ、現候補の応答時間を一周の記録へ貯める
2. 一周の予算を使い切る or 次候補が無いとき、**最良の候補へ戻す切替を1回だけ行う**（戻り先が現候補なら切り替えない）。この切替は**予算に数えない**
3. 一周が止まった時刻を覚え、`rearmAfterLapMin` が経過したら記録を消す。**消すだけで切り替えない**

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 仕様書 §2-A（一周後の行先）と §2-B（再開）。
 *
 * **旧仕様 §4-5 は「停止する」とだけ書いて、どこで停止するかを書いていなかった。**
 * 実装は前方にしか進まないため最後のメンバーに居座っていた（実機で確認。
 * `docs/MANUAL-TEST.md` 節 10-3-2）。
 */
class FailoverControllerLapTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private var degraded = false
    private var settings = SlowLinkSettings(enabled = true)
    private var lapRtt: List<Long> = emptyList()

    private val group
        get() = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        degraded = false
        settings = SlowLinkSettings(enabled = true)
        lapRtt = emptyList()
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            slowLinkProvider = { degraded },
            slowLinkSettingsProvider = { settings },
            lapRttProvider = { lapRtt },
        )
    }

    /** [uuid] を `Healthy` まで進める（猶予も越える）。 */
    private fun toHealthy(uuid: String) {
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = uuid))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = uuid))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    /** `FailingOver` から切断確認を与えて次候補を `Healthy` まで進める。 */
    private fun confirmAndAdvance(failed: String, next: String) {
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = failed))
        assertEquals(next, vpn.connectCalls.last())
        toHealthy(next)
    }

    @Test
    fun `一周したら応答時間が最良だった候補へ戻る`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)  // uuid-a は悪い
        toHealthy("uuid-a")

        degraded = true
        controller.handle(FailoverEvent.Tick)
        lapRtt = listOf(100L, 100L, 100L, 100L, 100L)  // uuid-b が最良
        confirmAndAdvance("uuid-a", "uuid-b")

        controller.handle(FailoverEvent.Tick)
        lapRtt = listOf(500L, 500L, 500L, 500L, 500L)  // uuid-c は最悪
        confirmAndAdvance("uuid-b", "uuid-c")

        // uuid-c は最後のメンバー。次候補が無いので一周が止まる。
        controller.handle(FailoverEvent.Tick)
        val st = controller.state
        assertTrue("最良へ戻る切替が始まるはず（実際: $st）", st is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `最良へ戻る切替は一周の予算に数えない`() {
        // メンバー3件なので予算は2。最良へ戻る切替で3回目になるが、
        // それは予算の対象外である（数えると予算が1回ぶん減る）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(100L, 100L, 100L, 100L, 100L)
        toHealthy("uuid-a")
        degraded = true
        controller.handle(FailoverEvent.Tick)
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)
        confirmAndAdvance("uuid-a", "uuid-b")
        controller.handle(FailoverEvent.Tick)
        lapRtt = listOf(500L, 500L, 500L, 500L, 500L)
        confirmAndAdvance("uuid-b", "uuid-c")
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        // uuid-a が最良なので戻る。
        assertEquals("uuid-a", vpn.connectCalls.last())
    }

    @Test
    fun `戻り先が現候補と同じなら切り替えない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)
        toHealthy("uuid-a")
        degraded = true
        controller.handle(FailoverEvent.Tick)
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)
        confirmAndAdvance("uuid-a", "uuid-b")
        controller.handle(FailoverEvent.Tick)
        lapRtt = listOf(100L, 100L, 100L, 100L, 100L)  // uuid-c が最良
        confirmAndAdvance("uuid-b", "uuid-c")
        controller.handle(FailoverEvent.Tick)
        // 最良が現候補なので、無駄な切断を作らない。
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `サンプルが足りなければグループの先頭へ戻る`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(10L, 20L)  // 5 未満
        toHealthy("uuid-a")
        degraded = true
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-a", "uuid-b")
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-b", "uuid-c")
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-a", vpn.connectCalls.last())
    }

    @Test
    fun `rearmAfterLapMin が 0 なら永久に再開しない`() {
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 0)
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)
        toHealthy("uuid-a")
        degraded = true
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-a", "uuid-b")
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-b", "uuid-c")
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")

        val before = vpn.connectCalls.size
        clock.advance(24L * 60 * 60 * 1000)  // 24時間
        controller.handle(FailoverEvent.Tick)
        assertEquals("再開してはならない", before, vpn.connectCalls.size)
    }

    @Test
    fun `経過しても再開だけでは切り替わらない`() {
        // 仕様書 §2-B の要: 再開は一周の記録を消すだけで、切替には判定が要る。
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 30)
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)
        toHealthy("uuid-a")
        degraded = true
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-a", "uuid-b")
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-b", "uuid-c")
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")

        degraded = false  // 状況が改善した
        val before = vpn.connectCalls.size
        clock.advance(31L * 60 * 1000)
        controller.handle(FailoverEvent.Tick)
        assertEquals("判定が false なら何も起きない", before, vpn.connectCalls.size)
    }

    @Test
    fun `経過して劣化が続いていればもう一周する`() {
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 30)
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        lapRtt = listOf(400L, 400L, 400L, 400L, 400L)
        toHealthy("uuid-a")
        degraded = true
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-a", "uuid-b")
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-b", "uuid-c")
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")

        clock.advance(31L * 60 * 1000)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`No value passed for parameter 'slowLinkSettingsProvider'` 等で失敗する。

- [ ] **Step 3: 実装する**

`FailoverController` に次を足す。**既定値があるので既存の呼び出し元とテストは無改変で通る。**

```kotlin
/**
 * 仕様書 §4-A: 経路品質による切替の設定。**参照のたびに最新を読む**
 * （裁定72 の `groupsProvider` と同じ作法）。既定は OFF の `SlowLinkSettings()`
 * なので、既存の呼び出し元は従来どおり動く。
 */
slowLinkSettingsProvider: () -> SlowLinkSettings = { SlowLinkSettings() },
/**
 * 仕様書 §2-A: いまの候補について、窓の中の応答時間のサンプル。
 * 一周の記録（[LapRecord]）へ貯めるのに使う。既定は空＝記録しない。
 */
lapRttProvider: () -> List<Long> = { emptyList() },
```

`slowLinkLaps: MutableMap<String, SlowLinkLap>` を `MutableMap<String, LapRecord>` に置き換える。

`onTick` の `Healthy` 分岐で、`onSlowLinkSwitch` の**前に**記録を貯める。

```kotlin
// 仕様書 §2-A: いまの候補の応答時間を一周の記録へ貯める。**判定の前に行う**
// ——同じ tick の中で「貯める → 判定」の順になり、切替が決まった時点で
// その候補のサンプルが記録に入っている。
private fun recordLapRtt(s: FailoverState.Healthy) {
    val group = groupOf(s.groupId) ?: return
    val uuid = group.memberUuids.getOrNull(s.candidateIndex) ?: return
    lapRecordFor(group.id).record(uuid, lapRttProvider())
}
```

`onSlowLinkSwitch` の「次候補が無い」「予算を使い切った」の2つの門で `null` を返す前に、**最良へ戻す切替を1回だけ試す**。

```kotlin
/**
 * 仕様書 §2-A: 一周が止まるとき、応答時間が最良だった候補へ戻す。
 *
 * **旧実装は前方にしか進まないため最後のメンバーに居座っていた**（実機で確認）。
 * 最後のメンバーは「良いから」ではなく「最後だから」選ばれており、グループの
 * 順序は利用者が宣言した優先順位なので、最も優先度の低い接続先に置き去りに
 * していた。
 *
 * **この切替は一周の予算に数えない。** 数えると予算が1回ぶん減る。
 * **戻り先が現候補なら切り替えない**（無駄な切断を作らない）。
 */
private fun returnToBest(s: FailoverState.Healthy, group: FailoverGroup): FailoverState? {
    val lap = lapRecordFor(group.id)
    if (lap.stoppedAtMs() != null) return null  // 既に戻した
    val settings = slowLinkSettingsProvider()
    val best = lap.bestUuid(
        order = group.memberUuids,
        jitterToleranceMs = settings.degradedJitterMs,
        minSamples = MIN_LAP_SAMPLES,
    ) ?: group.memberUuids.firstOrNull()
    lap.noteStopped(clock.nowMs())
    val currentUuid = group.memberUuids.getOrNull(s.candidateIndex)
    if (best == null || best == currentUuid) return null
    val index = group.memberUuids.indexOf(best)
    if (index < 0) return null
    return failOver(s.groupId, s.candidateIndex, alreadyDown = false, bySlowLink = true, toIndex = index)
}
```

`failOver` に `toIndex: Int? = null` を足し、`null` なら従来どおり前方へ進み、指定があればその添字へ向かう。**既定値があるので既存の呼び出し元は無改変。**

再開は `onTick` の `Healthy` 分岐で、記録を貯めたあと・判定の前に見る。

```kotlin
/**
 * 仕様書 §2-B: 一周が止まってから [SlowLinkSettings.rearmAfterLapMin] が経過したら
 * 記録を消す。**消すだけで切り替えない**——切替には判定の3条件が別途必要なので、
 * 状況が改善していれば何も起きない。
 *
 * **裁定78 / `DECISIONS` 項目3 と混同しないこと。** この時計は「人が居るか」
 * 「指示が生きているか」を判定するものではなく、自動動作の冷却期間である
 * （`connectTimeoutSec` と同じ種類の正当な期限）。
 */
private fun maybeRearmLap(groupId: String) {
    val minutes = slowLinkSettingsProvider().rearmAfterLapMin
    if (minutes <= 0) return
    val lap = slowLinkLaps[groupId] ?: return
    val stoppedAt = lap.stoppedAtMs() ?: return
    if (clock.nowMs() - stoppedAt < minutes * 60_000L) return
    lap.clear()
}
```

`MIN_LAP_SAMPLES = 5` を companion に置き、仕様書 §2-A の「サンプルが5未満の候補は比較から外す」と対応させる KDoc を書く。

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`FailoverControllerLapTest` の7件が通り、**既存の `FailoverControllerSlowLinkTest`（13件）と `FailoverControllerInvariantTest` が壊れない**ことを確認する。

> **I5 の扱い（仕様書 §7）。** `FailoverControllerInvariantTest` の I5 は
> 「速度起因の切替は1グループあたりメンバー数−1 を超えない」である。
> **最良へ戻す切替は予算に数えないので、I5 の数え方から除外する**。
> I5 のコメントに「最良へ戻す切替（仕様書 §2-A）は数えない」と書き、
> `bySlowLink` だけでは区別できないので**除外の根拠を状態から判定できる形**
> （例: `LapRecord.stoppedAtMs() != null` の直後の1回）にすること。
> **区別できない形で I5 を緩めないこと。**

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerLapTest.kt app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerInvariantTest.kt
git commit -m "feat(failover): 一周後は最良の候補へ戻し、設定可能な間隔で再開する"
```

---

## Task 7: サービスでの配線

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt`

**Interfaces:**
- Consumes: Task 1〜6 のすべて
- Produces: なし（配線のみ）

- [ ] **Step 1: ティックの採取を差し替える**

`probe.probe(...)` の呼び出しを `probe.probeTimed(probeTarget, currentProbeTimeoutMs(), PROBE_ATTEMPTS)` に差し替え、結果を2つに使う。

```kotlin
if (controller.shouldProbeNow() || forcedByWake) {
    val outcome = probe.probeTimed(probeTarget, currentProbeTimeoutMs(), PROBE_ATTEMPTS)
    // 仕様書 §2: 到達可否の意味は従来と同じ（1回でも成功すれば到達可能）。
    // 死活判定の強さを変えないための約束（ProbeOutcome の KDoc）。
    pathQualityDetector.onProbe(clock.nowMs(), outcome)
    dispatch(FailoverEvent.ProbeResult(outcome.reachable))
}
```

`PROBE_ATTEMPTS = 5` を companion に置く。

```kotlin
/**
 * 仕様書 §2「測り方」: 1周期あたりの疎通確認の回数。
 *
 * **5 の根拠。** プローブ30秒間隔・窓60秒なら2周期ぶんが窓に入るので、
 * 候補あたり10サンプルになり、旧仕様 §2-2 が ping 10発で測ったのと同じ密度に
 * なる（仕様書 §2-A）。**窓を長くして一周を15分に伸ばすより桁違いに安い。**
 *
 * **増やすときの代償は接続の本数である**（30秒あたり5本）。減らすと
 * ばらつきの推定が粗くなる。
 */
private const val PROBE_ATTEMPTS = 5
```

- [ ] **Step 2: 判定器を差し替える**

`slowLinkDetector: SlowLinkDetector` を `pathQualityDetector: PathQualityDetector` に置き換える。`reloadSlowLinkSettings()` では**作り直す**（既存と同じ理由。閾値がコンストラクタ引数なので）。

`sampleThroughput()` の `slowLinkDetector.onSample(...)` を `pathQualityDetector.onSample(...)` に、`reset()` も同様に差し替える。**`DECISIONS` 項目15（インターフェース名を毎ティック解決し、変わったら窓を捨てる）はそのまま維持する。**

- [ ] **Step 3: 供給関数を渡す**

`FailoverController` の構築に3つ渡す。

```kotlin
slowLinkProvider = { slowLinkSettings.enabled && pathQualityDetector.isDegraded() },
slowLinkSettingsProvider = { slowLinkSettings },
lapRttProvider = { pathQualityDetector.rttSamples() },
```

- [ ] **Step 4: ビルドと既存テストを通す**

`BUILD` を実行。全件が通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt
git commit -m "feat(failover): 計時付きプローブと経路品質の判定器をサービスへ配線する"
```

---

## Task 8: 設定画面の導出文（純関数）

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/SettingsText.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/tv/SettingsTextTest.kt`

**Interfaces:**
- Consumes: `SlowLinkSettings`（Task 2）
- Produces: `object SettingsText { fun pathQualitySummary(s: SlowLinkSettings): String }`

**仕様書 §4-A「画面に出す導出文」のとおり。** 数字は設定値から出し、文面に定数を二重に書かない。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.SlowLinkSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTextTest {

    @Test
    fun `既定値の文面に3つの数字が出る`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings())
        assertTrue(t.contains("250"))
        assertTrue(t.contains("150"))
        assertTrue(t.contains("50"))
    }

    @Test
    fun `待機中は切り替わらない旨が出る`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings())
        assertTrue(t.contains("待機中は切り替わりません"))
    }

    @Test
    fun `受信の下限を 0 にすると行が消えず警告に変わる`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings(rxFloorKbps = 0))
        assertTrue("警告を出すこと", t.contains("待機中でも切り替わりえます"))
        assertTrue("消さないこと", !t.contains("待機中は切り替わりません"))
    }

    @Test
    fun `再開しない設定ではそう書く`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings(rearmAfterLapMin = 0))
        assertTrue(t.contains("一周したあとは再開しません"))
    }

    @Test
    fun `再開する設定では分数と「再開しても切り替わらない」が出る`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings(rearmAfterLapMin = 60))
        assertTrue(t.contains("60"))
        assertTrue(t.contains("条件が揃わなければ切り替わりません"))
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`Unresolved reference 'SettingsText'` で失敗する。

- [ ] **Step 3: 実装する**

```kotlin
package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.SlowLinkSettings

/**
 * 仕様書 §4-A「画面に出す導出文」。
 *
 * 既存の「現在の設定では、最短 X秒・最長 Y秒でダウンを検知します」と同じ作法で、
 * **設定値から結果の文を組み立てる。** 文面に定数を二重に書かない。
 *
 * **「待機中は切り替わりません」は必ず出す。** 2026-10-09 の事故
 * （`docs/MANUAL-TEST.md` 節 10-5-1）がこの一文で説明される項目であり、
 * 利用者が受信の下限を 0 にしたときは**行を消すのではなく警告に差し替える。**
 */
object SettingsText {

    fun pathQualitySummary(s: SlowLinkSettings): String {
        val head = "いまの設定では、往復が ${s.degradedRttMs} ms を超え、" +
            "かつばらつきが ${s.degradedJitterMs} ms を超える状態が 60 秒続いたときに" +
            "切り替えます。"
        val floor = if (s.rxFloorKbps > 0) {
            "受信が ${s.rxFloorKbps} kbps を下回っているあいだは判定しません" +
                "（待機中は切り替わりません）。"
        } else {
            "受信の下限が 0 なので、何も再生していないときでも判定します" +
                "（待機中でも切り替わりえます）。"
        }
        val rearm = if (s.rearmAfterLapMin > 0) {
            "一周したあと ${s.rearmAfterLapMin} 分で再開します。" +
                "再開しても、条件が揃わなければ切り替わりません。"
        } else {
            "一周したあとは再開しません。"
        }
        return "$head\n$floor\n$rearm"
    }
}
```

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`SettingsTextTest` の5件が通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/SettingsText.kt app/src/test/java/net/openconnect_vpn/android/tv/SettingsTextTest.kt
git commit -m "feat(tv): 設定から導かれる文面を組み立てる純関数"
```

---

## Task 9: 設定画面のステッパー

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/tv/SettingsScreen.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/tv/SettingsStepperPathQualityTest.kt`

**Interfaces:**
- Consumes: `SlowLinkSettings`（Task 2）、`SettingsText`（Task 8）
- Produces: `SettingsStepper` に `clampDegradedRttMs` / `stepDegradedRttMs` / `clampDegradedJitterMs` / `stepDegradedJitterMs` / `clampRxFloorKbps` / `stepRxFloorKbps` / `clampRearmAfterLapMin` / `stepRearmAfterLapMin`

**範囲と刻み（仕様書 §4-A の表）:**

| 項目 | 範囲 | 刻み |
|---|---|---|
| `degradedRttMs` | 50〜2000 | 50 |
| `degradedJitterMs` | 50〜1000 | 50 |
| `rxFloorKbps` | 0〜500 | 50 |
| `rearmAfterLapMin` | 0〜240 | 15 |

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** 仕様書 §4-A の表のとおり。上下限で張り付き、刻みどおりに動くこと。 */
class SettingsStepperPathQualityTest {

    @Test
    fun `応答時間の上限は範囲で丸まる`() {
        assertEquals(50, SettingsStepper.clampDegradedRttMs(0))
        assertEquals(2000, SettingsStepper.clampDegradedRttMs(9999))
        assertEquals(250, SettingsStepper.clampDegradedRttMs(250))
    }

    @Test
    fun `応答時間の上限は50刻みで動き上下限で張り付く`() {
        assertEquals(300, SettingsStepper.stepDegradedRttMs(250, +1))
        assertEquals(200, SettingsStepper.stepDegradedRttMs(250, -1))
        assertEquals(2000, SettingsStepper.stepDegradedRttMs(2000, +1))
        assertEquals(50, SettingsStepper.stepDegradedRttMs(50, -1))
    }

    @Test
    fun `ばらつきの上限は50刻みで 50 から 1000`() {
        assertEquals(50, SettingsStepper.clampDegradedJitterMs(0))
        assertEquals(1000, SettingsStepper.clampDegradedJitterMs(9999))
        assertEquals(200, SettingsStepper.stepDegradedJitterMs(150, +1))
    }

    @Test
    fun `受信の下限は 0 を含み 500 まで`() {
        assertEquals(0, SettingsStepper.clampRxFloorKbps(-10))
        assertEquals(500, SettingsStepper.clampRxFloorKbps(9999))
        assertEquals(0, SettingsStepper.stepRxFloorKbps(50, -1))
        assertEquals(100, SettingsStepper.stepRxFloorKbps(50, +1))
    }

    @Test
    fun `再開間隔は 0 を含み 15分刻みで 240 まで`() {
        assertEquals(0, SettingsStepper.clampRearmAfterLapMin(-1))
        assertEquals(240, SettingsStepper.clampRearmAfterLapMin(9999))
        assertEquals(15, SettingsStepper.stepRearmAfterLapMin(0, +1))
        assertEquals(0, SettingsStepper.stepRearmAfterLapMin(15, -1))
    }
}
```

- [ ] **Step 2: 失敗を確認する**

`BUILD` を実行。`Unresolved reference 'clampDegradedRttMs'` で失敗する。

- [ ] **Step 3: 実装する**

`SettingsStepper` に4組の `clamp` / `step` と定数を足す。既存の `clampSlowRxKbps` と同じ形にし、**各定数の KDoc に仕様書 §4-A の「上げ下げの影響」を要約して書く**（とくに `rxFloorKbps` は「下げると 2026-10-09 の事故が戻る」を明記）。

`SettingsScreen` に `TvStepperRow` を4つ足し、`SettingsText.pathQualitySummary(...)` の結果を `Text` で出す。**`demandTxKbps` に関する記述は無いので消すものは無い**（`SlowLinkThresholds` 側の定数で、Task 3 で判定から外れた）。

既存の「速度低下で切り替える: ON/OFF」のラベルを**「経路品質の低下で切り替える」**に改める（仕様書 §4）。Task 8 の文面と合わせ、**【実験機能】の注記は維持する**（`DECISIONS` 項目17。実機確認が済むまで外さない）。

- [ ] **Step 4: 通ることを確認する**

`BUILD` を実行。`SettingsStepperPathQualityTest` の5件が通る。

- [ ] **Step 5: コミットする**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/SettingsScreen.kt app/src/test/java/net/openconnect_vpn/android/tv/SettingsStepperPathQualityTest.kt
git commit -m "feat(tv): 経路品質の設定4項目のステッパーと導出文を設定画面に足す"
```

---

## Task 10: 文書の更新

**Files:**
- Modify: `README.md`
- Modify: `docs/DECISIONS.md`
- Modify: `docs/MANUAL-TEST.md`

**Interfaces:**
- Consumes: Task 1〜9
- Produces: なし

- [ ] **Step 1: `README.md` の「仕組み」を書き直す**

判定が受信速度から応答時間へ移ったことを反映する。**実験機能の警告は維持する**が、「待機中にも誤判定する」の記述は「**この誤判定を直すために判定の根拠を変えた。実機での確認は未了**」に差し替える。**「直した」と「動くと確かめた」を混同しないこと**（`DECISIONS` 項目15 の末尾と同じ規律）。

- [ ] **Step 2: `docs/DECISIONS.md` を更新する**

- **項目1**（`demandTxKbps` を上げない）: **削除せず、「この条件自体が改訂で消えた」と追記する。** 経緯を消すと、将来また送信速度を需要の代用に使う案が出る
- **項目17**（送信は代用になっていない）: 改訂で解決した旨と、改訂版仕様への参照を追記する
- **新しい項目を足す**: 「窓の長さ（60秒）を可変にしない」「最良へ戻す切替は一周の予算に数えない」「再開の時計は裁定78 の禁止に当たらない」の3つ。どれも**善意で元に戻されうる**判断である

- [ ] **Step 3: `docs/MANUAL-TEST.md` の節10 を書き直す**

旧 10-1〜10-6 を改訂版の項目に差し替える。**10-5（待機中に切り替わらない）をこの改訂の合否を決める項目として最初に置く。** 旧版の観測記録（節 10-3-2 / 10-5-1）は**歴史として残す**。

**全項目を「未実施」と明記する。** 実装が通ったことと実機で動くことは別である。

- [ ] **Step 4: コミットする**

```bash
git add README.md docs/DECISIONS.md docs/MANUAL-TEST.md
git commit -m "docs: 判定の根拠が応答時間へ移ったことを反映し、実機確認の項目を書き直す"
```

---

## 実機での確認（コントローラが行う。実装者は行わない）

**すべて未実施。** 実装が通ったことと実機で動くことは別である。

| # | 確認すること | 前提 |
|---|---|---|
| 1 | **設定画面の回帰**（裁定85）。項目が4つ増えたあとも全項目へ D-pad で到達でき、画面外に落ちていない | — |
| 2 | **導出文が設定値どおりに変わる。** 受信の下限を 0 にすると警告に差し替わる | — |
| 3 | **待機中に切り替わらない**（この改訂の合否を決める項目） | ON、通信を起こさず7分以上。**端末のバックグラウンド通信のバーストを含む時間帯を狙う** |
| 4 | **遅い候補で切り替わり、理由が画面に出る** | ON、旧仕様 §2-1 の遅い候補 |
| 5 | **速い候補では切り替わらない** | ON、受信が下限を上回る通信 |
| 6 | **一周したら最良の候補へ戻る** | ON、全候補が劣化する状況 |
| 7 | **`rearmAfterLapMin = 0` で再開しない／値を入れると再開する。再開しただけでは切り替わらない** | ON |

**走行の前後に必ずグループ行の状態を読むこと**（2026-10-08 の教訓。`tun` の存在だけではエンジンが接続中と考えている証拠にならない）。
**切替の検出は `/proc/net/dev` のカウンタの後退で行うこと**（経路の名前は再利用されるので、名前の変化では見落とす）。

---

## 自己レビューの結果

**仕様書の網羅。** §1（原因）→ Task 3 のテスト、§2（判定）→ Task 1・3・5、§2-A（一周後の行先）→ Task 4・6、§2-B（再開）→ Task 6、§3（限界）→ 文書、§4（変更箇所）→ Task 2・5・7・9、§4-A（設定）→ Task 2・8・9、§5（将来）→ 文書に残す、§6（裁定）→ 各タスクの制約、§7（テスト方針）→ 各タスクのテスト。**抜けは無い。**

**型の一貫性。** `ProbeOutcome.medianRttMs` / `spreadMs`（Task 1）を Task 3・4 が使う。`SlowLinkSettings` の項目名（Task 2）を Task 3・6・8・9 が使う。`LapRecord.bestUuid(order, jitterToleranceMs, minSamples)`（Task 4）を Task 6 が使う。`probeTimed(target, timeoutMs, attempts)`（Task 5）を Task 7 が使う。**食い違いは無い。**

**空欄の走査。** 初稿の Task 4 に `error("placeholder")` を含むジェネリクス版の比較関数を書き、直後に注意書きで補う形にしていた。**注意書きで補うのではなく消すのが正しい**（計画の中のコードは、そのまま書けるものだけであるべき）。削除して正しい実装だけを残した。他に `TBD` / `TODO` / 「適切に」の類は無い。
