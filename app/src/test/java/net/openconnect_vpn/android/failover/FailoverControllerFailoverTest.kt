package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // Ruling 24: 切替先があるので、こちらから明示的な切断は要求しない
        // （既存コアが新プロファイル起動時に自分で旧トンネルを止める）。
        assertEquals(0, vpn.disconnectCalls)
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

        // トンネルは既に落ちているので切断要求を出してはならない。
        // ここで余分な disconnect が入ると、安全策 S3（意図的切断と障害の判別）が壊れる。
        assertEquals(0, vpn.disconnectCalls)
    }

    @Test
    fun `接続中のまま切断されたら次候補へ進む`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // Connected に至らず Disconnected が来た（接続失敗）
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)

        // 同上。接続が成立していないので切断要求は不要。
        assertEquals(0, vpn.disconnectCalls)
    }

    // --- Ruling 22a: Connecting に無期限に留まらない ---

    @Test
    fun `接続タイムアウトで次候補へ切替する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        // connectTimeoutSec を超えて Tick が来る
        clock.advance(group.config.connectTimeoutSec * 1_000L + 1_000L)
        controller.handle(FailoverEvent.Tick)

        // Ruling 24: 切替先があるので、こちらから明示的な切断は要求しない
        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
        // タイムアウトはネットワーク障害であり認証失敗ではないので除外しない
        assertFalse("uuid-a" in controller.excludedUuids)
    }

    @Test
    fun `接続タイムアウト前の Tick では切替しない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        val stateBefore = controller.state

        // connectTimeoutSec 未満で Tick が来る
        clock.advance(group.config.connectTimeoutSec * 1_000L - 1_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(stateBefore, controller.state)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(0, vpn.disconnectCalls)
    }

    // --- Ruling 22b: Verifying も無期限に留まらない ---

    @Test
    fun `猶予期間後のプローブ失敗が閾値に達したら切替する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        // 猶予期間を過ぎてから閾値回数だけ失敗する
        clock.advance((group.config.graceAfterConnectSec + 1) * 1_000L)
        repeat(group.config.failureThreshold) {
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // Ruling 24: 切替先があるので、こちらから明示的な切断は要求しない
        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        // 疎通できないだけで認証は失敗していないので除外しない
        assertFalse("uuid-a" in controller.excludedUuids)
    }

    @Test
    fun `猶予期間後の失敗が閾値未満で回復したら Healthy になる`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        clock.advance((group.config.graceAfterConnectSec + 1) * 1_000L)
        repeat(group.config.failureThreshold - 1) {
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(0, vpn.disconnectCalls)
    }

    // --- Ruling 24: 切替時は切断しない。諦めるときだけ切断する ---

    @Test
    fun `閾値超過での切替は明示的な切断を要求しない`() {
        toHealthy()

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 既存コア（OpenVpnService.onStartCommand）が新プロファイル起動前に
        // 自分で killVPNThread(true) して旧トンネルを止めるので、こちらから
        // stopService してはならない（実機で健全な候補を巻き込んで切断していた）。
        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(1, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `最後の候補が失敗して Exhausted に入るときは切断する`() {
        // 候補が1件だけのグループ。失敗すれば切替先が無く Exhausted へ諦める。
        val soloGroup = FailoverGroup(
            id = "g1",
            name = "単独候補",
            memberUuids = listOf("uuid-a"),
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )
        val soloController = FailoverController(listOf(soloGroup), clock, vpn, network)
        soloController.handle(FailoverEvent.UserConnectGroup("g1"))
        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        soloController.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(soloGroup.config.failureThreshold) {
            clock.advance(31_000L)
            soloController.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // これ以上試す候補が無い。放置されたトンネルを残さないよう、
        // ここで初めて明示的に切断する。
        assertTrue(soloController.state is FailoverState.Exhausted)
        assertEquals(1, vpn.disconnectCalls)
    }

    @Test
    fun `自動切替が無効なら失敗時に切断して Idle へ戻る`() {
        val manualGroup = FailoverGroup(
            id = "g1",
            name = "手動運用",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = false,
            config = FailoverConfig(),
        )
        val manualController = FailoverController(listOf(manualGroup), clock, vpn, network)
        manualController.handle(FailoverEvent.UserConnectGroup("g1"))
        manualController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        manualController.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(manualGroup.config.failureThreshold) {
            clock.advance(31_000L)
            manualController.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 切替先を試しにすら行かない（R6）。生きたトンネルを残さないよう切断する。
        assertTrue(manualController.state is FailoverState.Idle)
        assertEquals(1, vpn.disconnectCalls)
    }

    @Test
    fun `切替後の新候補で本物の障害が起きても検知される`() {
        // S3(expectingDisconnect)・S1(Ruling13 UUID照合)の両方が守っている境界。
        // 切替時に disconnect を要求しなくなった後も、新候補自身の本物の障害は
        // 飲み込まれず検知されなければならない。
        toHealthy()

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertEquals(0, vpn.disconnectCalls)
        assertEquals("uuid-b", vpn.connectCalls.last())

        // 候補2(uuid-b)が繋がって健全になる
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // 候補2で本物の予期しない切断が起きる
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))

        assertEquals("uuid-c", vpn.connectCalls.last())
        assertTrue(controller.state is FailoverState.Connecting)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
