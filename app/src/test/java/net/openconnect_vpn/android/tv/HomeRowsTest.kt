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
    fun `接続中のグループには Connecting バッジが付き 候補名は出るが接続済みではない`() {
        // Connecting はダイヤル中でトンネル未確立。memberName はどの候補へ
        // 繋ごうとしているかを示す有用な情報として出すが、これは「接続済み」を
        // 意味しない。接続済みかどうかは badge (Connecting) 側で示す。
        val state = FailoverState.Connecting("g1", candidateIndex = 0, startedAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Connecting, row.badge)
        assertEquals("sv1", row.memberName)
    }

    @Test
    fun `健全なグループには Connected バッジと現在の候補名が付く`() {
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Connected, row.badge)
        assertEquals("sv2", row.memberName)
    }

    @Test
    fun `疎通確認中は Verifying バッジと候補名が付く`() {
        // Verifying はすでにトンネルが張れている状態なので、memberName が
        // 非 null であることを直接確認する。
        val state = FailoverState.Verifying("g1", candidateIndex = 0, connectedAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Verifying, row.badge)
        assertEquals("sv1", row.memberName)
    }

    @Test
    fun `枯渇中は Retrying バッジで候補名は出ない`() {
        // Exhausted は全滅してバックオフ待ちの状態で、対象候補が定まっていない
        // ため memberName は null であることを直接確認する。
        val state = FailoverState.Exhausted("g1", attempt = 0, retryAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Retrying, row.badge)
        assertNull(row.memberName)
    }

    @Test
    fun `切替中は Connecting バッジで候補名は表示しない`() {
        // FailingOver.failedIndex は「見限られつつある」候補であり、まだ次の
        // 候補への接続は成立していないため、memberName は null のままにする。
        val state = FailoverState.FailingOver(
            groupId = "g1",
            failedIndex = 0,
            awaitingUuid = "uuid-a",
            startedAtMs = 0L,
        )
        val rows = HomeRows.build(listOf(group), profiles, state)

        val row = rows[0] as HomeRow.GroupRow
        assertEquals(ConnectionBadge.Connecting, row.badge)
        assertNull(row.memberName)
    }

    @Test
    fun `別のグループが Healthy でも当該グループのバッジは None`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.Healthy("g2", candidateIndex = 0, consecutiveFailures = 0, lastProbeAtMs = 0L)

        val rows = HomeRows.build(listOf(group, other), profiles, state)

        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
        assertEquals(ConnectionBadge.Connected, (rows[1] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `別のグループが Connecting でも当該グループのバッジは None`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.Connecting("g2", candidateIndex = 0, startedAtMs = 0L)

        val rows = HomeRows.build(listOf(group, other), profiles, state)

        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
        assertEquals(ConnectionBadge.Connecting, (rows[1] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `別のグループが Verifying でも当該グループのバッジは None`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.Verifying("g2", candidateIndex = 0, connectedAtMs = 0L)

        val rows = HomeRows.build(listOf(group, other), profiles, state)

        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
        assertEquals(ConnectionBadge.Verifying, (rows[1] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `別のグループが FailingOver でも当該グループのバッジは None`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.FailingOver(
            groupId = "g2",
            failedIndex = 0,
            awaitingUuid = "uuid-a",
            startedAtMs = 0L,
        )

        val rows = HomeRows.build(listOf(group, other), profiles, state)

        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
        assertEquals(ConnectionBadge.Connecting, (rows[1] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `別のグループが Exhausted でも当該グループのバッジは None`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.Exhausted("g2", attempt = 0, retryAtMs = 0L)

        val rows = HomeRows.build(listOf(group, other), profiles, state)

        assertEquals(ConnectionBadge.None, (rows[0] as HomeRow.GroupRow).badge)
        assertEquals(ConnectionBadge.Retrying, (rows[1] as HomeRow.GroupRow).badge)
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

    @Test
    fun `どのグループにも属さない接続先の削除警告は null`() {
        val warning = HomeRows.groupDeletionWarning("uuid-z", listOf(group))
        assertNull(warning)
    }

    @Test
    fun `グループに属する接続先の削除警告はグループ名を含む`() {
        val warning = HomeRows.groupDeletionWarning("uuid-a", listOf(group))
        assertTrue(warning != null && warning.contains("自宅優先"))
    }

    @Test
    fun `最後の1件を削除するとグループごと消えることを警告する`() {
        val soloGroup = group.copy(id = "g2", name = "単独", memberUuids = listOf("uuid-a"))
        val warning = HomeRows.groupDeletionWarning("uuid-a", listOf(soloGroup))
        assertTrue(warning != null && warning.contains("グループごと消えます"))
    }

    @Test
    fun `複数グループに属していればすべて警告に含まれる`() {
        val other = group.copy(id = "g2", name = "予備")
        val warning = HomeRows.groupDeletionWarning("uuid-a", listOf(group, other))
        assertTrue(warning != null && warning.contains("自宅優先") && warning.contains("予備"))
    }

    // --- 裁定65（指摘8）: withTrustworthyMemberNames ---

    @Test
    fun `エンジンのグループ定義が UI と同じなら memberName はそのまま`() {
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val reconciled = HomeRows.withTrustworthyMemberNames(rows, listOf(group), group)

        assertEquals("sv2", (reconciled[0] as HomeRow.GroupRow).memberName)
    }

    @Test
    fun `エンジンのグループ定義が UI とずれていれば memberName を null にする`() {
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        // UI 側では並びが変わっている（例: グループ編集で候補順を入れ替えた）が、
        // エンジン側（FailoverService の onCreate 時点のスナップショット）はまだ古いまま。
        val engineGroup = group.copy(memberUuids = listOf("uuid-b", "uuid-a"))
        val reconciled = HomeRows.withTrustworthyMemberNames(rows, listOf(group), engineGroup)

        assertNull((reconciled[0] as HomeRow.GroupRow).memberName)
    }

    @Test
    fun `ずれの補正は対象グループの行だけに適用され他の行は影響しない`() {
        val other = group.copy(id = "g2", name = "予備")
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group, other), profiles, state)

        val engineGroup = group.copy(memberUuids = listOf("uuid-b", "uuid-a"))
        val reconciled = HomeRows.withTrustworthyMemberNames(rows, listOf(group, other), engineGroup)

        assertNull((reconciled[0] as HomeRow.GroupRow).memberName)
        // g2（他のグループ）はそもそも対象でない（badge が None）ので、
        // 元から memberName は null。影響を受けていないことをバッジで確認する。
        assertEquals(ConnectionBadge.None, (reconciled[1] as HomeRow.GroupRow).badge)
    }

    @Test
    fun `engineGroup が null なら何も補正しない`() {
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val reconciled = HomeRows.withTrustworthyMemberNames(rows, listOf(group), null)

        assertEquals(rows, reconciled)
    }

    @Test
    fun `engineGroup に対応する UI 側グループが見つからなければ何も補正しない`() {
        val state = FailoverState.Healthy("g1", candidateIndex = 1, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val rows = HomeRows.build(listOf(group), profiles, state)

        val vanishedEngineGroup = group.copy(id = "g-deleted")
        val reconciled = HomeRows.withTrustworthyMemberNames(rows, listOf(group), vanishedEngineGroup)

        assertEquals(rows, reconciled)
    }
}
