package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerFailoverTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        controller = FailoverController(listOf(group), clock, vpn, network)
    }

    @Test
    fun `候補1が無応答なら候補2へ切替し候補2が Healthy になる`() {
        toHealthy()

        // 3回連続でプローブ失敗
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 切断要求が出て候補2への接続が始まる
        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)

        // 候補2が繋がって健全になる
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        val state = controller.state
        assertTrue(state is FailoverState.Healthy)
        assertEquals(1, (state as FailoverState.Healthy).candidateIndex)
    }

    @Test
    fun `連続失敗が閾値未満で回復したら切替しない`() {
        toHealthy()

        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = false))
        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = false))
        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `猶予期間中のプローブ失敗では切替しない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        // 猶予期間内に何度失敗しても Verifying に留まる
        repeat(5) {
            clock.advance(1_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        assertTrue(controller.state is FailoverState.Verifying)
        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `予期しない切断は即座に次候補へ切替する`() {
        toHealthy()

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `接続中のまま切断されたら次候補へ進む`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // Connected に至らず Disconnected が来た（接続失敗）
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
