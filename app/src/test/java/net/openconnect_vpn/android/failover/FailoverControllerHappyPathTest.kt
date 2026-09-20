package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerHappyPathTest {

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
        controller = FailoverController(
            groups = listOf(group),
            clock = clock,
            vpn = vpn,
            network = network,
        )
    }

    @Test
    fun `初期状態は Idle`() {
        assertTrue(controller.state is FailoverState.Idle)
    }

    @Test
    fun `ユーザーが接続を指示すると先頭候補へ接続する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(0, (state as FailoverState.Connecting).candidateIndex)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `接続完了で Verifying に入る`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        clock.advance(3_000L)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        val state = controller.state
        assertTrue(state is FailoverState.Verifying)
        assertEquals(0, (state as FailoverState.Verifying).candidateIndex)
        assertEquals(4_000L, state.connectedAtMs)
    }

    @Test
    fun `猶予期間中はプローブを打たない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        clock.advance(10_000L)  // graceAfterConnectSec = 15 未満
        assertFalse(controller.shouldProbeNow())
    }

    @Test
    fun `猶予期間を過ぎるとプローブを打つ`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        clock.advance(16_000L)  // graceAfterConnectSec = 15 を超えた
        assertTrue(controller.shouldProbeNow())
    }

    @Test
    fun `初回プローブ成功で Healthy になる`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        val state = controller.state
        assertTrue(state is FailoverState.Healthy)
        assertEquals(0, (state as FailoverState.Healthy).candidateIndex)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `Healthy 中はプローブ間隔ごとにプローブを打つ`() {
        toHealthy()

        clock.advance(29_000L)   // probeIntervalSec = 30 未満
        assertFalse(controller.shouldProbeNow())

        clock.advance(2_000L)    // 合計 31 秒
        assertTrue(controller.shouldProbeNow())
    }

    @Test
    fun `Healthy 中のプローブ成功で失敗カウントが 0 に戻る`() {
        toHealthy()

        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = false))
        assertEquals(1, (controller.state as FailoverState.Healthy).consecutiveFailures)

        clock.advance(31_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertEquals(0, (controller.state as FailoverState.Healthy).consecutiveFailures)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
