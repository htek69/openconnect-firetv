package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private val firstLoginGroup = FirstLoginGroup("g1", "自宅優先")

    private fun profileRow(needsFirstLogin: Boolean, group: FirstLoginGroup?) =
        HomeRow.ProfileRow(
            uuid = "uuid-b",
            name = "sv2",
            serverAddress = "sv2.example.com",
            needsFirstLogin = needsFirstLogin,
            firstLoginGroup = group,
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

    // --- 裁定84（fix8）: HomeRow.focusKey() ---

    @Test
    fun `focusKey はグループ行と接続先行と追加行それぞれで異なる`() {
        val rows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)
        val keys = rows.mapNotNull { it.focusKey() }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `SectionHeader の focusKey は null`() {
        val rows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)
        val header = rows.first { it is HomeRow.SectionHeader }

        assertNull(header.focusKey())
    }

    @Test
    fun `同じ行ならバッジが変わっても focusKey は変わらない`() {
        val idleRows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)
        val connectedState = FailoverState.Healthy("g1", candidateIndex = 0, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val connectedRows = HomeRows.build(listOf(group), profiles, connectedState)

        assertEquals(
            (idleRows[0] as HomeRow.GroupRow).focusKey(),
            (connectedRows[0] as HomeRow.GroupRow).focusKey(),
        )
    }

    @Test
    fun `AddProfile の focusKey は build の呼び出しをまたいで一致する`() {
        // 裁定86（L5）: 以前は `HomeRow.AddProfile.focusKey()` を2回呼んで比べる
        // だけで、`data object` に対する純関数なので定義上失敗しえなかった。
        // 押さえたい性質は「行の再生成（HomeScreen の reloadToken++ や
        // failoverState の変化で build が呼び直される）をまたいでキーが安定して
        // いること」なので、実際に2回 build して取り出したものを比べる
        // （FocusRequester のキャッシュがこの安定性に依存している: 裁定84 fix8）。
        val first = HomeRows.build(listOf(group), profiles, FailoverState.Idle)
        val connectedState = FailoverState.Healthy("g1", candidateIndex = 0, consecutiveFailures = 0, lastProbeAtMs = 0L)
        val second = HomeRows.build(listOf(group), emptyList(), connectedState)

        val firstKey = first.filterIsInstance<HomeRow.AddProfile>().single().focusKey()
        val secondKey = second.filterIsInstance<HomeRow.AddProfile>().single().focusKey()

        assertNotNull(firstKey)
        assertEquals(firstKey, secondKey)
    }

    // --- 裁定84（fix8）: HomeRows.targetKeyAfterConfirmedDelete ---

    @Test
    fun `一覧の途中を削除すると詰めたあとに同じ位置へ来た行へ戻す`() {
        // 削除前: [a, b, c, add] から b（index1）を削除 → 削除後 [a, c, add]。
        // index1 に来るのは c。
        val before = listOf("a", "b", "c", "add")
        val after = listOf("a", "c", "add")

        val target = HomeRows.targetKeyAfterConfirmedDelete(before, "b", after)

        assertEquals("c", target)
    }

    @Test
    fun `末尾の行を削除すると削除後の一覧の末尾へ丸める`() {
        // 削除前: [a, b, add] から add の直前の b（index1、一覧の実質末尾）を削除
        // → 削除後 [a, add]。index1 は配列外になるので末尾（add）へ丸める。
        val before = listOf("a", "b", "add")
        val after = listOf("a", "add")

        val target = HomeRows.targetKeyAfterConfirmedDelete(before, "b", after)

        assertEquals("add", target)
    }

    @Test
    fun `唯一の接続先を削除すると残るのは追加行だけなのでそこへ戻す`() {
        val before = listOf("profile:only", "add")
        val after = listOf("add")

        val target = HomeRows.targetKeyAfterConfirmedDelete(before, "profile:only", after)

        assertEquals("add", target)
    }

    @Test
    fun `先頭を削除すると詰めたあとの先頭へ戻す`() {
        val before = listOf("a", "b", "c", "add")
        val after = listOf("b", "c", "add")

        val target = HomeRows.targetKeyAfterConfirmedDelete(before, "a", after)

        assertEquals("b", target)
    }

    @Test
    fun `削除前の一覧に対象キーが見当たらない防御的分岐では先頭に丸める`() {
        val before = listOf("a", "b", "add")
        val after = listOf("a", "b", "add")

        val target = HomeRows.targetKeyAfterConfirmedDelete(before, "存在しないキー", after)

        assertEquals("a", target)
    }

    @Test
    fun `削除後の一覧が空なら戻す先が無いので null`() {
        val target = HomeRows.targetKeyAfterConfirmedDelete(listOf("a"), "a", emptyList())

        assertNull(target)
    }

    @Test
    fun `初回ログインが必要な接続先の注記を文言ごと固定する`() {
        // 状態（飛ばされている）だけでなく、解き方（この行の操作で接続する）まで
        // 書いてあることを固定する。どちらかが消えたら落ちる。
        assertEquals(
            "初回ログインが必要 / 自動切替では選ばれません。" +
                "右（→）の「初回ログイン」で接続するとログインできます",
            HomeRows.firstLoginNotice(profileRow(needsFirstLogin = true, group = firstLoginGroup)),
        )
        assertNull(HomeRows.firstLoginNotice(profileRow(needsFirstLogin = false, group = firstLoginGroup)))
    }

    @Test
    fun `グループに属さない接続先には注記も操作も出さない`() {
        // 自動切替の対象でないので飛ばされることもなく、初回ログインを急ぐ理由が
        // 無い。意味の無い警告を出さない（操作もその接続先には無い）。
        val row = profileRow(needsFirstLogin = true, group = null)

        assertNull(HomeRows.firstLoginNotice(row))
        assertNull(HomeRows.firstLoginActionLabel(row))
        assertNull(HomeRows.firstLoginConfirmation(row))
    }

    @Test
    fun `初回ログインの操作のラベルを文言ごと固定する`() {
        assertEquals(
            "初回ログイン",
            HomeRows.firstLoginActionLabel(profileRow(needsFirstLogin = true, group = firstLoginGroup)),
        )
        assertNull(
            HomeRows.firstLoginActionLabel(profileRow(needsFirstLogin = false, group = firstLoginGroup)),
        )
    }

    @Test
    fun `初回ログインの確認は接続が切れることと対象グループを明示する`() {
        val message = HomeRows.firstLoginConfirmation(
            profileRow(needsFirstLogin = true, group = firstLoginGroup),
        )

        assertEquals(
            "「sv2」で初回ログインを行いますか？\n\n" +
                "いまの VPN 接続を切って、グループ「自宅優先」の「sv2」へ繋ぎ直します。" +
                "認証画面が出たらユーザー名とパスワードを入力してください" +
                "（「パスワードを保存」にチェックを入れると、次回からは自動で接続できます）。",
            message,
        )
        assertNull(
            HomeRows.firstLoginConfirmation(profileRow(needsFirstLogin = false, group = firstLoginGroup)),
        )
    }

    @Test
    fun `初回ログインが必要な接続先の行にその印が付く`() {
        val rows = HomeRows.build(
            groups = listOf(group),
            profiles = profiles,
            state = FailoverState.Idle,
            needsFirstLogin = { uuid -> uuid == "uuid-b" },
        )

        val profileRows = rows.filterIsInstance<HomeRow.ProfileRow>()
        assertEquals(listOf(false, true), profileRows.map { it.needsFirstLogin })
    }

    @Test
    fun `判定を渡さなければどの行にも印は付かない`() {
        val rows = HomeRows.build(listOf(group), profiles, FailoverState.Idle)

        assertTrue(rows.filterIsInstance<HomeRow.ProfileRow>().none { it.needsFirstLogin })
        assertTrue(rows.filterIsInstance<HomeRow.ProfileRow>().all { it.firstLoginGroup == null })
    }

    @Test
    fun `初回ログインの経路は属するグループのうち一覧の順で最初のもの`() {
        // 接続先は複数のグループに属しうる。裁定: グループ一覧の順で最初に
        // 見つかったものを使う（決定的であり、選ばせる画面を増やさない）。
        val other = group.copy(id = "g2", name = "予備", memberUuids = listOf("uuid-b"))

        val rows = HomeRows.build(
            groups = listOf(other, group),
            profiles = profiles,
            state = FailoverState.Idle,
            needsFirstLogin = { true },
        )

        val row = rows.filterIsInstance<HomeRow.ProfileRow>().first { it.uuid == "uuid-b" }
        assertEquals(FirstLoginGroup("g2", "予備"), row.firstLoginGroup)
    }

    @Test
    fun `初回ログインを終えている接続先には経路を持たせない`() {
        val rows = HomeRows.build(
            groups = listOf(group),
            profiles = profiles,
            state = FailoverState.Idle,
            needsFirstLogin = { uuid -> uuid == "uuid-b" },
        )

        val profileRows = rows.filterIsInstance<HomeRow.ProfileRow>()
        assertNull(profileRows.first { it.uuid == "uuid-a" }.firstLoginGroup)
        assertEquals(
            FirstLoginGroup("g1", "自宅優先"),
            profileRows.first { it.uuid == "uuid-b" }.firstLoginGroup,
        )
    }

    @Test
    fun `グループに属さない接続先には経路を持たせない`() {
        val loneProfile = ProfileSummary("uuid-z", "sv9", "sv9.example.com")

        val rows = HomeRows.build(
            groups = listOf(group),
            profiles = profiles + loneProfile,
            state = FailoverState.Idle,
            needsFirstLogin = { true },
        )

        val row = rows.filterIsInstance<HomeRow.ProfileRow>().first { it.uuid == "uuid-z" }
        assertTrue(row.needsFirstLogin)
        assertNull(row.firstLoginGroup)
    }

    @Test
    fun `疎通確認のたびに初回ログインの再判定を起こさない`() {
        // Healthy の lastProbeAtMs は既定30秒ごとに変わる。これが鍵に入っていると、
        // ホームを開いているあいだ30秒ごとに prefs の再走査が走る。
        assertEquals(
            HomeRows.firstLoginRecheckKey(
                FailoverState.Healthy("g1", 0, consecutiveFailures = 0, lastProbeAtMs = 1_000L),
            ),
            HomeRows.firstLoginRecheckKey(
                FailoverState.Healthy("g1", 0, consecutiveFailures = 2, lastProbeAtMs = 999_000L),
            ),
        )
        // 猶予期間中・接続中の時刻も同じ理由で鍵に入れない。
        assertEquals(
            HomeRows.firstLoginRecheckKey(FailoverState.Verifying("g1", 0, connectedAtMs = 1L)),
            HomeRows.firstLoginRecheckKey(
                FailoverState.Verifying("g1", 0, connectedAtMs = 500L, consecutiveFailures = 1),
            ),
        )
        assertEquals(
            HomeRows.firstLoginRecheckKey(FailoverState.Connecting("g1", 0, startedAtMs = 1L)),
            HomeRows.firstLoginRecheckKey(FailoverState.Connecting("g1", 0, startedAtMs = 900L)),
        )
    }

    @Test
    fun `接続の節目では初回ログインを再判定する`() {
        // 初回ログインが通ると Connecting → Verifying → Healthy と進む。
        // この遷移で鍵が変われば、注記はアプリの再起動なしに消える。
        val keys = listOf(
            HomeRows.firstLoginRecheckKey(FailoverState.Idle),
            HomeRows.firstLoginRecheckKey(FailoverState.Connecting("g1", 0, startedAtMs = 0L)),
            HomeRows.firstLoginRecheckKey(FailoverState.Verifying("g1", 0, connectedAtMs = 0L)),
            HomeRows.firstLoginRecheckKey(
                FailoverState.Healthy("g1", 0, consecutiveFailures = 0, lastProbeAtMs = 0L),
            ),
            // 候補が変わる・グループが変わる・切替待ちに入る・枯渇の再試行が進む
            HomeRows.firstLoginRecheckKey(FailoverState.Connecting("g1", 1, startedAtMs = 0L)),
            HomeRows.firstLoginRecheckKey(FailoverState.Connecting("g2", 0, startedAtMs = 0L)),
            HomeRows.firstLoginRecheckKey(
                FailoverState.FailingOver("g1", 0, awaitingUuid = "uuid-a", startedAtMs = 0L),
            ),
            HomeRows.firstLoginRecheckKey(FailoverState.Exhausted("g1", attempt = 0, retryAtMs = 0L)),
            HomeRows.firstLoginRecheckKey(FailoverState.Exhausted("g1", attempt = 1, retryAtMs = 0L)),
        )

        assertEquals("すべて異なる鍵になること", keys.size, keys.toSet().size)
    }
}
