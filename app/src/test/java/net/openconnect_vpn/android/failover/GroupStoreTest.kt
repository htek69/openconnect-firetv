package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
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
}
