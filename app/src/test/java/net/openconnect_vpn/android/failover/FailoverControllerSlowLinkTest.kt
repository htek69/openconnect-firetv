package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 仕様書 4-3/4-4/4-5: スループット低下による切替。
 *
 * 判定そのものは [SlowLinkDetector] が持ち、ここへは真偽値で届く。よってこの
 * テストが固定するのは「**いつ切り替え、いつ切り替えないか**」だけである。
 */
class FailoverControllerSlowLinkTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private var slow = false
    private var autoFailover = true

    private val group
        get() = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = autoFailover,
            config = FailoverConfig(),
        )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        slow = false
        autoFailover = true
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            slowLinkProvider = { slow },
        )
    }

    /** uuid-a を `Healthy` まで進める（猶予も越える）。 */
    private fun toHealthyOnA() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L) // graceAfterConnectSec = 15 を越える
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `遅いと判定されたら次候補へ切り替える`() {
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)

        // Ruling 25: まず切断を要求し、確認を待つ。
        val failingOver = controller.state as FailoverState.FailingOver
        // 裁定（Task 7 用）: 速度起因の切替であることが状態に残る。
        assertTrue(failingOver.bySlowLink)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `機能が無効なら遅くても切り替えない`() {
        toHealthyOnA()
        slow = false
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `自動切替が OFF のグループでは切り替えない`() {
        autoFailover = false
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `下層ネットワークが無いときは切り替えない`() {
        toHealthyOnA()
        slow = true
        network.available = false
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `接続直後の猶予中は切り替えない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        slow = true
        clock.advance(5_000L) // 猶予 15 秒の内側
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state !is FailoverState.FailingOver)
    }

    /**
     * 候補2件を速度起因で一周させる。終わりは `Exhausted`
     * （2件目の切断確認のあと、次に試す候補がもう無い）。
     */
    private fun lapOnceBySlowness() {
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
        controller.handle(FailoverEvent.Tick) // uuid-b も遅い → 2回目の切替
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))
        assertTrue(controller.state is FailoverState.Exhausted)
    }

    /**
     * 一周したあと、`Exhausted` のバックオフ満了による再試行で uuid-a を
     * もう一度 `Healthy` まで戻す。
     *
     * ブリーフの元コードはここで `VpnStateChanged` を直接与えていたが、実物の
     * Ruling 13（現在の候補と一致しない UUID の通知は無視する）により、
     * `Exhausted`（＝現在の候補は uuid-b のまま）へ uuid-a の通知を与えても
     * 状態は一切動かない。**テストの意図（一周後に候補が再び Healthy になっても
     * 遅さでは切り替えない／死活では切り替わる）を実物の経路で作り直したもの。**
     */
    private fun backToHealthyOnAAfterLap() {
        clock.advance(31_000L) // Backoff.delayMsForAttempt(0) = 30 秒を越える
        controller.handle(FailoverEvent.Tick) // 枯渇後の再試行 → uuid-a を起動
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `一周したら速度による切替を止める`() {
        lapOnceBySlowness()
        // 一周後にどこかの候補が Healthy になっても、遅いままでは切り替えない。
        backToHealthyOnAAfterLap()
        val before = vpn.connectCalls.size
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(before, vpn.connectCalls.size)
    }

    @Test
    fun `ユーザー操作の接続で一周の記録が解除される`() {
        lapOnceBySlowness()
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    @Test
    fun `死活による切替は一周後も従来どおり働く`() {
        lapOnceBySlowness()
        backToHealthyOnAAfterLap()
        repeat(3) { // failureThreshold
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        val failingOver = controller.state as FailoverState.FailingOver
        // 死活起因の切替なので、速度起因の印は付かない。
        assertFalse(failingOver.bySlowLink)
    }
}
