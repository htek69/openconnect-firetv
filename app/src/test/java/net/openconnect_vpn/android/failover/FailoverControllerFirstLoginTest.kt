package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 初回ログインが未完了の接続先（認証情報が保存されていない＝人が居なければ絶対に
 * 成功しない相手）を、**機械が始める接続では候補にしない**という規則の検証。
 *
 * 症状: 6件のグループを自動で巡回すると、初回ログイン未完了のメンバーが約2分ごとに
 * `unattended = true` で起動され、裁定30 の10秒で見捨てられて裁定95 の
 * `cancelActiveDialog()` が認証ダイアログを畳む。利用者は入力中のダイアログを
 * 消され続け、初回ログインを完了できない。
 *
 * ここで検証する対になる性質:
 * - 無人の開始（`failOver` と枯渇後の再試行）では飛ばす
 * - 利用者の明示操作（`UserConnectGroup`）では**飛ばさない**
 *   （初回ログインはこの経路で行うのだから、ここで飛ばすと誰も登録できない）
 */
class FailoverControllerFirstLoginTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate

    private fun group(vararg members: String) = FailoverGroup(
        id = "g1",
        name = "日本",
        memberUuids = members.toList(),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    private fun controllerFor(
        group: FailoverGroup,
        needsFirstLogin: Set<String> = emptySet(),
    ) = FailoverController(
        groupsProvider = { listOf(group) },
        clock = clock,
        vpn = vpn,
        network = network,
        needsFirstLoginProvider = { uuid -> uuid in needsFirstLogin },
    )

    /**
     * 認証段階に到達せずに落ちた切断。Ruling 23 により S1 の除外は起きないので、
     * 「除外」ではなく「初回ログインによる飛ばし」だけを見られる。
     * 裁定31 により、自分の `Connecting` を観測してから `Disconnected` を送る。
     */
    private fun failCurrentCandidateWithoutReachingAuth(controller: FailoverController) {
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
    }

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
    }

    @Test
    fun `無人の切替では初回ログイン未完了のメンバーを飛ばす`() {
        val controller = controllerFor(
            group("uuid-a", "uuid-b", "uuid-c"),
            needsFirstLogin = setOf("uuid-b"),
        )

        // 利用者の操作で開始。先頭の uuid-a は保存済みなのでそのまま起動する。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        // uuid-a が落ちた → failOver（unattended = true）。uuid-b は飛ばして uuid-c へ。
        failCurrentCandidateWithoutReachingAuth(controller)

        assertEquals(listOf("uuid-a", "uuid-c"), vpn.connectCalls)
        assertFalse("飛ばしは除外ではない", "uuid-b" in controller.excludedUuids)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(2, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `枯渇後の再試行でも初回ログイン未完了のメンバーを飛ばす`() {
        val controller = controllerFor(
            group("uuid-a", "uuid-b"),
            needsFirstLogin = setOf("uuid-a"),
        )

        // 利用者の操作で開始（この経路では uuid-a も飛ばさない）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        // uuid-a → uuid-b → 枯渇。どちらも認証段階に届かないので除外はされない。
        failCurrentCandidateWithoutReachingAuth(controller)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        failCurrentCandidateWithoutReachingAuth(controller)
        assertTrue(controller.state is FailoverState.Exhausted)

        // バックオフ満了。再試行は fromIndex = 0 の無人経路なので uuid-a は飛ばす。
        clock.advance(30_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(listOf("uuid-a", "uuid-b", "uuid-b"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(1, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `利用者の明示操作では初回ログイン未完了でも飛ばさない`() {
        // ここで飛ばすと、初回ログインを行う唯一の経路が閉じて誰も登録できなくなる。
        val controller = controllerFor(
            group("uuid-a", "uuid-b"),
            needsFirstLogin = setOf("uuid-a", "uuid-b"),
        )

        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(0, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `接続中に利用者が指示した場合も切断確認のあとで飛ばさない`() {
        // 裁定86（H2）の PendingConnect.unattended は、切断待ちを挟んでも
        // 「利用者が指示した」ことを持ち越す。ここで unattended が true に
        // 化けると、既にそのグループへ接続中の状態で初回ログインを始めようと
        // した利用者（＝報告された状況そのもの）が永久に登録できなくなる。
        val controller = controllerFor(
            group("uuid-a", "uuid-b"),
            needsFirstLogin = setOf("uuid-a", "uuid-b"),
        )

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        // 同じグループへもう一度指示する（生きている候補があるので2段階になる）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertEquals(listOf("uuid-a", "uuid-a"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(0, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `全員が初回ログイン未完了なら無人の開始は既存の枯渇へ落ちて空回りしない`() {
        val controller = controllerFor(
            group("uuid-a", "uuid-b"),
            needsFirstLogin = setOf("uuid-a", "uuid-b"),
        )

        // 無人の開始（裁定44 の点灯・Ruling 18 の復帰など）。
        controller.handle(FailoverEvent.AutoConnectGroup("g1"))

        assertTrue(vpn.connectCalls.isEmpty())
        val first = controller.state
        assertTrue(first is FailoverState.Exhausted)
        assertEquals(0, (first as FailoverState.Exhausted).attempt)
        assertEquals(clock.now + 30_000L, first.retryAtMs)

        // 再試行のたびにバックオフが伸びる（＝新しい経路を作らずに暴走も防げている）。
        clock.advance(30_000L)
        controller.handle(FailoverEvent.Tick)

        assertTrue(vpn.connectCalls.isEmpty())
        val second = controller.state
        assertTrue(second is FailoverState.Exhausted)
        assertEquals(1, (second as FailoverState.Exhausted).attempt)
        assertEquals(clock.now + 60_000L, second.retryAtMs)

        // 誰も除外していない（飛ばしは S1 の除外ではない）。
        assertTrue(controller.excludedUuids.isEmpty())
    }

    @Test
    fun `供給関数を渡さなければ従来どおり誰も飛ばさない`() {
        // 既定 { false } の意味。既存の呼び出し元とテストは無改変で通る。
        val controller = FailoverController(
            { listOf(group("uuid-a", "uuid-b")) },
            clock,
            vpn,
            network,
        )

        controller.handle(FailoverEvent.AutoConnectGroup("g1"))
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        failCurrentCandidateWithoutReachingAuth(controller)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    // ------------------------------------------------------------------
    // 開始位置を指定する有人接続（接続先の行の「初回ログイン」から来る経路）。
    //
    // 有人の接続は常に先頭メンバーから始まるため、初回ログインが必要な接続先が
    // 2番目以降にあると到達する手段が無かった（一覧には「初回ログインが必要」と
    // 出るのに利用者にできることが無い）。既存の有人経路にそのまま開始位置を
    // 足して埋める。新しい接続経路は作らない（すべて startCandidateFrom を通る）。
    // ------------------------------------------------------------------

    @Test
    fun `開始位置を指定した利用者の接続はそのメンバーから始まり飛ばさない`() {
        val controller = controllerFor(
            group("uuid-a", "uuid-b", "uuid-c"),
            needsFirstLogin = setOf("uuid-c"),
        )

        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = 2))

        assertEquals(listOf("uuid-c"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(2, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `開始位置を渡さない従来の利用者の接続は先頭から始まる`() {
        // 既定 fromIndex = 0 の意味。既存の呼び出し元とテストは無改変で通る。
        val controller = controllerFor(group("uuid-a", "uuid-b", "uuid-c"))

        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(0, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `開始位置の指定は除外集合のクリアを変えない`() {
        // 裁定35a: 明示的なユーザー操作は新しいセッションの意思表示である。
        // 開始位置を指定する入口でも意味を変えない（変えると「除外されたあと
        // 初回ログインだけは通る／通らない」という別の規則が生まれる）。
        val controller = controllerFor(group("uuid-a", "uuid-b"))

        // 認証段階に到達して通らずに落ちた（Ruling 23 で S1 の除外が起きる）。
        // 裁定31a により、自分の Connecting を観測してから Disconnected を送る。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        assertTrue("uuid-a" in controller.excludedUuids)

        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = 1))

        assertTrue(controller.excludedUuids.isEmpty())
    }

    @Test
    fun `範囲外の開始位置では何も起動しない`() {
        // グループ構成が画面の読み込みより後に変わっていた場合。先頭から
        // 繋ぎ直すと「利用者が指したのとは別の接続先」が始まってしまうので、
        // 何もしない（枯渇にも入らない＝バックオフの再試行を始めない）。
        val controller = controllerFor(group("uuid-a", "uuid-b"))

        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = 5))

        assertTrue(vpn.connectCalls.isEmpty())
        assertEquals(0, vpn.disconnectCalls)
        assertEquals(FailoverState.Idle, controller.state)
    }

    @Test
    fun `範囲外の開始位置はいま生きている候補も止めない`() {
        val controller = controllerFor(group("uuid-a", "uuid-b"))

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))

        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = -1))

        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(0, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `切断待ちを挟んでも指定した開始位置を持ち越す`() {
        // 裁定86（H2）の PendingConnect は有人・無人を持ち越す。開始位置も
        // 同じように持ち越さないと、接続中に初回ログインを始めた利用者は
        // 切断のあと先頭メンバーへ繋ぎ直されてしまう（＝到達できないままになる）。
        val controller = controllerFor(
            group("uuid-a", "uuid-b", "uuid-c"),
            needsFirstLogin = setOf("uuid-c"),
        )

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = 2))
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertEquals(listOf("uuid-a", "uuid-c"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(2, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `切断を待つあいだに指定したメンバーが消えたら何も起動しない`() {
        var current = group("uuid-a", "uuid-b", "uuid-c")
        val controller = FailoverController(
            groupsProvider = { listOf(current) },
            clock = clock,
            vpn = vpn,
            network = network,
        )

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = 2))
        assertTrue(controller.state is FailoverState.FailingOver)

        // 切断を待っているあいだにグループから外された。
        current = group("uuid-a")
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(FailoverState.Idle, controller.state)
    }
}
