# Fire TV OpenConnect クライアント 実装計画 2/2: TV UI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 計画1 で作ったフェイルオーバーエンジンを、Fire TV のリモコンだけで完全に操作できる Compose for TV の UI で包む。

**Architecture:** 画面は Compose for TV で新規に書き、既存の Java + `PreferenceFragment` の UI には触らない（設定画面の奥から到達できる形で残す）。UI ロジックのうち検証・整列・表示行の組み立ては Android 非依存の純粋 Kotlin に切り出して JVM 単体テストで検証し、Compose 側は状態を描くだけにする。

**Tech Stack:** Kotlin 2.1.0 / Compose BOM 2025.01.00 / androidx.tv:tv-material 1.0.0 / kotlinx-coroutines 1.10.1 / JUnit 4

**Spec:** `docs/superpowers/specs/2026-09-19-firetv-openconnect-design.md`

**前提:** 計画1（`docs/superpowers/plans/2026-09-19-firetv-openconnect-engine.md`）が完了していること。本計画は `FailoverService.connectGroup` / `FailoverService.disconnect` / `GroupStore` / `FailoverGroup` を利用する。

## Global Constraints

計画1 と同じ制約がすべて適用される。以下は本計画で特に効くもの。

- **既存の Java コードは原則無改変**: 本計画で変更を許すのは `AndroidManifest.xml`（ランチャー移設）のみ
- **AGP 8.7.2 / Gradle 8.10.2 / Kotlin 2.1.0 / Compose BOM 2025.01.00 / tv-material 1.0.0** は変更しない
- **ビルドは常に WSL2 側で実行する**
- **入力項目はサーバ URL と表示名の2つだけ**: 資格情報はフォーム構造の MD5 を鍵にして保存されるため、接続前に書き込めない（仕様書 9.1）
- **プロファイル作成時に `batch_mode = "enabled"` を必ず設定する**: これが無い場合、再接続ごとに認証ダイアログが出て自動切替が停止する（仕様書 9.2）
- **フェイルバックは実装しない**
- **初期フォーカスを必ず設定する**: TV UI で最も多い不具合は「フォーカスがどこにも無い」状態

---

## File Structure

### 純粋ロジック（JVM 単体テストで検証）

| ファイル | 責務 |
|---|---|
| `tv/ServerAddressValidator.kt` | サーバ URL と表示名の入力検証 |
| `tv/GroupEditor.kt` | グループのメンバー追加・削除・並び替え（純関数） |
| `tv/HomeRows.kt` | プロファイル・グループ・接続状態から一覧の表示行を組み立てる |

### Compose 画面とプラットフォーム連携

| ファイル | 責務 |
|---|---|
| `tv/TvMainActivity.kt` | 単一 Activity。画面遷移を保持する |
| `tv/TvTheme.kt` | tv-material のテーマ定義 |
| `tv/ProfileRepository.kt` | 既存 `ProfileManager` へのアダプタ。作成・削除・一覧 |
| `tv/HomeScreen.kt` | 接続先とグループの一覧 |
| `tv/ProfileEditScreen.kt` | 接続先の追加・編集フォーム |
| `tv/GroupEditScreen.kt` | グループの作成・編集・並び替え |
| `tv/SettingsScreen.kt` | プローブ宛先・間隔・閾値・既存 UI への入口 |
| `tv/ConfirmDialog.kt` | 削除確認 |
| `tv/VpnConsent.kt` | 初回の VPN 許可取得 |

### テスト

| ファイル | 責務 |
|---|---|
| `tv/ServerAddressValidatorTest.kt` | 入力検証 |
| `tv/GroupEditorTest.kt` | 並び替えとメンバー操作 |
| `tv/HomeRowsTest.kt` | 表示行の組み立て |

### 変更する既存ファイル

| ファイル | 変更内容 |
|---|---|
| `app/src/main/AndroidManifest.xml` | ランチャーを `TvMainActivity` へ移設、`MainActivity` から `LAUNCHER`/`LEANBACK_LAUNCHER` を外す |
| `app/src/main/java/net/openconnect_vpn/android/tv/BuildProbeActivity.kt` | 削除（計画1 Task 2 の検証用） |

---

## Phase 4: TV UI

### Task 1: 入力検証とプロファイルリポジトリ

UI を書く前に、UI が依存する2つの部品を先に固める。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/ServerAddressValidator.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/ProfileRepository.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/tv/ServerAddressValidatorTest.kt`

**Interfaces:**
- Consumes: 計画1 の何も使わない（独立）
- Produces:
  - `sealed interface AddressValidation { data object Valid; data class Invalid(val reason: InvalidReason) }`
  - `enum class InvalidReason { Empty, ContainsWhitespace, NoHost, BadScheme }`
  - `object ServerAddressValidator { fun validate(raw: String): AddressValidation; fun normalize(raw: String): String }`
  - `class ProfileRepository(private val context: Context)` with
    - `fun list(): List<ProfileSummary>`
    - `fun create(serverAddress: String, displayName: String): String`（戻り値は UUID）
    - `fun rename(uuid: String, displayName: String)`
    - `fun ensureBatchMode(uuid: String)` — 既存プロファイルに `batch_mode = "enabled"` を補う
    - `fun delete(uuid: String)`
  - `data class ProfileSummary(val uuid: String, val name: String, val serverAddress: String)`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/tv/ServerAddressValidatorTest.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressValidatorTest {

    @Test
    fun `ホスト名だけなら有効`() {
        assertTrue(ServerAddressValidator.validate("vpn.example.com") is AddressValidation.Valid)
    }

    @Test
    fun `https 付きなら有効`() {
        assertTrue(ServerAddressValidator.validate("https://vpn.example.com") is AddressValidation.Valid)
    }

    @Test
    fun `ポート付きなら有効`() {
        assertTrue(ServerAddressValidator.validate("vpn.example.com:4443") is AddressValidation.Valid)
    }

    @Test
    fun `IPv4 アドレスなら有効`() {
        assertTrue(ServerAddressValidator.validate("192.0.2.10") is AddressValidation.Valid)
    }

    @Test
    fun `空文字は Empty で無効`() {
        val result = ServerAddressValidator.validate("")
        assertEquals(InvalidReason.Empty, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `空白のみは Empty で無効`() {
        val result = ServerAddressValidator.validate("   ")
        assertEquals(InvalidReason.Empty, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `途中に空白があれば無効`() {
        val result = ServerAddressValidator.validate("vpn example.com")
        assertEquals(InvalidReason.ContainsWhitespace, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `http は無効`() {
        val result = ServerAddressValidator.validate("http://vpn.example.com")
        assertEquals(InvalidReason.BadScheme, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `スキームだけでホストが無ければ無効`() {
        val result = ServerAddressValidator.validate("https://")
        assertEquals(InvalidReason.NoHost, (result as AddressValidation.Invalid).reason)
    }

    @Test
    fun `正規化は前後の空白を落とす`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("  vpn.example.com  "))
    }

    @Test
    fun `正規化は https スキームを落として素のホストにする`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("https://vpn.example.com"))
    }

    @Test
    fun `正規化は末尾のスラッシュを落とす`() {
        assertEquals("vpn.example.com", ServerAddressValidator.normalize("https://vpn.example.com/"))
    }

    @Test
    fun `正規化はパスを保持する`() {
        assertEquals("vpn.example.com/group1", ServerAddressValidator.normalize("https://vpn.example.com/group1"))
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*ServerAddressValidatorTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: ServerAddressValidator` で FAIL

- [ ] **Step 3: 検証ロジックを実装する**

`app/src/main/java/net/openconnect_vpn/android/tv/ServerAddressValidator.kt`:

```kotlin
package net.openconnect_vpn.android.tv

sealed interface AddressValidation {
    data object Valid : AddressValidation
    data class Invalid(val reason: InvalidReason) : AddressValidation
}

enum class InvalidReason {
    Empty,
    ContainsWhitespace,
    NoHost,
    BadScheme,
}

/**
 * サーバアドレスの入力検証と正規化。
 * Android API を使わないため JVM 単体テストで全ケースを検証できる。
 */
object ServerAddressValidator {

    fun validate(raw: String): AddressValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return AddressValidation.Invalid(InvalidReason.Empty)
        if (trimmed.any { it.isWhitespace() }) {
            return AddressValidation.Invalid(InvalidReason.ContainsWhitespace)
        }
        if (trimmed.startsWith("http://")) {
            return AddressValidation.Invalid(InvalidReason.BadScheme)
        }
        val withoutScheme = trimmed.removePrefix("https://")
        if (withoutScheme.isEmpty() || withoutScheme.startsWith("/")) {
            return AddressValidation.Invalid(InvalidReason.NoHost)
        }
        return AddressValidation.Valid
    }

    /**
     * 既存コアの `server_address` に入れる形に整える。
     * スキームは落とし、ホスト（とパス）だけを残す。
     */
    fun normalize(raw: String): String =
        raw.trim()
            .removePrefix("https://")
            .removeSuffix("/")
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*ServerAddressValidatorTest*' 2>&1 | tail -20
```

Expected: PASS（13 tests）

- [ ] **Step 5: ProfileRepository を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/ProfileRepository.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import android.content.Context
import net.openconnect_vpn.android.core.ProfileManager

data class ProfileSummary(
    val uuid: String,
    val name: String,
    val serverAddress: String,
)

/**
 * 既存の ProfileManager への薄いアダプタ。
 *
 * プロファイル作成時に batch_mode を "enabled" にするのが最も重要な役目である。
 * これが無いと再接続ごとに認証ダイアログが出て自動フェイルオーバーが止まる（仕様書 9.2）。
 */
class ProfileRepository(private val context: Context) {

    fun list(): List<ProfileSummary> {
        ProfileManager.init(context)
        return ProfileManager.getProfiles()
            .map { profile ->
                ProfileSummary(
                    uuid = profile.uuidString,
                    name = profile.name ?: profile.uuidString,
                    serverAddress = profile.mPrefs.getString("server_address", "") ?: "",
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 接続先を作成して UUID を返す。
     * [serverAddress] は正規化済みの値を渡すこと。
     */
    fun create(serverAddress: String, displayName: String): String {
        ProfileManager.init(context)
        val profile = ProfileManager.create(serverAddress)
        profile.mPrefs.edit()
            .putString("server_address", serverAddress)
            .putString("batch_mode", BATCH_MODE_ENABLED)
            .apply()
        if (displayName.isNotBlank()) {
            profile.mPrefs.edit().putString("profile_name", displayName).apply()
        }
        return profile.uuidString
    }

    fun rename(uuid: String, displayName: String) {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return
        profile.mPrefs.edit().putString("profile_name", displayName).apply()
    }

    /** 既存プロファイルに batch_mode が無い場合に補う（フォーク元で作った既存分の救済）。 */
    fun ensureBatchMode(uuid: String) {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return
        if (profile.mPrefs.getString("batch_mode", null) != BATCH_MODE_ENABLED) {
            profile.mPrefs.edit().putString("batch_mode", BATCH_MODE_ENABLED).apply()
        }
    }

    fun delete(uuid: String) {
        ProfileManager.init(context)
        ProfileManager.delete(uuid)
    }

    private companion object {
        const val BATCH_MODE_ENABLED = "enabled"
    }
}
```

- [ ] **Step 6: ビルドが通ることを確認する**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

`profile.name` / `profile.uuidString` / `profile.mPrefs` が解決できない場合は、実際の getter 名を確認して合わせる。

```bash
grep -nE 'public ' app/src/main/java/net/openconnect_vpn/android/VpnProfile.java
```

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/ \
        app/src/test/java/net/openconnect_vpn/android/tv/
git commit -m "feat(tv): 入力検証とプロファイルリポジトリ

作成時に batch_mode=enabled を設定する。これが無いと再接続ごとに
認証ダイアログが出て自動フェイルオーバーが停止する。"
```

---

### Task 2: グループ編集ロジック（並び替え）

グループの並び順が優先順位そのものなので、この操作は純関数として切り出して網羅的にテストする。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/GroupEditor.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/tv/GroupEditorTest.kt`

**Interfaces:**
- Consumes: `FailoverGroup`（計画1 Task 4）
- Produces: `object GroupEditor` with
  - `fun addMember(group: FailoverGroup, uuid: String): FailoverGroup`
  - `fun removeMember(group: FailoverGroup, uuid: String): FailoverGroup`
  - `fun moveUp(group: FailoverGroup, uuid: String): FailoverGroup`
  - `fun moveDown(group: FailoverGroup, uuid: String): FailoverGroup`
  - `fun setAutoFailover(group: FailoverGroup, enabled: Boolean): FailoverGroup`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/tv/GroupEditorTest.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditorTest {

    private fun group(vararg members: String) = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = members.toList(),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Test
    fun `メンバーを末尾に追加する`() {
        val result = GroupEditor.addMember(group("a", "b"), "c")
        assertEquals(listOf("a", "b", "c"), result.memberUuids)
    }

    @Test
    fun `既に居るメンバーは重複追加しない`() {
        val result = GroupEditor.addMember(group("a", "b"), "a")
        assertEquals(listOf("a", "b"), result.memberUuids)
    }

    @Test
    fun `メンバーを削除する`() {
        val result = GroupEditor.removeMember(group("a", "b", "c"), "b")
        assertEquals(listOf("a", "c"), result.memberUuids)
    }

    @Test
    fun `居ないメンバーの削除は何もしない`() {
        val result = GroupEditor.removeMember(group("a", "b"), "z")
        assertEquals(listOf("a", "b"), result.memberUuids)
    }

    @Test
    fun `優先順位を1つ上げる`() {
        val result = GroupEditor.moveUp(group("a", "b", "c"), "b")
        assertEquals(listOf("b", "a", "c"), result.memberUuids)
    }

    @Test
    fun `先頭のメンバーはこれ以上上げられない`() {
        val result = GroupEditor.moveUp(group("a", "b", "c"), "a")
        assertEquals(listOf("a", "b", "c"), result.memberUuids)
    }

    @Test
    fun `優先順位を1つ下げる`() {
        val result = GroupEditor.moveDown(group("a", "b", "c"), "b")
        assertEquals(listOf("a", "c", "b"), result.memberUuids)
    }

    @Test
    fun `末尾のメンバーはこれ以上下げられない`() {
        val result = GroupEditor.moveDown(group("a", "b", "c"), "c")
        assertEquals(listOf("a", "b", "c"), result.memberUuids)
    }

    @Test
    fun `居ないメンバーの移動は何もしない`() {
        assertEquals(listOf("a", "b"), GroupEditor.moveUp(group("a", "b"), "z").memberUuids)
        assertEquals(listOf("a", "b"), GroupEditor.moveDown(group("a", "b"), "z").memberUuids)
    }

    @Test
    fun `自動切替を切り替える`() {
        val off = GroupEditor.setAutoFailover(group("a"), enabled = false)
        assertFalse(off.autoFailoverEnabled)

        val on = GroupEditor.setAutoFailover(off, enabled = true)
        assertTrue(on.autoFailoverEnabled)
    }

    @Test
    fun `操作してもグループの id と名前は変わらない`() {
        val result = GroupEditor.moveDown(GroupEditor.addMember(group("a"), "b"), "a")
        assertEquals("g1", result.id)
        assertEquals("自宅優先", result.name)
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*GroupEditorTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: GroupEditor` で FAIL

- [ ] **Step 3: GroupEditor を実装する**

`app/src/main/java/net/openconnect_vpn/android/tv/GroupEditor.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverGroup

/**
 * グループのメンバー操作。すべて純関数で、元のオブジェクトは変更しない。
 * memberUuids の並び順が優先順位を表すため、移動操作の正しさが直接動作に効く。
 */
object GroupEditor {

    fun addMember(group: FailoverGroup, uuid: String): FailoverGroup =
        if (uuid in group.memberUuids) group
        else group.copy(memberUuids = group.memberUuids + uuid)

    fun removeMember(group: FailoverGroup, uuid: String): FailoverGroup =
        group.copy(memberUuids = group.memberUuids.filterNot { it == uuid })

    fun moveUp(group: FailoverGroup, uuid: String): FailoverGroup = move(group, uuid, offset = -1)

    fun moveDown(group: FailoverGroup, uuid: String): FailoverGroup = move(group, uuid, offset = 1)

    fun setAutoFailover(group: FailoverGroup, enabled: Boolean): FailoverGroup =
        group.copy(autoFailoverEnabled = enabled)

    private fun move(group: FailoverGroup, uuid: String, offset: Int): FailoverGroup {
        val index = group.memberUuids.indexOf(uuid)
        if (index < 0) return group
        val target = index + offset
        if (target < 0 || target >= group.memberUuids.size) return group
        val reordered = group.memberUuids.toMutableList()
        reordered[index] = reordered[target]
        reordered[target] = uuid
        return group.copy(memberUuids = reordered)
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*GroupEditorTest*' 2>&1 | tail -20
```

Expected: PASS（11 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/GroupEditor.kt \
        app/src/test/java/net/openconnect_vpn/android/tv/GroupEditorTest.kt
git commit -m "feat(tv): グループ編集を純関数として実装

memberUuids の並び順が優先順位なので、移動操作を網羅的にテストした。"
```

---

### Task 3: 一覧の表示行の組み立て

画面に何をどの順で出すかを純粋ロジックにして、Compose 側は描くだけにする。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/HomeRows.kt`
- Test: `app/src/test/java/net/openconnect_vpn/android/tv/HomeRowsTest.kt`

**Interfaces:**
- Consumes: `ProfileSummary`（Task 1）、`FailoverGroup` / `FailoverState`（計画1）
- Produces:
  - `sealed interface HomeRow` with `GroupRow`, `SectionHeader`, `ProfileRow`, `AddProfile`
  - `object HomeRows { fun build(groups: List<FailoverGroup>, profiles: List<ProfileSummary>, state: FailoverState): List<HomeRow> }`
  - `enum class ConnectionBadge { None, Connecting, Verifying, Connected, Retrying }`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/net/openconnect_vpn/android/tv/HomeRowsTest.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {

    private val profiles = listOf(
        ProfileSummary("uuid-a", "sv1", "sv1.example.com"),
        ProfileSummary("uuid-b", "sv2", "sv2.example.com"),
    )

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Test
    fun `グループが先に来て次に個別の接続先が並ぶ`() {
        val rows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)

        assertTrue(rows[0] is HomeRow.GroupRow)
        assertTrue(rows[1] is HomeRow.SectionHeader)
        assertTrue(rows[2] is HomeRow.ProfileRow)
        assertTrue(rows[3] is HomeRow.ProfileRow)
        assertTrue(rows[4] is HomeRow.AddProfile)
    }

    @Test
    fun `グループが無ければグループ行もセクション見出しも出ない`() {
        val rows = HomeRows.build(emptyList(), profiles, FailoverState.Idle)

        assertTrue(rows.none { it is HomeRow.GroupRow })
        assertTrue(rows.none { it is HomeRow.SectionHeader })
        assertTrue(rows[0] is HomeRow.ProfileRow)
    }

    @Test
    fun `接続先が無くても追加行だけは出る`() {
        val rows = HomeRows.build(emptyList(), emptyList(), FailoverState.Idle)

        assertEquals(1, rows.size)
        assertTrue(rows[0] is HomeRow.AddProfile)
    }

    @Test
    fun `未接続ならバッジは None`() {
        val rows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)
        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `接続中のグループには Connecting バッジが付く`() {
        val state = FailoverState.Connecting("g1", candidateIndex = 0, startedAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Connecting, row.badge)
    }

    @Test
    fun `健全なグループには Connected バッジと現在の候補名が付く`() {
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Connected, row.badge)
        assertEquals("sv2", row.activeMemberName)
    }

    @Test
    fun `疎通確認中は Verifying バッジ`() {
        val state = FailoverState.Verifying("g1", candidateIndex = 0, connectedAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)
        assertEquals(ConnectionBadge.Verifying, (rows[0] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `枯渇中は Retrying バッジ`() {
        val state = FailoverState.Exhausted("g1", attempt = 0, retryAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)
        assertEquals(ConnectionBadge.Retrying, (rows[0] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `別のグループが接続中でも当該グループのバッジは None`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.Healthy("g2", candidateIndex = 0, consecutiveFailures = 0, lastProbeAtMs = 0L)

        val rows = HomeRows.build(listOf(group, other), profiles, state)

        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
        assertEquals(ConnectionBadge.Connected, (rows[1] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `自動切替の状態が行に反映される`() {
        val rows = HomeRows.build(
            listOf(group, group.copy(id = "g2", autoFailoverEnabled = false)),
            profiles,
            FailoverState.Idle,
        )

        assertTrue((rows[0] as HomeRow.GroupRow).autoFailoverEnabled)
        assertTrue(!(rows[1] as HomeRow.GroupRow).autoFailoverEnabled)
    }

    @Test
    fun `グループのメンバー数が行に載る`() {
        val rows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)
        assertEquals(2, (rows[0] as HomeRow.GroupRow).memberCount)
    }
}
```

- [ ] **Step 2: テストを実行して失敗を確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*HomeRowsTest*' 2>&1 | tail -20
```

Expected: コンパイルエラー `Unresolved reference: HomeRows` で FAIL

- [ ] **Step 3: HomeRows を実装する**

`app/src/main/java/net/openconnect_vpn/android/tv/HomeRows.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverState

enum class ConnectionBadge {
    None,
    Connecting,
    Verifying,
    Connected,
    Retrying,
}

sealed interface HomeRow {

    data class GroupRow(
        val groupId: String,
        val name: String,
        val memberCount: Int,
        val autoFailoverEnabled: Boolean,
        val badge: ConnectionBadge,
        /** 現在つながっている候補の表示名。未接続なら null。 */
        val activeMemberName: String?,
    ) : HomeRow

    data class SectionHeader(val title: String) : HomeRow

    data class ProfileRow(
        val uuid: String,
        val name: String,
        val serverAddress: String,
    ) : HomeRow

    data object AddProfile : HomeRow
}

/**
 * 一覧画面に出す行を組み立てる純粋ロジック。
 * Compose 側はこの結果を描くだけにして、表示の判断をここに集約する。
 */
object HomeRows {

    fun build(
        groups: List<FailoverGroup>,
        profiles: List<ProfileSummary>,
        state: FailoverState,
    ): List<HomeRow> {
        val rows = mutableListOf<HomeRow>()

        groups.forEach { group ->
            rows += HomeRow.GroupRow(
                groupId = group.id,
                name = group.name,
                memberCount = group.memberUuids.size,
                autoFailoverEnabled = group.autoFailoverEnabled,
                badge = badgeFor(group.id, state),
                activeMemberName = activeMemberName(group, profiles, state),
            )
        }

        if (groups.isNotEmpty() && profiles.isNotEmpty()) {
            rows += HomeRow.SectionHeader("個別の接続先")
        }

        profiles.forEach { profile ->
            rows += HomeRow.ProfileRow(
                uuid = profile.uuid,
                name = profile.name,
                serverAddress = profile.serverAddress,
            )
        }

        rows += HomeRow.AddProfile
        return rows
    }

    private fun badgeFor(groupId: String, state: FailoverState): ConnectionBadge = when (state) {
        is FailoverState.Connecting -> if (state.groupId == groupId) ConnectionBadge.Connecting else ConnectionBadge.None
        is FailoverState.Verifying -> if (state.groupId == groupId) ConnectionBadge.Verifying else ConnectionBadge.None
        is FailoverState.Healthy -> if (state.groupId == groupId) ConnectionBadge.Connected else ConnectionBadge.None
        is FailoverState.FailingOver -> if (state.groupId == groupId) ConnectionBadge.Connecting else ConnectionBadge.None
        is FailoverState.Exhausted -> if (state.groupId == groupId) ConnectionBadge.Retrying else ConnectionBadge.None
        FailoverState.Idle -> ConnectionBadge.None
    }

    private fun activeMemberName(
        group: FailoverGroup,
        profiles: List<ProfileSummary>,
        state: FailoverState,
    ): String? {
        val index = when (state) {
            is FailoverState.Connecting -> state.takeIf { it.groupId == group.id }?.candidateIndex
            is FailoverState.Verifying -> state.takeIf { it.groupId == group.id }?.candidateIndex
            is FailoverState.Healthy -> state.takeIf { it.groupId == group.id }?.candidateIndex
            else -> null
        } ?: return null

        val uuid = group.memberUuids.getOrNull(index) ?: return null
        return profiles.firstOrNull { it.uuid == uuid }?.name
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :app:testDebugUnitTest --tests '*HomeRowsTest*' 2>&1 | tail -20
```

Expected: PASS（11 tests）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/HomeRows.kt \
        app/src/test/java/net/openconnect_vpn/android/tv/HomeRowsTest.kt
git commit -m "feat(tv): 一覧の表示行を純粋ロジックとして組み立てる

表示の判断を HomeRows に集約し、Compose 側は描くだけにした。
どのグループにどのバッジが付くかを網羅的にテストした。"
```

---

### Task 4: TvMainActivity とテーマ、ランチャー移設

ここで初めて実機のホーム画面から TV UI が立ち上がるようになる。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/TvTheme.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/TvMainActivity.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/TvNav.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/java/net/openconnect_vpn/android/tv/BuildProbeActivity.kt`

**Interfaces:**
- Consumes: Task 1〜3 の全部、計画1 の `GroupStore` / `PrefsKeyValueStore` / `FailoverService`
- Produces:
  - `class TvMainActivity : ComponentActivity()`
  - `sealed interface TvScreen { data object Home; data class EditProfile(val uuid: String?); data class EditGroup(val groupId: String?); data object Settings }`
  - `@Composable fun TvTheme(content: @Composable () -> Unit)`

- [ ] **Step 1: テーマと画面定義を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/TvTheme.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** TV は暗い部屋で遠くから見るため、暗色固定にする。 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
```

`app/src/main/java/net/openconnect_vpn/android/tv/TvNav.kt`:

```kotlin
package net.openconnect_vpn.android.tv

/** 画面遷移。ライブラリを増やさず sealed interface と状態で表現する。 */
sealed interface TvScreen {
    data object Home : TvScreen

    /** [uuid] が null なら新規作成。 */
    data class EditProfile(val uuid: String?) : TvScreen

    /** [groupId] が null なら新規作成。 */
    data class EditGroup(val groupId: String?) : TvScreen

    data object Settings : TvScreen
}
```

- [ ] **Step 2: TvMainActivity を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/TvMainActivity.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.preference.PreferenceManager
import net.openconnect_vpn.android.failover.GroupStore
import net.openconnect_vpn.android.failover.PrefsKeyValueStore

class TvMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profiles = ProfileRepository(this)
        val groupStore = GroupStore(
            PrefsKeyValueStore(PreferenceManager.getDefaultSharedPreferences(this)),
        )

        setContent {
            TvTheme {
                var screen by remember { mutableStateOf<TvScreen>(TvScreen.Home) }

                when (val current = screen) {
                    TvScreen.Home -> HomeScreen(
                        profiles = profiles,
                        groupStore = groupStore,
                        onNavigate = { screen = it },
                    )

                    is TvScreen.EditProfile -> ProfileEditScreen(
                        profiles = profiles,
                        editingUuid = current.uuid,
                        onDone = { screen = TvScreen.Home },
                    )

                    is TvScreen.EditGroup -> GroupEditScreen(
                        profiles = profiles,
                        groupStore = groupStore,
                        editingGroupId = current.groupId,
                        onDone = { screen = TvScreen.Home },
                    )

                    TvScreen.Settings -> SettingsScreen(
                        groupStore = groupStore,
                        onDone = { screen = TvScreen.Home },
                    )
                }
            }
        }
    }
}
```

この時点では `HomeScreen` / `ProfileEditScreen` / `GroupEditScreen` / `SettingsScreen` が未実装なのでコンパイルが通らない。Task 5〜8 で順に実装する。まずは各画面の仮実装を置いてコンパイルを通す。

`app/src/main/java/net/openconnect_vpn/android/tv/Placeholders.kt`（Task 8 完了時に削除する）:

```kotlin
package net.openconnect_vpn.android.tv

import androidx.compose.runtime.Composable
import net.openconnect_vpn.android.failover.GroupStore

@Composable
fun ProfileEditScreen(profiles: ProfileRepository, editingUuid: String?, onDone: () -> Unit) {
    // Task 6 で実装する
}

@Composable
fun GroupEditScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    editingGroupId: String?,
    onDone: () -> Unit,
) {
    // Task 7 で実装する
}

@Composable
fun SettingsScreen(groupStore: GroupStore, onDone: () -> Unit) {
    // Task 8 で実装する
}
```

- [ ] **Step 3: マニフェストのランチャーを移設する**

`AndroidManifest.xml` の `MainActivity` の `intent-filter` から `LAUNCHER` と `LEANBACK_LAUNCHER` を外す。変更後の `MainActivity` はこうなる。

```xml
        <activity
            android:exported="true"
            android:name=".MainActivity"
            android:uiOptions="splitActionBarWhenNarrow"
            tools:ignore="ExportedActivity" />
```

`BuildProbeActivity` の宣言を削除し、代わりに `TvMainActivity` を追加する。

```xml
        <activity
            android:name=".tv.TvMainActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
```

- [ ] **Step 4: BuildProbeActivity を削除する**

```bash
git rm app/src/main/java/net/openconnect_vpn/android/tv/BuildProbeActivity.kt
```

- [ ] **Step 5: HomeScreen の仮実装を置いてビルドを通す**

Task 5 で本実装に差し替えるが、ここでコンパイルを通しておく。`Placeholders.kt` に追加する。

```kotlin
@Composable
fun HomeScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    onNavigate: (TvScreen) -> Unit,
) {
    // Task 5 で実装する
}
```

- [ ] **Step 6: ビルドしてホーム画面から起動できることを確認する**

```bash
./gradlew assembleDebug 2>&1 | tail -20
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`。Fire TV のホーム画面からアプリを起動すると、黒い空画面（`TvMainActivity`）が出る。既存のスマホ UI がランチャーから消えていることも確認する。

- [ ] **Step 7: コミット**

```bash
git add -A
git commit -m "feat(tv): TvMainActivity を追加しランチャーを移設

MainActivity からランチャーカテゴリを外し、TvMainActivity を入口にした。
既存のスマホ UI は削除せず、設定画面から到達できるようにする（Task 8）。"
```

---

### Task 5: HomeScreen（接続先とグループの一覧）

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/HomeScreen.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/ConfirmDialog.kt`
- Modify: `app/src/main/java/net/openconnect_vpn/android/tv/Placeholders.kt`（`HomeScreen` の仮実装を削除）

**Interfaces:**
- Consumes: Task 3 の `HomeRows` / `HomeRow` / `ConnectionBadge`、Task 1 の `ProfileRepository`、計画1 の `GroupStore` / `FailoverService`
- Produces:
  - `@Composable fun HomeScreen(profiles: ProfileRepository, groupStore: GroupStore, onNavigate: (TvScreen) -> Unit)`
  - `@Composable fun ConfirmDialog(message: String, confirmLabel: String = "削除する", onConfirm: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: 確認ダイアログを書く**

`app/src/main/java/net/openconnect_vpn/android/tv/ConfirmDialog.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 削除などの取り消せない操作の前に1枚挟む。
 * 初期フォーカスは必ず「キャンセル」側に置く（誤爆防止）。
 */
@Composable
fun ConfirmDialog(
    message: String,
    confirmLabel: String = "削除する",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                ) {
                    Text("キャンセル")
                }
                Button(onClick = onConfirm) {
                    Text(confirmLabel)
                }
            }
        }
    }

    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
}
```

- [ ] **Step 2: HomeScreen を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/HomeScreen.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.failover.FailoverService
import net.openconnect_vpn.android.failover.FailoverState
import net.openconnect_vpn.android.failover.GroupStore

@Composable
fun HomeScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    onNavigate: (TvScreen) -> Unit,
) {
    val context = LocalContext.current

    var reloadToken by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<HomeRow.ProfileRow?>(null) }

    val profileList = remember(reloadToken) { profiles.list() }
    val groupList = remember(reloadToken) {
        groupStore.loadGroups(profileList.map { it.uuid }.toSet())
    }

    // 状態機械の現在状態は FailoverService が通知で見せる。
    // 画面側は最後に指示した内容ではなく Idle を起点に描き、
    // 詳細はサービスの通知に委ねる（UI が真実を持たない）。
    val rows = remember(reloadToken, profileList, groupList) {
        HomeRows.build(groupList, profileList, FailoverState.Idle)
    }

    val firstItemFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("接続先", style = MaterialTheme.typography.headlineMedium)
            Card(onClick = { onNavigate(TvScreen.Settings) }) {
                Text("設定", modifier = Modifier.padding(16.dp))
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows) { row ->
                val isFirst = row === rows.firstOrNull()
                val focusModifier =
                    if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier

                when (row) {
                    is HomeRow.GroupRow -> GroupCard(
                        row = row,
                        modifier = focusModifier,
                        onConnect = { FailoverService.connectGroup(context, row.groupId) },
                        onEdit = { onNavigate(TvScreen.EditGroup(row.groupId)) },
                    )

                    is HomeRow.SectionHeader -> Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    is HomeRow.ProfileRow -> ProfileCard(
                        row = row,
                        modifier = focusModifier,
                        onEdit = { onNavigate(TvScreen.EditProfile(row.uuid)) },
                        onDelete = { pendingDelete = row },
                    )

                    HomeRow.AddProfile -> Card(
                        onClick = { onNavigate(TvScreen.EditProfile(null)) },
                        modifier = focusModifier,
                    ) {
                        Text("＋ 接続先を追加", modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            message = "「${target.name}」を削除しますか？",
            onConfirm = {
                profiles.delete(target.uuid)
                pendingDelete = null
                reloadToken++
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // TV UI で最も多い不具合は「フォーカスがどこにも無い」状態。
    // 画面表示時に必ず先頭項目へフォーカスを置く。
    LaunchedEffect(rows) {
        runCatching { firstItemFocus.requestFocus() }
    }
}

@Composable
private fun GroupCard(
    row: HomeRow.GroupRow,
    modifier: Modifier,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(onClick = onConnect, onLongClick = onEdit, modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            val auto = if (row.autoFailoverEnabled) "自動切替 ON" else "自動切替 OFF"
            val active = row.activeMemberName?.let { " / 接続中: $it" } ?: ""
            Text(
                "${row.memberCount} 件の候補 · $auto$active",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("長押しで編集", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfileCard(
    row: HomeRow.ProfileRow,
    modifier: Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onEdit, onLongClick = onDelete, modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(row.serverAddress, style = MaterialTheme.typography.bodySmall)
            Text("長押しで削除", style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

- [ ] **Step 3: Placeholders.kt から HomeScreen の仮実装を削除する**

`Placeholders.kt` の `HomeScreen` 関数を削除する。他の3つは残す。

- [ ] **Step 4: ビルドしてインストールする**

```bash
./gradlew assembleDebug 2>&1 | tail -30
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`Card` の `onLongClick` が tv-material 1.0.0 に存在しない場合は、`Modifier.onKeyEvent` でメニューキー（`KEYCODE_MENU`）を拾う形に置き換える。

```kotlin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType

// Card(onClick = ..., modifier = modifier.onKeyEvent { event ->
//     if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) { onEdit(); true } else false
// })
```

- [ ] **Step 5: リモコンで一覧が操作できることを確認する**

Fire TV のリモコンで次を確認する。

1. アプリ起動時に先頭項目にフォーカスが当たっている
2. 上下キーで項目間を移動できる
3. 「設定」ボタンへ移動できる
4. 「＋ 接続先を追加」を選ぶと空画面（Task 6 で実装）に遷移する

- [ ] **Step 6: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/
git commit -m "feat(tv): 接続先とグループの一覧画面

表示判断は HomeRows に委ね、画面は描画とフォーカス管理だけを担う。
画面表示時に必ず先頭項目へフォーカスを置く。削除は確認ダイアログを挟む。"
```

---

### Task 6: ProfileEditScreen（接続先の追加・編集）

仕様書 9.1 のとおり、入力はサーバ URL と表示名の2つだけにする。

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/ProfileEditScreen.kt`
- Modify: `app/src/main/java/net/openconnect_vpn/android/tv/Placeholders.kt`

**Interfaces:**
- Consumes: Task 1 の `ServerAddressValidator` / `ProfileRepository`
- Produces: `@Composable fun ProfileEditScreen(profiles: ProfileRepository, editingUuid: String?, onDone: () -> Unit)`

- [ ] **Step 1: ProfileEditScreen を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/ProfileEditScreen.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 接続先の追加・編集。
 *
 * 入力項目はサーバ URL と表示名の2つだけ。ユーザー名とパスワードは
 * 認証フォームの構造から鍵が決まるため接続前に書き込めず、初回接続時に
 * 既存の認証ダイアログで入力・保存する（仕様書 9.1）。
 */
@Composable
fun ProfileEditScreen(
    profiles: ProfileRepository,
    editingUuid: String?,
    onDone: () -> Unit,
) {
    val existing = remember(editingUuid) {
        editingUuid?.let { uuid -> profiles.list().firstOrNull { it.uuid == uuid } }
    }

    var address by remember { mutableStateOf(existing?.serverAddress ?: "") }
    var displayName by remember { mutableStateOf(existing?.name ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val addressFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            if (editingUuid == null) "接続先を追加" else "接続先を編集",
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = address,
            onValueChange = { address = it; error = null },
            label = { androidx.compose.material3.Text("サーバ URL（例: vpn.example.com）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(addressFocus),
        )

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { androidx.compose.material3.Text("表示名（任意）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Text(
            "保存後、この接続先に初めて接続するときにユーザー名とパスワードの入力を求められます。" +
                "そこで「パスワードを保存」をチェックすると、以降は自動で再接続できます。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                when (val result = ServerAddressValidator.validate(address)) {
                    AddressValidation.Valid -> {
                        val normalized = ServerAddressValidator.normalize(address)
                        if (editingUuid == null) {
                            profiles.create(normalized, displayName)
                        } else {
                            profiles.rename(editingUuid, displayName)
                            profiles.ensureBatchMode(editingUuid)
                        }
                        onDone()
                    }

                    is AddressValidation.Invalid -> error = messageFor(result.reason)
                }
            }) {
                Text("保存")
            }
            Button(onClick = onDone) {
                Text("キャンセル")
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { addressFocus.requestFocus() } }
}

private fun messageFor(reason: InvalidReason): String = when (reason) {
    InvalidReason.Empty -> "サーバ URL を入力してください"
    InvalidReason.ContainsWhitespace -> "サーバ URL に空白を含めないでください"
    InvalidReason.NoHost -> "ホスト名が入力されていません"
    InvalidReason.BadScheme -> "http:// は使えません。https:// かホスト名のみを入力してください"
}
```

編集時にサーバアドレスを変更すると既存の保存済み資格情報の鍵が合わなくなる可能性があるため、編集では表示名のみを反映し、アドレス欄は表示のみとする運用にしている（`rename` だけを呼ぶ）。

- [ ] **Step 2: Placeholders.kt から ProfileEditScreen の仮実装を削除する**

- [ ] **Step 3: ビルドしてインストールする**

```bash
./gradlew assembleDebug 2>&1 | tail -30
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`OutlinedTextField` は tv-material に無いため `androidx.compose.material3` のものを使っている。material3 の依存が解決できない場合は `app/build.gradle` の `dependencies` に追加する。

```groovy
    implementation 'androidx.compose.material3:material3'
```

- [ ] **Step 4: リモコンで接続先を追加できることを確認する**

1. 「＋ 接続先を追加」から画面に入り、サーバ URL 欄にフォーカスが当たっている
2. 決定キーで Fire TV のソフトキーボードが開き、文字入力できる
3. 「保存」で一覧に戻り、追加した接続先が表示される
4. 空欄で保存するとエラーメッセージが出る
5. `http://` を入力するとスキームのエラーが出る

- [ ] **Step 5: batch_mode が設定されたことを確認する**

```bash
adb shell run-as net.openconnect_vpn.android.firetv \
    cat shared_prefs/profile-<追加したUUID>.xml | grep batch_mode
```

Expected: `<string name="batch_mode">enabled</string>`

これが無いと自動フェイルオーバーが止まるため、必ず確認する。

- [ ] **Step 6: 初回接続で認証ダイアログが D-pad 操作できることを確認する**

一覧から追加した接続先を選んで接続する。既存の `AuthFormHandler` のダイアログが出るので、リモコンでユーザー名・パスワードを入力し「パスワードを保存」をチェックして接続できることを確認する。

**ここが通らない場合は仕様書 9.1 の前提が崩れるため、UI の作業を止めて設計を見直す。**

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/
git commit -m "feat(tv): 接続先の追加・編集フォーム

入力はサーバ URL と表示名の2つだけ。資格情報は初回接続時の
既存認証ダイアログで保存させる旨を画面上で案内する。"
```

---

### Task 7: GroupEditScreen（グループの作成・並び替え・自動切替 ON/OFF）

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/GroupEditScreen.kt`
- Modify: `app/src/main/java/net/openconnect_vpn/android/tv/Placeholders.kt`

**Interfaces:**
- Consumes: Task 2 の `GroupEditor`、Task 1 の `ProfileRepository`、計画1 の `GroupStore` / `FailoverGroup`
- Produces: `@Composable fun GroupEditScreen(profiles: ProfileRepository, groupStore: GroupStore, editingGroupId: String?, onDone: () -> Unit)`

- [ ] **Step 1: GroupEditScreen を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/GroupEditScreen.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.GroupStore
import java.util.UUID

/**
 * フェイルオーバーグループの作成・編集。
 * メンバーの並び順がそのまま優先順位になるため、上下移動を明示的に操作させる。
 */
@Composable
fun GroupEditScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    editingGroupId: String?,
    onDone: () -> Unit,
) {
    val allProfiles = remember { profiles.list() }
    val knownUuids = remember(allProfiles) { allProfiles.map { it.uuid }.toSet() }
    val storedGroups = remember { groupStore.loadGroups(knownUuids) }

    var group by remember {
        mutableStateOf(
            storedGroups.firstOrNull { it.id == editingGroupId }
                ?: FailoverGroup(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    memberUuids = emptyList(),
                    autoFailoverEnabled = true,
                    config = FailoverConfig(),
                ),
        )
    }

    val nameFocus = remember { FocusRequester() }

    fun nameOf(uuid: String): String =
        allProfiles.firstOrNull { it.uuid == uuid }?.name ?: uuid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (editingGroupId == null) "グループを作成" else "グループを編集",
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = group.name,
            onValueChange = { group = group.copy(name = it) },
            label = { androidx.compose.material3.Text("グループ名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocus),
        )

        Card(onClick = {
            group = GroupEditor.setAutoFailover(group, !group.autoFailoverEnabled)
        }) {
            Text(
                if (group.autoFailoverEnabled) "自動切替: ON" else "自動切替: OFF",
                modifier = Modifier.padding(20.dp),
            )
        }

        Text("候補（上が優先）", style = MaterialTheme.typography.titleSmall)

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(group.memberUuids) { uuid ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(onClick = { group = GroupEditor.moveUp(group, uuid) }) {
                        Text("▲", modifier = Modifier.padding(16.dp))
                    }
                    Card(onClick = { group = GroupEditor.moveDown(group, uuid) }) {
                        Text("▼", modifier = Modifier.padding(16.dp))
                    }
                    Card(onClick = { group = GroupEditor.removeMember(group, uuid) }) {
                        Text("${nameOf(uuid)}  （決定で除外）", modifier = Modifier.padding(16.dp))
                    }
                }
            }

            val candidates = allProfiles.filterNot { it.uuid in group.memberUuids }
            if (candidates.isNotEmpty()) {
                item {
                    Text(
                        "追加できる接続先",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            items(candidates) { profile ->
                Card(onClick = { group = GroupEditor.addMember(group, profile.uuid) }) {
                    Text("＋ ${profile.name}", modifier = Modifier.padding(16.dp))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                if (group.name.isBlank() || group.memberUuids.isEmpty()) return@Button
                val others = storedGroups.filterNot { it.id == group.id }
                groupStore.saveGroups(others + group)
                onDone()
            }) {
                Text("保存")
            }
            Button(onClick = onDone) {
                Text("キャンセル")
            }
            if (editingGroupId != null) {
                Button(onClick = {
                    groupStore.saveGroups(storedGroups.filterNot { it.id == group.id })
                    onDone()
                }) {
                    Text("グループを削除")
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
}
```

グループ名が空、または候補が0件のときは保存を無効にしている。`GroupStore.loadGroups` がメンバー0件のグループを落とす仕様（計画1 Task 6）と整合させるためである。

- [ ] **Step 2: Placeholders.kt から GroupEditScreen の仮実装を削除する**

- [ ] **Step 3: ビルドしてインストールする**

```bash
./gradlew assembleDebug 2>&1 | tail -30
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: リモコンでグループを作れることを確認する**

1. 一覧で「＋ 接続先を追加」以外の場所からグループ作成へ入れることを確認する
   （導線が無い場合は `HomeScreen` に「グループを作成」カードを1枚足す）
2. グループ名を入力し、接続先を2件追加する
3. ▲▼ で並び順が入れ替わる
4. 「自動切替: ON / OFF」が切り替わる
5. 保存して一覧に戻り、グループ行が表示される

- [ ] **Step 5: HomeScreen にグループ作成の導線を足す**

`HomeScreen.kt` のヘッダー行の `Row` に、設定の隣にカードを追加する。

```kotlin
            Card(onClick = { onNavigate(TvScreen.EditGroup(null)) }) {
                Text("グループを作成", modifier = Modifier.padding(16.dp))
            }
```

- [ ] **Step 6: 保存したグループで自動フェイルオーバーが動くことを確認する**

作ったグループの行を決定キーで選び、`FailoverService.connectGroup` が走って先頭候補に接続されることを確認する。計画1 Task 13 のシナリオ1（サーバ停止）を再度実行し、TV UI から作ったグループでも切替が動くことを確認する。

- [ ] **Step 7: コミット**

```bash
git add app/src/main/java/net/openconnect_vpn/android/tv/
git commit -m "feat(tv): グループの作成・並び替え・自動切替 ON/OFF

memberUuids の並び順が優先順位なので上下移動を明示操作にした。
グループ名が空、または候補0件では保存させない。"
```

---

### Task 8: SettingsScreen と既存 UI への導線

**Files:**
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/SettingsScreen.kt`
- Create: `app/src/main/java/net/openconnect_vpn/android/tv/VpnConsent.kt`
- Delete: `app/src/main/java/net/openconnect_vpn/android/tv/Placeholders.kt`

**Interfaces:**
- Consumes: 計画1 の `GroupStore` / `ProbeTarget` / `FailoverService`
- Produces:
  - `@Composable fun SettingsScreen(groupStore: GroupStore, onDone: () -> Unit)`
  - `@Composable fun rememberVpnConsentLauncher(): () -> Unit`

- [ ] **Step 1: VPN 許可取得を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/VpnConsent.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 初回の VPN 許可を取得する。
 *
 * 許可が済んでいれば VpnService.prepare が null を返すようになり、
 * 以降フェイルオーバー層が Activity を経由せず無人で接続できる（仕様書 5.2）。
 */
@Composable
fun rememberVpnConsentLauncher(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* 結果は次回の prepare() が null を返すかで判断する */ }

    return {
        VpnService.prepare(context)?.let { launcher.launch(it) }
    }
}
```

- [ ] **Step 2: SettingsScreen を書く**

`app/src/main/java/net/openconnect_vpn/android/tv/SettingsScreen.kt`:

```kotlin
package net.openconnect_vpn.android.tv

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.MainActivity
import net.openconnect_vpn.android.failover.FailoverService
import net.openconnect_vpn.android.failover.GroupStore
import net.openconnect_vpn.android.failover.ProbeTarget

@Composable
fun SettingsScreen(groupStore: GroupStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val stored = remember { groupStore.loadProbeTarget() }

    var host by remember { mutableStateOf(stored.host) }
    var port by remember { mutableStateOf(stored.port.toString()) }
    var message by remember { mutableStateOf<String?>(null) }

    val requestConsent = rememberVpnConsentLauncher()
    val hostFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)

        Text(
            "疎通確認の宛先。VPN 経由でここに TCP 接続できるかで、接続先が生きているかを判定します。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it; message = null },
            label = { androidx.compose.material3.Text("宛先ホスト") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(hostFocus),
        )

        OutlinedTextField(
            value = port,
            onValueChange = { port = it; message = null },
            label = { androidx.compose.material3.Text("宛先ポート") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Button(onClick = {
            val portValue = port.toIntOrNull()
            when {
                host.isBlank() -> message = "宛先ホストを入力してください"
                portValue == null || portValue !in 1..65535 -> message = "ポートは 1〜65535 で指定してください"
                else -> {
                    groupStore.saveProbeTarget(ProbeTarget(host.trim(), portValue))
                    message = "保存しました。次回のサービス起動から反映されます"
                }
            }
        }) {
            Text("疎通確認の宛先を保存")
        }

        Card(onClick = { requestConsent() }) {
            Text("VPN の許可を取得する", modifier = Modifier.padding(20.dp))
        }

        Card(onClick = { FailoverService.disconnect(context) }) {
            Text("VPN を切断する", modifier = Modifier.padding(20.dp))
        }

        Card(onClick = {
            context.startActivity(Intent(context, MainActivity::class.java))
        }) {
            Column(Modifier.padding(20.dp)) {
                Text("詳細設定とログ（従来の画面）")
                Text(
                    "接続できないときの切り分けに使います",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(onClick = onDone) {
            Text("戻る")
        }
    }

    LaunchedEffect(Unit) { runCatching { hostFocus.requestFocus() } }
}
```

この画面の `message` はエラーと成功通知の両方を兼ねるため、変数名を `error` ではなく
`message` にしている。

- [ ] **Step 3: Placeholders.kt を削除する**

```bash
git rm app/src/main/java/net/openconnect_vpn/android/tv/Placeholders.kt
```

すべての画面が本実装に置き換わったため不要になる。

- [ ] **Step 4: ビルドしてインストールする**

```bash
./gradlew assembleDebug 2>&1 | tail -30
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: 設定画面をリモコンで操作できることを確認する**

1. 宛先ホスト・ポートを変更して保存できる
2. 「VPN の許可を取得する」でシステムの許可ダイアログが出て、D-pad で承認できる
3. 「VPN を切断する」で切断される
4. 「詳細設定とログ（従来の画面）」から既存のスマホ UI に入れる

- [ ] **Step 6: 全テストを流す**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: 計画1 の 48 tests + 本計画の 35 tests = 83 tests PASS

- [ ] **Step 7: コミット**

```bash
git add -A
git commit -m "feat(tv): 設定画面と VPN 許可取得、既存 UI への導線

疎通確認の宛先を変更でき、初回の VPN 許可をここで取得できる。
既存のスマホ UI は切り分け用として設定画面から到達可能にした。"
```

---

### Task 9: D-pad 操作の通し確認と記録

**Files:**
- Modify: `docs/MANUAL-TEST.md`

**Interfaces:**
- Consumes: Task 1〜8 の全成果物
- Produces: `docs/MANUAL-TEST.md` に追記された D-pad チェックリストの結果

- [ ] **Step 1: リモコンのみで一連の操作を通す**

**マウス・キーボード・ADB を一切使わず**、Fire TV のリモコンだけで次を最後まで通す。途中で操作不能になった箇所が、そのまま修正すべき不具合である。

1. ホーム画面からアプリを起動する
2. 「接続先を追加」で1件目を登録する
3. 初回接続で認証情報を入力し「パスワードを保存」をチェックして接続する
4. 切断する
5. 同様に2件目を登録して接続・切断する
6. 「グループを作成」で2件を候補にしたグループを作り、優先順位を並べ替える
7. 自動切替を ON にして保存する
8. グループを選んで接続する
9. 設定画面で疎通確認の宛先を確認する
10. 接続先を1件削除する（確認ダイアログが出ること）
11. アプリを終了して再起動し、登録内容が保持されていることを確認する

- [ ] **Step 2: D-pad 到達性を機械的に計測する（主観に頼らない）**

仕様書 9.4 の客観基準を全画面に適用する。既存アプリの実測では、プロファイルの行
（`vpn_list_item_left`）と各行の編集ボタン（`quickedit_settings`）が
`clickable="true" focusable="false"` になっており、これがリモコンで接続先を
切り替えられない直接の原因だった（仕様書 9.3）。同じ失敗を機械的に検出する。

各画面を開いた状態で次を実行する。

```bash
ADB=/c/Users/htek6/AppData/Local/Android/Sdk/platform-tools/adb.exe
"$ADB" shell uiautomator dump /sdcard/ui.xml
MSYS_NO_PATHCONV=1 "$ADB" shell "cat /sdcard/ui.xml" | tr -d '\r' > ui.xml
python3 - ui.xml <<'PY'
import sys, xml.etree.ElementTree as ET
bad = [n for n in ET.parse(sys.argv[1]).getroot().iter('node')
       if n.get('clickable') == 'true' and n.get('focusable') != 'true']
for n in bad:
    print("UNREACHABLE", n.get('resource-id') or n.get('class'), repr(n.get('text')))
print("violations:", len(bad))
PY
```

**合格条件: すべての画面で `violations: 0`。**

数の比較では判定しない。合計数の一致は偶然成立しうるため、要素ごとの述語
（`clickable="true"` かつ `focusable="false"` が存在しないこと）で判定する。
違反が出たら、印字された `resource-id` の要素を修正する。

対象画面: ホーム一覧 / 接続先の追加 / 接続先の編集 / グループ作成 / グループ編集 /
設定 / 削除確認ダイアログ。

- [ ] **Step 2b: フォーカスの不具合を洗う**

各画面で次を確認する。フォーカスが消える画面があれば、その画面の `LaunchedEffect` と `focusRequester` を見直す。

- 画面に入った瞬間、必ずどこかにフォーカスがある
- 上下左右キーで全ての操作要素に到達できる
- 戻るキーで前の画面に戻れる（または「戻る」ボタンに到達できる）
- ダイアログ表示中、フォーカスがダイアログ内にある
- ダイアログの初期フォーカスが「キャンセル」側にある

既存コアが出す2つのダイアログも D-pad で操作できることを確認する。どちらも既存実装を
そのまま使う方針のため、ここが唯一の担保になる。

- 初回接続時の認証ダイアログ（`AuthFormHandler`）でユーザー名・パスワードを入力できる
- サーバ証明書の警告ダイアログ（`CertWarningDialog`）で承認・拒否を選べる
  （自己署名証明書のサーバを1件登録すれば再現できる）

- [ ] **Step 3: 実機で自動フェイルオーバーを通しで確認する**

計画1 Task 13 のシナリオ1・2 を、**TV UI から作ったグループ**で再実行する。

- サーバ停止 → 即座に次候補へ切り替わる
- パケット drop → 約90秒後に次候補へ切り替わる

- [ ] **Step 4: 長時間放置テストを行う（リスク RK4）**

Fire TV をスタンバイに入れて数時間放置し、復帰後に接続が維持されているか、または自動で復帰するかを確認する。仕様書のリスク RK4 の検証である。

```bash
# 放置後に確認
adb connect <FireTVのIP>:5555
adb shell dumpsys activity services net.openconnect_vpn.android.firetv | head -20
```

`FailoverService` が生きているかを確認する。killed されている場合は `START_STICKY` による復帰が効いているかをログで確認する。

- [ ] **Step 5: 結果を記録する**

`docs/MANUAL-TEST.md` に、Step 1〜4 の実施日時と結果、操作不能だった箇所とその修正内容、長時間放置テストの結果を追記する。

- [ ] **Step 6: コミット**

```bash
git add docs/MANUAL-TEST.md
git commit -m "test: リモコンのみでの通し操作と長時間放置を検証

リモコンだけで接続先の登録からグループ作成、自動切替まで通ることを確認。
スタンバイ復帰後のサービス継続性（リスク RK4）も記録した。"
```

---

## 計画2 完了条件

- [ ] `./gradlew :app:testDebugUnitTest` が 83 tests 全て PASS
- [ ] `./gradlew assembleDebug` が成功する
- [ ] **リモコンだけで**接続先の追加・削除・グループ作成・並び替え・自動切替 ON/OFF・接続・切断ができる
- [ ] どの画面でもフォーカスが消えない
- [ ] TV UI から作ったグループで実機の自動フェイルオーバーが2シナリオとも動く
- [ ] 既存のスマホ UI が設定画面から到達できる
- [ ] `docs/MANUAL-TEST.md` に D-pad チェックリストと長時間放置テストの結果が記録されている
