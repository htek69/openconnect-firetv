# スループット低下による切り替え 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 繋がってはいるが遅い接続先を検知し、既存の切替経路に載せて次候補へ移す。

**Architecture:** トンネルのバイトカウンタ（`/proc/net/dev` の `tun0`）を毎ティック読み、窓で均した受信・送信速度から「遅い」を判定する純粋ロジックを作る。判定結果は供給関数で `FailoverController` へ渡し、検知時は既存の `failOver` 経路をそのまま使う。追加の通信は発生しない。

**Tech Stack:** Kotlin（Android 非依存の純粋ロジック＋既存の `FailoverService`）、kotlinx.serialization（設定の保存）、JUnit4（JVM 単体テスト）、Compose for TV（設定画面）

**Spec:** `docs/superpowers/specs/2026-09-27-throughput-failover-design.md`

## Global Constraints

計画1・計画2 と同じ制約がすべて適用される。本計画で特に効くもの:

- **`FailoverController` は Android API を参照しない**。新しい判定ロジックも同じ
- **既存の Java コードは変更しない**。トンネルのカウンタは `/proc/net/dev` から読む
- **依存を追加しない。バージョンを変えない**
- **安全策 S1〜S4 と、実機で計測済みの裁定 39 / 44 / 75 / 77 / 80 / 82 / 83 / 84 / 85 / 87 / 90 / 92 / 93 / 94 / 95 を壊さない**
- **新しい切替経路を作らない**。検知後は既存の `failOver`（Ruling 25 の2段階切替）に載せる
- **既定は OFF**（仕様書 5）。有効化は利用者の明示操作
- **ビルドは Docker のイメージ（ダイジェスト固定）でのみ行う**
- **認証情報に触れない。ログを足さない**

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

### 純粋ロジック（JVM 単体テストで検証）

- `app/src/main/java/net/openconnect_vpn/android/failover/ProcNetDev.kt`
  `/proc/net/dev` の内容から1つのインターフェースの累計バイト数を取り出す
- `app/src/main/java/net/openconnect_vpn/android/failover/SlowLinkDetector.kt`
  累計バイト数の列から「遅い」を判定する。時計も I/O も持たない

### 境界とプラットフォーム連携

- `Ports.kt` に `ThroughputSource` を追加
- `ProcNetDevThroughputSource.kt` が実際に読む実装
- `FailoverModels.kt` に `SlowLinkSettings`
- `GroupStore.kt` に読み書きと監視キー
- `FailoverController.kt` に供給関数と判定
- `FailoverService.kt` で採取して判定器へ流す

### 画面

- `SettingsScreen.kt` に ON/OFF と閾値
- `HomeRows.kt` に切替理由の表示

---

## Task 1: `/proc/net/dev` の解析

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/ProcNetDev.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/ProcNetDevTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `data class IfaceBytes(val rxBytes: Long, val txBytes: Long)`、
  `fun parseProcNetDev(text: String, iface: String): IfaceBytes?`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcNetDevTest {

    private val sample =
        "Inter-|   Receive                                                |  Transmit\n" +
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
        "  tun0: 47719469   39329    0    0    0     0          0         0  4699017   29801    0    0    0     0       0          0\n" +
        " wlan0: 59014309   60183    0    0    0     0          0         0 15105423   61624  107    0    0     0       0          0\n"

    @Test
    fun `tun0 の受信と送信のバイト数を取り出す`() {
        val v = parseProcNetDev(sample, "tun0")
        assertEquals(47719469L, v!!.rxBytes)
        assertEquals(4699017L, v.txBytes)
    }

    @Test
    fun `別のインターフェースも取り出せる`() {
        val v = parseProcNetDev(sample, "wlan0")
        assertEquals(59014309L, v!!.rxBytes)
        assertEquals(15105423L, v.txBytes)
    }

    @Test
    fun `存在しないインターフェースは null`() {
        assertNull(parseProcNetDev(sample, "tun9"))
    }

    @Test
    fun `インターフェース名の部分一致で誤って拾わない`() {
        assertNull(parseProcNetDev(sample, "tun"))
    }

    @Test
    fun `空文字列は null`() {
        assertNull(parseProcNetDev("", "tun0"))
    }

    @Test
    fun `列が足りない行は null`() {
        assertNull(parseProcNetDev("  tun0: 123 456\n", "tun0"))
    }
}
```

- [ ] **Step 2: テストが落ちることを確認**

`BUILD` を `--tests '*ProcNetDevTest*'` 付きで実行。
期待: `Unresolved reference: parseProcNetDev` でコンパイルエラー。

- [ ] **Step 3: 最小の実装を書く**

```kotlin
package net.openconnect_vpn.android.failover

/** 1つのネットワークインターフェースの累計バイト数。 */
data class IfaceBytes(val rxBytes: Long, val txBytes: Long)

/**
 * `/proc/net/dev` の内容から [iface] の行を探し、受信・送信の累計バイト数を返す。
 *
 * 書式は「`  tun0: <rx bytes> <rx packets> ... <tx bytes> <tx packets> ...`」で、
 * 名前に続くコロンの後に受信側8列・送信側8列が並ぶ。受信バイト数は1列目、
 * 送信バイト数は9列目である。
 *
 * 見つからない場合や列が足りない場合は null を返す。**例外は投げない**。
 * 端末やカーネルで書式が違いうるので、呼び出し側が「測れない」として扱えるようにする。
 */
fun parseProcNetDev(text: String, iface: String): IfaceBytes? {
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        val name = trimmed.substringBefore(':', missingDelimiterValue = "")
        if (name != iface) continue
        val cols = trimmed.substringAfter(':').trim().split(Regex("\\s+"))
        if (cols.size < 9) return null
        val rx = cols[0].toLongOrNull() ?: return null
        val tx = cols[8].toLongOrNull() ?: return null
        return IfaceBytes(rx, tx)
    }
    return null
}
```

- [ ] **Step 4: テストが通ることを確認**

Step 2 と同じコマンド。期待: 6件すべて PASS。

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/ProcNetDev.kt app/src/test/java/net/openconnect_vpn/android/failover/ProcNetDevTest.kt
git commit -m "feat(failover): /proc/net/dev から tun0 のバイト数を読む解析を足す

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: 遅さの判定ロジック

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/SlowLinkDetector.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/SlowLinkDetectorTest.kt`

**Interfaces:**
- Consumes: `IfaceBytes`（Task 1）
- Produces:
  - `data class SlowLinkThresholds(val windowSec: Int = 60, val slowRxKbps: Int = 1000, val demandTxKbps: Int = 5)`
  - `class SlowLinkDetector(thresholds: SlowLinkThresholds)`
  - `fun onSample(atMs: Long, bytes: IfaceBytes)` / `fun isSlow(): Boolean` / `fun reset()`

**判定（仕様書 4-2）:** 「受信 < `slowRxKbps`」かつ「送信 > `demandTxKbps`」が
`windowSec` のあいだ連続したとき `isSlow()` が true。条件が破れたら数え直す。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowLinkDetectorTest {

    private val th = SlowLinkThresholds(windowSec = 60, slowRxKbps = 1000, demandTxKbps = 5)

    /** [rxKbps] / [txKbps] の速度で [sec] 秒ぶん、1秒刻みで供給し、最後の時刻を返す。 */
    private fun SlowLinkDetector.feed(startMs: Long, sec: Int, rxKbps: Int, txKbps: Int): Long {
        var rx = 0L
        var tx = 0L
        var t = startMs
        onSample(t, IfaceBytes(rx, tx))
        repeat(sec) {
            t += 1_000
            rx += rxKbps * 1000L / 8
            tx += txKbps * 1000L / 8
            onSample(t, IfaceBytes(rx, tx))
        }
        return t
    }

    @Test
    fun `窓を満たすまでは遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 59, rxKbps = 100, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `受信が閾値未満で送信があり窓を満たしたら遅い`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
    }

    @Test
    fun `送信がほとんど無ければ待機中とみなし遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 120, rxKbps = 0, txKbps = 1)
        assertFalse(d.isSlow())
    }

    @Test
    fun `受信が閾値以上なら遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 120, rxKbps = 4000, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `途中で速くなったら数え直す`() {
        val d = SlowLinkDetector(th)
        val t = d.feed(0, sec = 50, rxKbps = 100, txKbps = 50)
        d.feed(t, sec = 5, rxKbps = 4000, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `reset すると数え直しになる`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
        d.reset()
        assertFalse(d.isSlow())
    }

    @Test
    fun `カウンタが巻き戻ったら数え直す`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
        d.onSample(100_000, IfaceBytes(0, 0))
        assertFalse(d.isSlow())
    }

    @Test
    fun `同じ時刻の重複サンプルで壊れない`() {
        val d = SlowLinkDetector(th)
        d.onSample(0, IfaceBytes(0, 0))
        d.onSample(0, IfaceBytes(0, 0))
        assertFalse(d.isSlow())
    }
}
```

- [ ] **Step 2: テストが落ちることを確認**

`BUILD` を `--tests '*SlowLinkDetectorTest*'` 付きで実行。
期待: `Unresolved reference: SlowLinkDetector`。

- [ ] **Step 3: 最小の実装を書く**

```kotlin
package net.openconnect_vpn.android.failover

/** 遅さの判定に使う閾値。既定値の根拠は仕様書 2 の実測。 */
data class SlowLinkThresholds(
    /** 条件が連続していなければならない長さ。 */
    val windowSec: Int = 60,
    /** これを下回る受信速度を「遅い」とみなす。 */
    val slowRxKbps: Int = 1000,
    /** これを上回る送信速度を「使おうとしている」とみなす。 */
    val demandTxKbps: Int = 5,
)

/**
 * トンネルの累計バイト数の列から「遅い」を判定する。
 *
 * **時計を持たない。** 時刻は [onSample] の引数で受け取るので、Android にも
 * コルーチンにも依存せず JVM の単体テストで完全に固定できる。
 *
 * 判定は仕様書 4-2 のとおり、受信が閾値未満かつ送信が閾値超という状態が
 * [SlowLinkThresholds.windowSec] のあいだ連続したとき。条件が破れたらそこから数え直す。
 *
 * 累計値が前回より小さくなった場合（再接続でインターフェースが作り直された等）は
 * 差分に意味が無いので数え直す。
 */
class SlowLinkDetector(private val thresholds: SlowLinkThresholds) {

    private var lastAtMs: Long? = null
    private var lastBytes: IfaceBytes? = null
    private var slowSinceMs: Long? = null
    private var slowForMs: Long = 0

    fun reset() {
        lastAtMs = null
        lastBytes = null
        slowSinceMs = null
        slowForMs = 0
    }

    fun onSample(atMs: Long, bytes: IfaceBytes) {
        val prevAt = lastAtMs
        val prevBytes = lastBytes
        lastAtMs = atMs
        lastBytes = bytes
        if (prevAt == null || prevBytes == null) return

        val elapsedMs = atMs - prevAt
        if (elapsedMs <= 0) return

        val dRx = bytes.rxBytes - prevBytes.rxBytes
        val dTx = bytes.txBytes - prevBytes.txBytes
        if (dRx < 0 || dTx < 0) {
            slowSinceMs = null
            slowForMs = 0
            return
        }

        val rxKbps = (dRx * 8 * 1000 / elapsedMs / 1000).toInt()
        val txKbps = (dTx * 8 * 1000 / elapsedMs / 1000).toInt()

        if (rxKbps < thresholds.slowRxKbps && txKbps > thresholds.demandTxKbps) {
            if (slowSinceMs == null) slowSinceMs = prevAt
            slowForMs = atMs - (slowSinceMs ?: prevAt)
        } else {
            slowSinceMs = null
            slowForMs = 0
        }
    }

    fun isSlow(): Boolean = slowForMs >= thresholds.windowSec * 1000L
}
```

- [ ] **Step 4: テストが通ることを確認**

Step 2 と同じコマンド。期待: 8件すべて PASS。

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/failover/SlowLinkDetector.kt app/src/test/java/net/openconnect_vpn/android/failover/SlowLinkDetectorTest.kt
git commit -m "feat(failover): 窓で均した速度から遅さを判定する純粋ロジックを足す

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: 設定の保存

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverModels.kt`
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/GroupStore.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/SlowLinkSettingsStoreTest.kt`

**Interfaces:**
- Consumes: `KeyValueStore`（既存）
- Produces:
  - `@Serializable data class SlowLinkSettings(val enabled: Boolean = false, val slowRxKbps: Int = 1000)`
  - `GroupStore.loadSlowLinkSettings(): SlowLinkSettings`
  - `GroupStore.saveSlowLinkSettings(settings: SlowLinkSettings)`

保存キーは `slow_link_settings_v1`。**既存の `loadProbeSchedule` / `saveProbeSchedule`
と同じ形に揃えること**（同ファイルの実装を読むこと）。壊れた値は既定値へ倒す。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowLinkSettingsStoreTest {

    @Test
    fun `未保存なら既定値を返す`() {
        val s = GroupStore(InMemoryKeyValueStore()).loadSlowLinkSettings()
        assertFalse(s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }

    @Test
    fun `保存した値を読み戻せる`() {
        val g = GroupStore(InMemoryKeyValueStore())
        g.saveSlowLinkSettings(SlowLinkSettings(enabled = true, slowRxKbps = 1500))
        val s = g.loadSlowLinkSettings()
        assertTrue(s.enabled)
        assertEquals(1500, s.slowRxKbps)
    }

    @Test
    fun `壊れた値なら既定値に戻す`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("slow_link_settings_v1", "{ not json")
        val s = GroupStore(kv).loadSlowLinkSettings()
        assertFalse(s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }
}
```

> `InMemoryKeyValueStore` は既存のテストが使っているものを再利用する。名前や作り方が
> 違う場合は `app/src/test/java/net/openconnect_vpn/android/failover/` の既存テストに
> 合わせること。

- [ ] **Step 2: テストが落ちることを確認**

`BUILD` を `--tests '*SlowLinkSettingsStoreTest*'` 付きで実行。

- [ ] **Step 3: 実装する**

`FailoverModels.kt` に追加:

```kotlin
/**
 * スループット低下による切替の設定（仕様書 5）。グループ単位ではなく全体の設定。
 *
 * **既定は無効。** 仕様書 6 の誤判定を承知のうえで有効化してもらうため。
 */
@Serializable
data class SlowLinkSettings(
    val enabled: Boolean = false,
    val slowRxKbps: Int = 1000,
)
```

`GroupStore.kt` に `KEY_SLOW_LINK = "slow_link_settings_v1"` と読み書きを追加。

- [ ] **Step 4: 監視キーに追加する**

`FailoverService` の設定変更リスナが見るキー一覧（裁定72 で導入。`GroupStore.kt` の
定数群）に `slow_link_settings_v1` を加える。**これが無いと設定を変えても
動作中のサービスに届かない**（計画1 の F2 と同じ穴）。

- [ ] **Step 5: 全テストを通す**

`BUILD` を実行。期待: 既存を含めて全件 PASS。

- [ ] **Step 6: コミット**

```bash
git add -A
git commit -m "feat(failover): スループット切替の設定を保存し、変更を動作中のサービスへ届ける

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: 状態機械への組み込み

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverController.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/failover/FailoverControllerSlowLinkTest.kt`

**Interfaces:**
- Consumes: なし（判定結果を真偽値で受け取るだけ）
- Produces: `FailoverController` のコンストラクタに
  `slowLinkProvider: () -> Boolean = { false }` を追加。
  **既定値があるので既存の呼び出しとテストは無改変で通る**（裁定93 の
  `dialogHostAttachedProvider` と同じ形）

**規則（仕様書 4-3 / 4-4 / 4-5）:**

- `Healthy` の tick で `slowLinkProvider()` が true なら、その候補を遅いとみなして
  既存の `failOver(...)` に入る。**新しい経路を作らない**
- 判定しない場合: `autoFailoverEnabled` が false、下層ネットワークが無い（S2）、
  接続直後の猶予中（`graceAfterConnectSec`）
- **一周したら止める**: そのグループで速度起因の切替を候補数だけ行ったら、以後
  `slowLinkProvider()` を見ない。解除は `onUserConnect`（裁定35 と同じ考え方）と
  グループ構成の変更時。**時間による自動解除は設けない**
- `onUnattendedPromptTimeout` と `onConnectTimeout` の既存の判定順序を変えない。
  速度の判定は、それらが `Healthy` のままにした経路の**後ろ**に足す

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 仕様書 4-3/4-4/4-5: スループット低下による切替。
 *
 * 判定そのものは [SlowLinkDetector] が持ち、ここへは真偽値で届く。よってこの
 * テストが固定するのは「**いつ切り替え、いつ切り替えないか**」だけである。
 */
class FailoverControllerSlowLinkTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private var slow = false
    private var autoFailover = true

    private val group
        get() = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = autoFailover,
            config = FailoverConfig(),
        )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        slow = false
        autoFailover = true
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            slowLinkProvider = { slow },
        )
    }

    /** uuid-a を `Healthy` まで進める（猶予も越える）。 */
    private fun toHealthyOnA() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L) // graceAfterConnectSec = 15 を越える
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `遅いと判定されたら次候補へ切り替える`() {
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)

        // Ruling 25: まず切断を要求し、確認を待つ。
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `機能が無効なら遅くても切り替えない`() {
        toHealthyOnA()
        slow = false
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `自動切替が OFF のグループでは切り替えない`() {
        autoFailover = false
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `下層ネットワークが無いときは切り替えない`() {
        toHealthyOnA()
        slow = true
        network.available = false
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `接続直後の猶予中は切り替えない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        slow = true
        clock.advance(5_000L) // 猶予 15 秒の内側
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state !is FailoverState.FailingOver)
    }

    /** 候補2件を速度起因で一周させる。 */
    private fun lapOnceBySlowness() {
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
        controller.handle(FailoverEvent.Tick) // uuid-b も遅い → 2回目の切替
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))
    }

    @Test
    fun `一周したら速度による切替を止める`() {
        lapOnceBySlowness()
        // 一周後にどこかの候補が Healthy になっても、遅いままでは切り替えない。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        val before = vpn.connectCalls.size
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(before, vpn.connectCalls.size)
    }

    @Test
    fun `ユーザー操作の接続で一周の記録が解除される`() {
        lapOnceBySlowness()
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    @Test
    fun `死活による切替は一周後も従来どおり働く`() {
        lapOnceBySlowness()
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        repeat(3) { // failureThreshold
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
    }
}
```

> **注意**: 上のコードは既存テスト（`FailoverControllerDialogHostTest.kt`）の
> 組み立て方に合わせてある。`FakeVpnController` の公開フィールド名
> （`connectCalls` など）と `FailoverEvent` の引数名は実物を読んで確認し、
> 食い違っていたら**実物に合わせて直すこと**。テストの意図は変えない。

- [ ] **Step 2: テストが落ちることを確認**

`BUILD` を `--tests '*FailoverControllerSlowLinkTest*'` 付きで実行。

- [ ] **Step 3: 実装する**

コンストラクタ引数、グループ ID ごとの速度起因切替の回数、`Healthy` の tick での判定。

- [ ] **Step 4: 全テストを通す**

`BUILD` を実行。期待: 新規8件を含め**全件 PASS**。既存を1件も壊さないこと。

- [ ] **Step 5: コミット**

```bash
git add -A
git commit -m "feat(failover): 遅い候補を既存の2段階切替に載せ、一周したら止める

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: サービスでの採取と配線

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/Ports.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/failover/ProcNetDevThroughputSource.kt`
- Modify: `app/src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt`

**Interfaces:**
- Consumes: `parseProcNetDev`（Task 1）、`SlowLinkDetector`（Task 2）、
  `GroupStore.loadSlowLinkSettings`（Task 3）、`slowLinkProvider`（Task 4）
- Produces: `interface ThroughputSource { fun read(): IfaceBytes? }`、
  `class ProcNetDevThroughputSource(iface: String = "tun0") : ThroughputSource`

- [ ] **Step 1: 境界と実装を書く**

`Ports.kt` に追加:

```kotlin
/** トンネルの累計バイト数を読む。読めなければ null を返し、例外は投げない。 */
interface ThroughputSource {
    fun read(): IfaceBytes?
}
```

新規ファイル:

```kotlin
package net.openconnect_vpn.android.failover

import java.io.File

/**
 * `/proc/net/dev` から [iface] の累計バイト数を読む。
 *
 * コアの `VPNStats` ではなくカーネルのカウンタを読むのは、**既存 Java を変更せずに
 * 済む**ためである（サービスへの bind も要らない）。測るのは tun インターフェース上の
 * バイト数で、これは利用者がトンネル越しに実際にやり取りした量そのものである。
 */
class ProcNetDevThroughputSource(private val iface: String = "tun0") : ThroughputSource {
    override fun read(): IfaceBytes? =
        runCatching { parseProcNetDev(File("/proc/net/dev").readText(), iface) }.getOrNull()
}
```

- [ ] **Step 2: `FailoverService` に配線する**

- `onCreate` で `SlowLinkDetector` と `ProcNetDevThroughputSource` を作る
- ティックのたびに `source.read()?.let { detector.onSample(clock.nowMs(), it) }`
- `FailoverController` へ `slowLinkProvider = { slowLinkSettings.enabled && detector.isSlow() }`
- **候補が変わったとき・切断したときは `detector.reset()`**。前の候補の測定値を
  持ち越さないため。呼ぶ場所は既存の状態反映処理に合わせる
- 設定変更リスナで `slowLinkSettings` を読み直す
- 閾値は `SlowLinkThresholds(slowRxKbps = slowLinkSettings.slowRxKbps)` で作る

**読めなかったときは `onSample` を呼ばない。「測れない」を「遅い」と解釈しない。**

- [ ] **Step 3: ビルドと全テスト**

`BUILD` を実行。期待: 全件 PASS、`assembleDebug` 成功。

- [ ] **Step 4: コミット**

```bash
git add -A
git commit -m "feat(failover): トンネルのバイト数を毎ティック採取して判定器へ流す

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: 設定画面

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/tv/SettingsScreen.kt`

**Interfaces:**
- Consumes: `GroupStore.loadSlowLinkSettings` / `saveSlowLinkSettings`（Task 3）

**要件:**

- **「速度低下で切り替える」の ON / OFF**。既存の自動切替トグルと同じ見た目・操作
- **受信速度の下限**。既存の「プローブ間隔」「失敗閾値」のステッパーと同じ作り。
  単位 kbps、刻み 250、範囲 250〜5000
- OFF のときも値は編集でき、保存される（後で ON にしたときに効く）
- 説明文を1つ置く。**何を見て切り替えるのか**と、**低ビットレートの再生を
  誤判定しうること**（仕様書 6-1）に必ず触れる
- **裁定85 の `verticalScroll` を壊さない**。項目が増えるので、追加後に
  すべての項目へ D-pad で到達できることをコントローラが実機で確認する

- [ ] **Step 1: 実装する**

既存の `SettingsScreen.kt` を読み、同じ構造で項目を足す。

- [ ] **Step 2: ビルドと全テスト**

`BUILD` を実行。

- [ ] **Step 3: コミット**

```bash
git add -A
git commit -m "feat(tv): 設定画面に速度低下による切替の ON/OFF と閾値を足す

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: 切替理由の表示

**Files:**
- Modify: `app/src/main/java/net/openconnect_vpn/android/tv/HomeRows.kt`
- Modify: `app/src/test/java/net/openconnect_vpn/android/tv/HomeRowsTest.kt`

**要件（仕様書 5）:** 速度低下で切り替えたときは、グループの行にそれと分かる文言を
出す（例: `速度低下で切替`）。**理由の分からない自動切替を作らない。**
文言の組み立ては既存の `HomeRows` と同じく純粋関数で行い、テストで固定する。

- [ ] **Step 1: 失敗するテストを書く**

既存の `HomeRowsTest` を読み、同じ形で「速度低下による切替の直後は、その理由が
文言に含まれる」ことを表明するテストを書く。

- [ ] **Step 2: テストが落ちることを確認**

`BUILD` を `--tests '*HomeRowsTest*'` 付きで実行。

- [ ] **Step 3: 実装する**

- [ ] **Step 4: 全テストを通す**

`BUILD` を実行。

- [ ] **Step 5: コミット**

```bash
git add -A
git commit -m "feat(tv): 速度低下で切り替えた理由を一覧に出す

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## 実機での確認（コントローラが行う）

単体テストでは押さえられない。結果は `docs/MANUAL-TEST.md` に追記する。

1. **設定画面の回帰**: 項目が増えたあとも、すべての項目へ D-pad で到達でき、
   画面外に落ちていないこと（裁定85）
2. **OFF のとき何も起きない**: 既定のまま長時間つないでも切替が起きないこと
3. **ON で遅い候補につないだとき**: 仕様書 2-1 で 424 kbps だった候補につなぎ、
   60秒ほどで切り替わること。**理由が画面に出ること**
4. **速い候補では切り替わらない**: 4 Mbps 出る候補で誤判定しないこと
5. **待機中に切り替わらない**: 動画を止めた状態で放置しても切替が起きないこと
   （送信側の条件が効いていることの確認）
6. **一周したら止まる**: 全候補が遅い状況を作り、切替が止まること

> 3 と 4 は**実測済みの候補がある**（仕様書 2-1）ので再現できる。
> 5 は誤判定のうち最も起きやすいので必ず確認する。
