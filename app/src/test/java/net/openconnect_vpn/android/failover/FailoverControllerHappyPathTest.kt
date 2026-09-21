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

    @Test
    fun `Healthy から予期しない Disconnected が来たら即座に次候補へ切替する`() {
        // 裁定37（M3）の核心の回帰テスト。レビューが指摘したとおり、
        // このテストクラスの toHealthy() は（このテストでは意図的に）Connecting を
        // 送らなくても Connected → ProbeResult(true) だけで Healthy に到達できて
        // しまう（sawCoreConnecting は false のまま）。ガードを絞る前のコード
        // （`core == Disconnected && !sawCoreConnecting -> s`、状態を問わない）
        // では、Healthy かつ sawCoreConnecting == false のこの組合せで
        // Disconnected が無条件に捨てられ、このテストは確実に落ちる
        // （state が Healthy のまま、connectCalls が増えない）。
        // 裁定37 のガード（Connecting/FailingOver のときだけ捨てる）ではこの
        // Disconnected は捨てられず、次候補へ正しく切替わる。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        // 裁定31/37: 実機では runVPN() の冒頭で必ず STATE_CONNECTING が送られる。
        // このテストクラスだけ Connecting の送信が漏れていた（レビュー M3 で指摘）。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
