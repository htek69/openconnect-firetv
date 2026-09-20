package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerExhaustionTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate

    private fun controllerFor(group: FailoverGroup) =
        FailoverController(listOf(group), clock, vpn, network)

    private fun group(auto: Boolean = true) = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b"),
        autoFailoverEnabled = auto,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
    }

    @Test
    fun `全候補が接続失敗すると Exhausted に入る`() {
        val controller = controllerFor(group())
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // uuid-a: 認証通過後に切断（除外されない）
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        // uuid-b: 同様に失敗
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        val state = controller.state
        assertTrue(state is FailoverState.Exhausted)
        assertEquals(0, (state as FailoverState.Exhausted).attempt)
        assertEquals(clock.now + 30_000L, state.retryAtMs)
    }

    @Test
    fun `バックオフ満了で先頭候補から再試行する`() {
        val controller = controllerFor(group())
        toExhausted(controller)
        val callsBefore = vpn.connectCalls.size

        clock.advance(30_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals("uuid-a", vpn.connectCalls.last())
        assertEquals(callsBefore + 1, vpn.connectCalls.size)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `バックオフ満了前の Tick では再試行しない`() {
        val controller = controllerFor(group())
        toExhausted(controller)
        val callsBefore = vpn.connectCalls.size

        clock.advance(29_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(callsBefore, vpn.connectCalls.size)
        assertTrue(controller.state is FailoverState.Exhausted)
    }

    @Test
    fun `枯渇を繰り返すとバックオフ間隔が指数的に伸びる`() {
        val controller = controllerFor(group())
        toExhausted(controller)
        assertEquals(0, (controller.state as FailoverState.Exhausted).attempt)

        // 1回目の再試行も全滅させる
        clock.advance(30_000L)
        controller.handle(FailoverEvent.Tick)
        failAllCandidates(controller)

        val state = controller.state as FailoverState.Exhausted
        assertEquals(1, state.attempt)
        assertEquals(clock.now + 60_000L, state.retryAtMs)
    }

    @Test
    fun `自動切替 OFF のグループは障害時に切替せず Idle で止まる`() {
        val controller = controllerFor(group(auto = false))
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)
    }

    @Test
    fun `自動切替 OFF なら予期しない切断でも次候補へ行かない`() {
        val controller = controllerFor(group(auto = false))
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    private fun toExhausted(controller: FailoverController) {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        failAllCandidates(controller)
    }

    private fun failAllCandidates(controller: FailoverController) {
        repeat(2) {
            controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
            controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        }
    }
}
