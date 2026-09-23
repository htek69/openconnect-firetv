package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `切替中は Connecting バッジで候補名は表示しない`() {
        // FailingOver.failedIndex は「見限られつつある」候補であり、まだ次の
        // 候補への接続は成立していないため、activeMemberName は null のままにする。
        val state = FailoverState.FailingOver(
            groupId = "g1",
            failedIndex = 0,
            awaitingUuid = "uuid-a",
            startedAtMs = 0L,
        )
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Connecting, row.badge)
        assertNull(row.activeMemberName)
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
