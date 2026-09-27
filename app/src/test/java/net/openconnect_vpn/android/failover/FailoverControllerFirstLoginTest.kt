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
}
