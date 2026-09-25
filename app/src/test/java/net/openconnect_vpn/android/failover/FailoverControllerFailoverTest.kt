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
        controller = FailoverController({ listOf(group) }, clock, vpn, network)
    }

    @Test
    fun `候補1が無応答なら候補2へ切替し候補2が Healthy になる`() {
        toHealthy()

        // 3回連続でプローブ失敗
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // Ruling 25: 切替は2段階。まず切断を要求し FailingOver で確認を待つ。
        // 次候補はまだ起動しない。
        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        // uuid-a の切断完了が確認できたので候補2へ進む
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

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
        // 裁定31: runVPN() 冒頭の STATE_CONNECTING は Connected に至らない場合も送られる。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))

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
        // 裁定31: runVPN() 冒頭の STATE_CONNECTING はタイムアウトする場合も送られる。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        // connectTimeoutSec を超えて Tick が来る
        clock.advance(group.config.connectTimeoutSec * 1_000L + 1_000L)
        controller.handle(FailoverEvent.Tick)

        // Ruling 25: タイムアウトでも切断を要求してから FailingOver で確認を待つ
        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

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
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        // 猶予期間を過ぎてから閾値回数だけ失敗する
        clock.advance((group.config.graceAfterConnectSec + 1) * 1_000L)
        repeat(group.config.failureThreshold) {
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // Ruling 25: 切断要求後、FailingOver で確認を待つ
        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

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
    fun `閾値超過での切替は切断を要求し確認後に次候補へ進む`() {
        // Ruling 24 時代はここで「切替に明示的な切断は要らない」ことを検証していたが、
        // Ruling 25 でその前提は覆った。既存コア（OpenVpnService.onStartCommand）の
        // killVPNThread(true) はスレッド join を 1000ms で打ち切るため後から接続を
        // 完了させることがあり、それに任せると放棄したトンネルが残る（実機で確認）。
        // 現在の保証は「こちらから切断を要求し、確認してから次候補へ進む。かつ
        // 余分な2回目の切断はしない」こと。
        toHealthy()

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // 確認後に次候補が起動する。余分な切断は発生していない。
        assertEquals(1, vpn.disconnectCalls)
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
        val soloController = FailoverController({ listOf(soloGroup) }, clock, vpn, network)
        soloController.handle(FailoverEvent.UserConnectGroup("g1"))
        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        soloController.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(soloGroup.config.failureThreshold) {
            clock.advance(31_000L)
            soloController.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // Ruling 25: 次候補が無くても、まず切断を要求して FailingOver に入り
        // 確認を待つ（切替の入口は常に同じ手順を踏む）。
        assertTrue(soloController.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        // 確認できたら次候補が無いので Exhausted へ。
        // 切断は既に FailingOver 入口で済んでいるので、ここで2回目の
        // disconnect は発生しない。
        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

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
        val manualController = FailoverController({ listOf(manualGroup) }, clock, vpn, network)
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
        // Ruling 25: 切断要求後、FailingOver で uuid-a の確認を待つ
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())

        // 候補2(uuid-b)が繋がって健全になる
        // 裁定31: 実機同様、uuid-b 自身の Connecting を観測してから先へ進む。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // 候補2で本物の予期しない切断が起きる。これは Disconnected 自身が契機
        // （alreadyDown = true）なので、FailingOver を経ずに直接次候補へ進む。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))

        assertEquals("uuid-c", vpn.connectCalls.last())
        assertTrue(controller.state is FailoverState.Connecting)
    }

    // --- Ruling 25: 切替を2段階にする（FailingOver を実際の状態にする） ---

    @Test
    fun `プローブ失敗の閾値到達で切断を要求し FailingOver に入る`() {
        toHealthy()

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        // 次候補はまだ起動しない
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.FailingOver)
        assertEquals(0, (state as FailoverState.FailingOver).failedIndex)
    }

    @Test
    fun `FailingOver で当該 UUID の Disconnected を受けると次候補が起動する`() {
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(1, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `FailingOver 中に別候補の UUID で届いた Disconnected は前進させない`() {
        // 裁定34 のレビューで判明した訂正: 旧コメントは「onVpnState 冒頭の
        // Ruling 13 ガードがここで捨てており、FailingOver 内の awaitingUuid
        // 照合はそのガードにより到達不能なので、Ruling 13 のガードを外すと
        // このテストは落ちる」と主張していたが、これは誤りだった。実際に
        // Ruling 13 の冒頭ガード（uuid != currentCandidateUuid の早期 return）
        // を外して手元でトレースすると、sawCoreConnecting は既に true
        // （toHealthy() で uuid-a の Connecting を観測済み）なので上のガードには
        // 掛からず、`core == Disconnected && s is FailingOver` の分岐まで進む。
        // そこで awaitingUuid（= uuid-a）と uuid-c が一致しないため、
        // このテスト自身の awaitingUuid 照合が**独立に**捨てている。
        // つまりこのテストが検証しているのは「Ruling 13 のガード」ではなく
        // 「FailingOver は awaitingUuid と一致しない Disconnected では
        // 前進しない」という、FailingOver 自身が持つ不変条件である
        // （Ruling 13 のガードは通常経路ではこれより先に働くので二重の
        // 安全策ではあるが、このテストの合否はそちらには依存しない）。
        // Ruling 13 の冒頭ガード単体を検証する回帰テストは
        // FailoverControllerSafetyTest.kt の
        // `切替前の古い Disconnected は新しい候補を除外しない`
        // （FailingOver を経由しない状態で検証する）が担っている。
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        val callsBefore = vpn.connectCalls.size

        // 無関係な候補（uuid-c）の Disconnected が届く。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))

        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(callsBefore, vpn.connectCalls.size)
    }

    @Test
    fun `確認が来なくても DISCONNECT_WAIT_MS 経過後の Tick で前進する`() {
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)

        // DISCONNECT_WAIT_MS = 3000ms 経過
        clock.advance(3_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `DISCONNECT_WAIT_MS 未満の Tick では前進しない`() {
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)

        clock.advance(2_999L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    @Test
    fun `S2 下層ネットが無い間は確認を受けても前進せずネット復帰後の Tick で前進する`() {
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)

        // 下層ネットが無い間に確認が届いても前進しない
        network.available = false
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        // 待ち時間経過済みのままネットが復帰し、Tick が来ると前進する
        clock.advance(3_000L)
        network.available = true
        controller.handle(FailoverEvent.Tick)

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `欠陥13の回帰 FailingOver 中に放棄した候補の Connected が届いても戻らない`() {
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        // 放棄した候補（uuid-a）へ切断を要求したこと自体も検証する。欠陥13は
        // 「切断を要求せずに放置した」ことが原因だったので、これを確認しないと
        // このテストは本来の欠陥を検出できない。
        assertEquals(1, vpn.disconnectCalls)

        // 遅れて張られた放棄済み候補（uuid-a）のトンネルが Connected を報告する。
        // currentCandidateUuid はまだ uuid-a を指しているので Ruling 13 の
        // UUID 照合では弾かれない。それでも FailingOver から復活してはならない
        // （欠陥13: 誰も管理していないトンネルが残り続けた実機不具合の回帰）。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))

        assertTrue(controller.state is FailoverState.FailingOver)
    }

    @Test
    fun `Disconnected 起因の切替は FailingOver を経ずに直接次候補へ進み切断しない`() {
        toHealthy()

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(0, vpn.disconnectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals(1, (state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `次候補が無い状態で切替が起きたら切断を1回だけ要求し確認後に Exhausted に入る`() {
        val soloGroup = FailoverGroup(
            id = "g1",
            name = "単独候補",
            memberUuids = listOf("uuid-a"),
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )
        val soloController = FailoverController({ listOf(soloGroup) }, clock, vpn, network)
        soloController.handle(FailoverEvent.UserConnectGroup("g1"))
        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        soloController.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(soloGroup.config.failureThreshold) {
            clock.advance(31_000L)
            soloController.handle(FailoverEvent.ProbeResult(reachable = false))
        }

        assertTrue(soloController.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        soloController.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertTrue(soloController.state is FailoverState.Exhausted)
        // 確認後の前進では2回目の切断は発生しない
        assertEquals(1, vpn.disconnectCalls)
    }

    @Test
    fun `FailingOver 中の UserDisconnect は Idle に落ちる`() {
        toHealthy()
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.UserDisconnect)

        assertTrue(controller.state is FailoverState.Idle)
    }

    // --- 裁定30: FailingOver 中の UserPrompt を無害にする（Ruling 26 の性質の継承） ---

    @Test
    fun `FailingOver 中に UserPrompt が来ても待ち時間はリセットされず除外もされない`() {
        // 裁定32（レビュー指摘1・空虚なテストの修正）: 旧版はここで候補0
        // （toHealthy = UserConnectGroup 起動、unattended = false）のまま
        // FailingOver に入っていた。unattended が false だと
        // userPromptSinceMs は一度も武装されないので、failOver の
        // `userPromptSinceMs = null` を消しても、onTick の FailingOver 分岐に
        // onUnattendedPromptTimeout を足しても、このテストは常に緑のまま
        // 通ってしまっていた（何も検証していないのと同じ）。
        //
        // 無人運用の候補（unattended = true）自身の UserPrompt タイムアウトが
        // 原因で FailingOver に入る筋に作り直す: 候補0(uuid-a) を自動切替
        // （failOver）経由で候補1(uuid-b, unattended = true) へ進め、
        // 候補1 の UserPrompt が USER_PROMPT_WAIT_MS 進んでも進展しないことで
        // FailingOver に入らせる。
        toHealthy() // 候補0(uuid-a, unattended = false) を Healthy にする

        // 候補0をプローブ失敗で切替え、候補1(uuid-b, unattended = true) へ進める
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals("uuid-b", vpn.connectCalls.last())
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))

        // 候補1(uuid-b)が認証ダイアログで止まる（無人運用なので誰も答えられない）。
        // USER_PROMPT_WAIT_MS 経過で除外され FailingOver に入る（1回目の切断要求）。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(10_000L) // USER_PROMPT_WAIT_MS
        controller.handle(FailoverEvent.Tick)

        val firstFailingOver = controller.state
        assertTrue(firstFailingOver is FailoverState.FailingOver)
        assertTrue("uuid-b" in controller.excludedUuids)
        val disconnectCallsAfterEntry = vpn.disconnectCalls
        val startedAtMsAfterEntry = (firstFailingOver as FailoverState.FailingOver).startedAtMs

        // (a) 突入直後、ごく短い時間だけ進めて Tick する。もし failOver が
        // userPromptSinceMs を null にしていなければ、突入の原因になった
        // 「既に期限切れの」タイムスタンプがそのまま残る。もし同時に onTick が
        // FailingOver にも onUnattendedPromptTimeout を適用していれば、
        // ここで即座に2度目の除外・切断が起きてしまう
        // （= 裁定32 が挙げた2つの変更を両方戻すと、このアサーションで落ちる）。
        clock.advance(1L)
        controller.handle(FailoverEvent.Tick)
        assertEquals(disconnectCallsAfterEntry, vpn.disconnectCalls)
        assertEquals(
            startedAtMsAfterEntry,
            (controller.state as FailoverState.FailingOver).startedAtMs,
        )

        // (b) 既存コアの二重ブロードキャストを模して、FailingOver 中に同じ
        // uuid-b の UserPrompt がもう一度届く（currentCandidateUnattended は
        // まだ true）。USER_PROMPT_WAIT_MS だけ進めて Tick する。onTick の
        // FailingOver 分岐が onFailingOverTimeout しか呼ばない限り、この
        // 再武装は一切読まれず、2度目の disconnect() は起きない
        // （DISCONNECT_WAIT_MS より長く進めているので、確認を諦めて次候補へ
        // 進むこと自体は正しい前進である。これは onTick の FailingOver 分岐に
        // onUnattendedPromptTimeout を足すと単独でも落ちる）。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(10_000L) // USER_PROMPT_WAIT_MS
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(disconnectCallsAfterEntry, vpn.disconnectCalls)
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        // 裁定31: 実機では runVPN() の冒頭で必ず STATE_CONNECTING が送られる。
        // これを観測する前に届いた Disconnected は無視されるようになったので、
        // テストでも実機同様に Connecting を送ってから先へ進む。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }
}
