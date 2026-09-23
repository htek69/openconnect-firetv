package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 裁定59（レビュー指摘1・High）: ユーザーが Idle でない状態から別グループへの接続を
 * 指示した場合も、Ruling 25 の2段階切替（切断要求 → 完了確認 → 次を起動）を
 * 必ず経由することを確認する。
 *
 * ここを UI 側の規律任せにすると、放棄したはずの候補のスレッドが
 * `killVPNThread` の1秒 join を生き延びて後から tun を取り、画面は新グループが
 * 繋がったと言っているのに実際のトラフィックは旧グループのサーバを通る、という
 * 計画1 で欠陥13・欠陥15 として潰した事故が UI 起点の切替で再現する。
 * この修正前のコード（`onUserConnect` が常に `startCandidateFrom` を即座に呼ぶ）では、
 * 以下のテストはすべて確実に落ちる:
 * - 1〜3・7: 修正前は `vpn.disconnect()` が一度も呼ばれず、`connectCalls` に
 *   新グループの候補が即座に（切断確認を待たず）追加され、`state` は
 *   `FailingOver` ではなく `Connecting(新グループ)` になる。
 * - 4: 修正前はそもそも保留の概念が無いので、2回目の `UserConnectGroup` が
 *   即座に別の `startCandidateFrom` を呼んでしまい、`disconnectCalls` の期待値
 *   （据え置き）にも `connectCalls` の期待値（最後に指示したグループ）にも合わない。
 * - 5: 修正前は保留を持たないため「保留中に UserDisconnect」という状況自体が
 *   発生せず、そもそも意味を持たない（＝この安全性を検証できていなかった）。
 * - 6: 唯一、修正前後で同じ結果になる回帰確認。
 */
class FailoverControllerUserSwitchTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private val g1 = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )
    private val g2 = FailoverGroup(
        id = "g2",
        name = "予備",
        memberUuids = listOf("uuid-x", "uuid-y"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )
    private val g3 = FailoverGroup(
        id = "g3",
        name = "第3",
        memberUuids = listOf("uuid-p"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(0L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        controller = FailoverController(listOf(g1, g2, g3), clock, vpn, network)
    }

    @Test
    fun `接続中に別グループを指示すると切断を要求して FailingOver に入り まだ新グループを起動しない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        // 裁定31: 実機の既存コアは runVPN() 冒頭で必ず STATE_CONNECTING を送る。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g2"))

        assertEquals(1, vpn.disconnectCalls)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.FailingOver)
        assertEquals("g1", (state as FailoverState.FailingOver).groupId)
        assertEquals("uuid-a", state.awaitingUuid)
    }

    @Test
    fun `切断確認後に新グループの先頭候補から開始する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g2"))

        // 確認が来る前は、まだ g2 には一切繋いでいない（この時点の connectCalls と
        // disconnectCalls を確認しないと、「最終状態だけ一致する」誤った実装
        // （例: 確認を待たず即座に g2 へ繋ぎ、g1 の確認は UUID 不一致で単に無視される
        // だけの旧実装）を見逃す）。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertEquals(listOf("uuid-a", "uuid-x"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls) // 確認後も再送されていない
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g2", (state as FailoverState.Connecting).groupId)
        assertEquals(0, state.candidateIndex)
    }

    @Test
    fun `確認が来なくても DISCONNECT_WAIT_MS 経過後の Tick で新グループへ進む`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g2"))

        // タイムアウト前は、まだ g2 には一切繋いでいない（切断確認テストと同じ理由）。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)

        clock.advance(3_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals(listOf("uuid-a", "uuid-x"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g2", (state as FailoverState.Connecting).groupId)
    }

    @Test
    fun `FailingOver 中にさらに別グループを指示しても disconnect は再送されず startedAtMs も変わらない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g2")) // -> FailingOver(g1), disconnect#1, startedAtMs=0

        clock.advance(2_000L) // t=2000。DISCONNECT_WAIT_MS(3000) 未満。
        controller.handle(FailoverEvent.UserConnectGroup("g3")) // 保留先を g3 に差し替えるだけのはず

        assertEquals(1, vpn.disconnectCalls) // 再送されていない
        assertEquals(listOf("uuid-a"), vpn.connectCalls) // まだどこにも繋いでいない
        assertTrue(controller.state is FailoverState.FailingOver)

        // startedAtMs が更新されていなければ、元の t=0 起点で t=3000 に到達した
        // この Tick で DISCONNECT_WAIT_MS の期限が来て前進する。
        // もし裁定26 に反して更新されていたら（t=2000 起点）、ここではまだ
        // 前進せず、この assert が落ちる。
        clock.advance(1_000L) // t=3000
        controller.handle(FailoverEvent.Tick)

        // 最後に指示したグループ（g3）の先頭候補へ繋ぐ（g2 ではない）
        assertEquals(listOf("uuid-a", "uuid-p"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g3", (state as FailoverState.Connecting).groupId)
    }

    @Test
    fun `保留中に UserDisconnect が来たら Idle になり 確認後も新グループを起動しない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.UserConnectGroup("g2")) // -> FailingOver(g1), pending=g2

        controller.handle(FailoverEvent.UserDisconnect)
        assertTrue(controller.state is FailoverState.Idle)

        // 遅れて届いた切断確認が来ても、勝手に新グループを起動しない
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `Idle から別グループを指示した場合は従来どおり即座に起動する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g2"))

        assertEquals(0, vpn.disconnectCalls)
        assertEquals(listOf("uuid-x"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g2", (state as FailoverState.Connecting).groupId)
    }

    @Test
    fun `自動切替による FailingOver 中にユーザーが別グループを指示しても disconnect は再送されない`() {
        // g1 の自動切替（プローブ失敗）で FailingOver に入る（このとき pending は未設定）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance((g1.config.graceAfterConnectSec + 1) * 1_000L)
        repeat(g1.config.failureThreshold) {
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        // ここでユーザーが g3 を指示。disconnect は再送されず、保留先だけが立つ。
        controller.handle(FailoverEvent.UserConnectGroup("g3"))
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // 自動切替が本来次に試すはずだった候補（uuid-b）ではなく、
        // ユーザーが指示した g3 へ進む。
        assertEquals(listOf("uuid-a", "uuid-p"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g3", (state as FailoverState.Connecting).groupId)
    }
}
