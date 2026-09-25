package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStoreTest {

    private val allKnown = setOf("uuid-a", "uuid-b", "uuid-c")

    private fun group(vararg members: String) = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = members.toList(),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Test
    fun `保存したグループを読み戻せる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-a", "uuid-b")))

        val loaded = store.loadGroups(allKnown)

        assertEquals(1, loaded.size)
        assertEquals("自宅優先", loaded[0].name)
        assertEquals(listOf("uuid-a", "uuid-b"), loaded[0].memberUuids)
        assertTrue(loaded[0].autoFailoverEnabled)
    }

    @Test
    fun `並び順が優先順位として保持される`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-c", "uuid-a", "uuid-b")))

        val loaded = store.loadGroups(allKnown)

        assertEquals(listOf("uuid-c", "uuid-a", "uuid-b"), loaded[0].memberUuids)
    }

    @Test
    fun `削除済みプロファイルの UUID は読み込み時に除去される`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-a", "uuid-deleted", "uuid-b")))

        val loaded = store.loadGroups(knownProfileUuids = setOf("uuid-a", "uuid-b"))

        assertEquals(listOf("uuid-a", "uuid-b"), loaded[0].memberUuids)
    }

    @Test
    fun `メンバーが全て削除されたグループ自体も除去される`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(listOf(group("uuid-gone-1", "uuid-gone-2")))

        val loaded = store.loadGroups(knownProfileUuids = setOf("uuid-a"))

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `未保存の状態では空リストを返す`() {
        val store = GroupStore(InMemoryKeyValueStore())
        assertTrue(store.loadGroups(allKnown).isEmpty())
    }

    @Test
    fun `壊れた JSON は空リストとして扱う`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("failover_groups_v1", "{ this is not json")

        val loaded = GroupStore(kv).loadGroups(allKnown)

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `未保存のプローブ宛先は既定値を返す`() {
        val store = GroupStore(InMemoryKeyValueStore())
        val target = store.loadProbeTarget()
        assertEquals("1.1.1.1", target.host)
        assertEquals(443, target.port)
    }

    @Test
    fun `プローブ宛先を保存して読み戻せる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveProbeTarget(ProbeTarget("9.9.9.9", 853))

        val target = store.loadProbeTarget()

        assertEquals("9.9.9.9", target.host)
        assertEquals(853, target.port)
    }

    @Test
    fun `未保存のアクティブグループIDは null を返す`() {
        val store = GroupStore(InMemoryKeyValueStore())
        assertNull(store.loadActiveGroupId())
    }

    @Test
    fun `アクティブグループIDを保存して読み戻せる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveActiveGroupId("g1")

        assertEquals("g1", store.loadActiveGroupId())
    }

    @Test
    fun `アクティブグループIDに null を保存すると消える`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveActiveGroupId("g1")
        store.saveActiveGroupId(null)

        assertNull(store.loadActiveGroupId())
    }

    // --- 裁定74: プローブ間隔・失敗閾値のグローバル設定 ---

    @Test
    fun `未保存のプローブ設定は既定値を返す`() {
        val store = GroupStore(InMemoryKeyValueStore())

        val schedule = store.loadProbeSchedule()

        assertEquals(FailoverConfig().probeIntervalSec, schedule.probeIntervalSec)
        assertEquals(FailoverConfig().failureThreshold, schedule.failureThreshold)
    }

    @Test
    fun `プローブ設定を保存して読み戻せる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveProbeSchedule(ProbeSchedule(probeIntervalSec = 90, failureThreshold = 5))

        val schedule = store.loadProbeSchedule()

        assertEquals(90, schedule.probeIntervalSec)
        assertEquals(5, schedule.failureThreshold)
    }

    @Test
    fun `壊れたプローブ設定のJSONは既定値として扱う`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("failover_probe_schedule_v1", "{ this is not json")

        val schedule = GroupStore(kv).loadProbeSchedule()

        assertEquals(FailoverConfig().probeIntervalSec, schedule.probeIntervalSec)
        assertEquals(FailoverConfig().failureThreshold, schedule.failureThreshold)
    }

    @Test
    fun `保存済みグループの config はグローバル設定で上書きされて読み込まれる`() {
        val store = GroupStore(InMemoryKeyValueStore())
        store.saveGroups(
            listOf(group("uuid-a").copy(config = FailoverConfig(probeIntervalSec = 20, failureThreshold = 1))),
        )
        store.saveProbeSchedule(ProbeSchedule(probeIntervalSec = 60, failureThreshold = 4))

        val loaded = store.loadGroups(allKnown)

        assertEquals(60, loaded[0].config.probeIntervalSec)
        assertEquals(4, loaded[0].config.failureThreshold)
    }

    @Test
    fun `グローバル設定の上書きはプローブ間隔と失敗閾値以外のフィールドを変えない`() {
        val store = GroupStore(InMemoryKeyValueStore())
        val original = FailoverConfig(probeTimeoutMs = 1_234, graceAfterConnectSec = 7, connectTimeoutSec = 12)
        store.saveGroups(listOf(group("uuid-a").copy(config = original)))
        store.saveProbeSchedule(ProbeSchedule(probeIntervalSec = 60, failureThreshold = 4))

        val config = store.loadGroups(allKnown).single().config

        assertEquals(1_234, config.probeTimeoutMs)
        assertEquals(7, config.graceAfterConnectSec)
        assertEquals(12, config.connectTimeoutSec)
    }

    // --- 裁定86（H1）: 接続先の作成・削除を既存の再読込トリガに載せる ---

    @Test
    fun `グループ定義とプローブ設定のキーは再読込トリガである`() {
        val store = GroupStore(InMemoryKeyValueStore())

        assertTrue(store.isReloadTriggerKey("failover_groups_v1"))
        assertTrue(store.isReloadTriggerKey("failover_probe_target_v1"))
        assertTrue(store.isReloadTriggerKey("failover_probe_schedule_v1"))
    }

    @Test
    fun `アクティブグループIDと無関係なキーは再読込トリガではない`() {
        val store = GroupStore(InMemoryKeyValueStore())

        // どのグループへ繋ぐかの記録はグループ定義そのものではない（裁定48/72）。
        assertEquals(false, store.isReloadTriggerKey("failover_active_group_id_v1"))
        assertEquals(false, store.isReloadTriggerKey("something_else"))
        assertEquals(false, store.isReloadTriggerKey(null))
    }

    @Test
    fun `bumpProfileGeneration が書くキーは再読込トリガである`() {
        val kv = InMemoryKeyValueStore()
        val store = GroupStore(kv)

        store.bumpProfileGeneration()

        // ProfileRepository の作成・削除がこのキーを進めることで、
        // FailoverService の既存のリスナが groups を読み直す。ここが
        // トリガに入っていないと、接続先を削除してもエンジンには永久に
        // 届かない（H1）。
        val key = kv.writtenKeys.single()
        assertTrue(store.isReloadTriggerKey(key))
    }

    @Test
    fun `bumpProfileGeneration は呼ぶたびに違う値を書く`() {
        val kv = InMemoryKeyValueStore()
        val store = GroupStore(kv)

        store.bumpProfileGeneration()
        val key = kv.writtenKeys.single()
        val first = kv.getString(key)
        store.bumpProfileGeneration()
        val second = kv.getString(key)

        // SharedPreferences のリスナは値が変わらないと発火しないので、
        // 同じ値を書き直すだけでは再読込が起きない。
        assertEquals("1", first)
        assertEquals("2", second)
    }

    @Test
    fun `壊れた世代の値からでも必ず別の値へ進む`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("failover_profile_generation_v1", "not a number")
        val store = GroupStore(kv)

        store.bumpProfileGeneration()

        assertEquals("1", kv.getString("failover_profile_generation_v1"))
    }

    @Test
    fun `裁定74 グローバル設定を保存した後に作られた新しいグループにも適用される`() {
        val store = GroupStore(InMemoryKeyValueStore())

        // 設定画面での操作を模す: 先に既定値ではないグローバル設定を保存する。
        store.saveProbeSchedule(ProbeSchedule(probeIntervalSec = 90, failureThreshold = 5))

        // その後に新しいグループを作る。GroupEditScreen は常にコンパイル時既定値
        // FailoverConfig()（30秒・3回）でグループを作るので、config には
        // グローバル設定と異なる値が入っている。
        store.saveGroups(listOf(group("uuid-a").copy(config = FailoverConfig())))

        val loaded = store.loadGroups(allKnown)

        assertEquals(90, loaded[0].config.probeIntervalSec)
        assertEquals(5, loaded[0].config.failureThreshold)
    }
}
