package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 6.5（裁定48・裁定72）: `FailoverController` はコンストラクタで
 * `groupsProvider: () -> List<FailoverGroup>` という供給関数を受け取り、
 * `groups` を1回だけ保持することをやめた。このテストはその供給関数が
 * 「今」返す値が毎回の判断に反映されること（裁定72b の R6 トグルを含む）と、
 * 供給関数が返すグループのメンバーが接続中に変わっても、現在の候補の
 * *identity*（`currentCandidateUuid`）が添字より優先されること
 * （裁定72a。計画1 の欠陥13・欠陥15 と同種の「画面は A、実際は B」という
 * 嘘を設定変更の経路から防ぐ）を確認する。
 *
 * すべてのテストで [controllerWith] が返す `MutableList` を書き換えることで
 * 「TV UI が保存した変更が動作中のコントローラへ反映される」状況を再現する。
 * 供給関数化する前のコード（コンストラクタが `groups: List<FailoverGroup>` を
 * 1回だけ保持する版）では、この書き換えは一切コントローラに見えないため、
 * このファイルの全テストが該当箇所で確実に落ちる。
 */
class FailoverControllerReloadTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
    }

    /** 供給関数の中身を後から書き換えられるよう、バッキングの MutableList を返す。 */
    private fun controllerWith(vararg groups: FailoverGroup): Pair<FailoverController, MutableList<FailoverGroup>> {
        val current = groups.toMutableList()
        val controller = FailoverController(
            groupsProvider = { current.toList() },
            clock = clock,
            vpn = vpn,
            network = network,
        )
        return controller to current
    }

    @Test
    fun `グループの供給関数が返す値を変えると次の判断に反映される`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a"),
            autoFailoverEnabled = true,
        )
        val (controller, groups) = controllerWith(group)

        // 供給関数が g1 をまだ返さない間は、UI が「接続」を送っても何も起きない
        // （groups が1回だけ保持される旧実装なら、ここで最初から g1 を渡している
        // ため区別が付かない。それがまさにこの1件目のテストの限界であり、以降の
        // テストで実際の書き換えを検証する）。
        groups.clear()
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        assertTrue(controller.state is FailoverState.Idle)

        // 供給関数が g1 を返すようになった後の同じイベントでは接続できる。
        // 固定リストを1回だけ保持する実装ではこの2回目の handle でも
        // Idle のままになり、このアサーションで落ちる。
        groups.add(group)
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `接続中にメンバーの順序が変わっても、同じ接続先を指し続ける`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
            autoFailoverEnabled = true,
        )
        val (controller, groups) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        val before = controller.state
        assertTrue(before is FailoverState.Healthy)
        assertEquals(0, (before as FailoverState.Healthy).candidateIndex)

        // TV UI がメンバーを並び替える。uuid-a（現在の接続先）は末尾に動く。
        groups[0] = group.copy(memberUuids = listOf("uuid-c", "uuid-b", "uuid-a"))
        controller.handle(FailoverEvent.Tick)

        // 添字は新しい位置（2）へ振り直されるが、状態機械は同じ接続先
        // （uuid-a）を指し続ける。裁定72a を実装しない場合、この添字は
        // 古い位置（0）のまま残り、UI 側は誤って uuid-c に繋がっていると
        // 表示することになる（欠陥13/15 と同種の嘘）。
        val after = controller.state
        assertTrue(after is FailoverState.Healthy)
        assertEquals(2, (after as FailoverState.Healthy).candidateIndex)

        // 接続そのものは維持される。余計な connect/disconnect は起きない。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(0, vpn.disconnectCalls)
    }

    @Test
    fun `接続中の候補がグループから外されたら、障害として次候補へ切り替わる`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
            autoFailoverEnabled = true,
        )
        val (controller, groups) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // TV UI が現在の接続先（uuid-a）をグループから削除する。
        groups[0] = group.copy(memberUuids = listOf("uuid-b", "uuid-c"))
        controller.handle(FailoverEvent.Tick)

        // 外された候補は「生きている候補」として扱い続けてはならない。障害として
        // Ruling 25 の通常経路（切断要求 -> FailingOver で確認を待つ）に入る。
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // 新しいメンバー一覧の先頭（uuid-b）から次候補を試す。もし古い添字
        // （0）をそのまま +1 して使うと、新しい一覧では範囲外になり、
        // まだ試していない候補があるのに Exhausted へ落ちてしまう。
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(0, (state as FailoverState.Connecting).candidateIndex)
        assertEquals("g1", state.groupId)
    }

    @Test
    fun `autoFailoverEnabled を false に変えると、次の障害で切替せず Idle で止まる`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = true,
        )
        val (controller, groups) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // R6: 接続中のグループに対して自動切替を OFF にする。
        groups[0] = group.copy(autoFailoverEnabled = false)

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 供給関数化する前のコード（コンストラクタに渡した groups の参照を
        // ずっと使い続ける）ではこの OFF への変更は一切コントローラに届かず、
        // 通常どおり FailingOver へ切り替わってこのアサーションが落ちる。
        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `autoFailoverEnabled を true に戻すと、また切替するようになる`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = false,
        )
        val (controller, groups) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // OFF のままなら障害時に切替せず Idle へ落ちる（前のテストと同じ前提）。
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(1, vpn.disconnectCalls)

        // 自動切替を ON に戻して改めて接続する。
        groups[0] = group.copy(autoFailoverEnabled = true)
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 今度は ON になっているので、通常どおり切替（FailingOver への遷移）が起きる。
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(2, vpn.disconnectCalls)
    }
}
