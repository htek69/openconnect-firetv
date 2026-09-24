package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * レビュー `review-task-6-6.5.md` の修正ラウンド（F1・F2・F6）の回帰テスト。
 *
 * F1: `groupsProvider()` が返す一覧から現在接続中のグループが丸ごと消えた場合、
 * 供給関数化する前は到達すらしなかった `reconcileCandidateIdentity` /
 * `failOver` の「グループが見つからない」分岐が、Task 6.5 以降は実際に到達する
 * ようになった。修正前はどちらも `vpn.disconnect()` を呼ばずに `Idle` を返して
 * いたため、トンネルは張られたまま誰にも監視されなくなり、次の
 * `onUserConnect` が「生きている候補は無い」という `Idle` の前提を信じて
 * Ruling 25 の2段階を素通りしてしまう（欠陥13/欠陥15 と同じ事故が設定変更の
 * 経路から再来する）。
 *
 * F2: `FailingOver` が `DISCONNECT_WAIT_MS` の確認待ちを諦めて `Exhausted`/
 * `Idle` へ進んだとき、諦めた候補のスレッドが実際に終了した保証は無い
 * （`UserDialog.waitForResponse()` にブロックしたスレッドは `stopVPN()` では
 * 解放されない）。以前のコードは「`Exhausted` に至る前の切替はすべて2段階を
 * 経ているので待つべき相手が無い」という、タイムアウト経路では成り立たない
 * 前提のもとに `Exhausted`/`Idle` からの `onUserConnect` を即座に起動していた。
 *
 * F6: `onAutoConnect` が `pendingConnectGroupId` を消していなかった。今日の
 * 唯一の呼び出し元（`FailoverService` の起動時復帰・画面点灯時の再接続）は
 * どちらも `Idle` からしか呼ばないので現状は無害だが、コントローラ自身は
 * その前提を検証していない。
 */
class FailoverControllerFix1Test {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
    }

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
    fun `グループが丸ごと削除されたら生きている候補を切断してから Idle に落ちる`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a"),
            autoFailoverEnabled = true,
        )
        val (controller, groups) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // グループ自体が丸ごと削除される（例: 最後のメンバーだったプロファイルが
        // 消え、GroupStore.loadGroups がグループごと落とした）。
        groups.clear()
        controller.handle(FailoverEvent.Tick)

        // 修正前: reconcileCandidateIdentity の group == null 分岐がそのまま
        // Idle を返し、disconnect() を一切呼んでいなかった。トンネルは生きた
        // まま「待機中」を騙ることになる。
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.Idle)
    }

    @Test
    fun `FailingOver 中にグループが丸ごと削除されても二重disconnectせずpendingConnectGroupIdを保持する`() {
        val g1 = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = true,
        )
        val g2 = FailoverGroup(
            id = "g2",
            name = "予備",
            memberUuids = listOf("uuid-x"),
            autoFailoverEnabled = true,
        )
        val (controller, groups) = controllerWith(g1, g2)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // ユーザーが別グループ(g2)を指示 -> FailingOver(g1) に入り、
        // pendingConnectGroupId = "g2" が立つ。
        controller.handle(FailoverEvent.UserConnectGroup("g2"))
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        // 確認が来る前に g1 自体が丸ごと削除される（g2 は残る）。
        groups.removeAll { it.id == "g1" }
        controller.handle(FailoverEvent.Tick)

        // この FailingOver は既に uuid-a 宛の disconnect() を要求済みなので、
        // ここで再び disconnectIfStillUp を呼んで二重要求してはならない。
        // まだ FailingOver のまま（pendingConnectGroupId="g2" を保持したまま）
        // であるべき。
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        // 遅れて確認が届く。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // pendingConnectGroupId が迷子になっていなければ g2 の先頭候補
        // （uuid-x）へ正しく進む。もしここで先回りして Idle に落としていたら
        // （F1 の一般規則をそのまま無条件適用した場合の副作用）、
        // pendingConnectGroupId の消費を経由せず g2 へは二度と進めなくなる。
        assertEquals(listOf("uuid-a", "uuid-x"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g2", (state as FailoverState.Connecting).groupId)
    }

    @Test
    fun `FailingOverのタイムアウトでExhaustedに入った直後のUserConnectGroupは2段階を経てから起動する`() {
        val g1 = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a"),
            autoFailoverEnabled = true,
        )
        val g2 = FailoverGroup(
            id = "g2",
            name = "予備",
            memberUuids = listOf("uuid-x"),
            autoFailoverEnabled = true,
        )
        val (controller, _) = controllerWith(g1, g2)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // プローブ失敗の閾値到達 -> 切断要求 -> FailingOver(awaiting uuid-a)。
        repeat(g1.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        // 確認が来ないまま DISCONNECT_WAIT_MS が経過し、諦めて先へ進む。
        // g1 は単独候補なので次候補が無く Exhausted へ落ちる。この時点で
        // uuid-a のスレッドが実際に終了した確認はまだ無い。
        clock.advance(3_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Exhausted)

        // ユーザーが別グループ(g2)を指示する。
        controller.handle(FailoverEvent.UserConnectGroup("g2"))

        // 修正前: 「Exhausted に至る前の切替はすべて2段階を経ているので待つべき
        // 相手が無い」という誤った前提のもと、ここで即座に vpn.connect("uuid-x")
        // を呼んでいた。修正後: 諦めた uuid-a の確認がまだ無いので、
        // Ruling 25 と同じ2段階（切断要求 -> 確認 -> 起動）を Exhausted からでも
        // 踏み、まだ uuid-x へは繋がない。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(2, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        // 諦めていた uuid-a の確認が遅れて届く。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // ここでようやく g2 の先頭候補へ進む。
        assertEquals(listOf("uuid-a", "uuid-x"), vpn.connectCalls)
        val state = controller.state
        assertTrue(state is FailoverState.Connecting)
        assertEquals("g2", (state as FailoverState.Connecting).groupId)
    }

    @Test
    fun `onAutoConnectはpendingConnectGroupIdを消すので古い保留先に奪われない`() {
        val g1 = FailoverGroup(id = "g1", name = "G1", memberUuids = listOf("uuid-a"), autoFailoverEnabled = true)
        val g2 = FailoverGroup(id = "g2", name = "G2", memberUuids = listOf("uuid-x"), autoFailoverEnabled = true)
        val g3 = FailoverGroup(id = "g3", name = "G3", memberUuids = listOf("uuid-p"), autoFailoverEnabled = true)
        val (controller, _) = controllerWith(g1, g2, g3)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // ユーザーが g2 を指示 -> FailingOver(g1), pendingConnectGroupId = "g2"。
        controller.handle(FailoverEvent.UserConnectGroup("g2"))
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        // 確認が来る前に、契約違反の呼び出し元（今日の FailoverService は
        // Idle からしか AutoConnectGroup を投げないが、将来増えたときの
        // 保険）を想定して直接 AutoConnectGroup を投げる。
        controller.handle(FailoverEvent.AutoConnectGroup("g3"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-p"))

        // onAutoConnect は現在の状態を見ずに即座に g3 の先頭候補へ接続する
        // （それ自体は別の、より大きな問題であり F6 の対象ではない）。
        assertEquals(listOf("uuid-a", "uuid-p"), vpn.connectCalls)
        val afterAuto = controller.state
        assertTrue(afterAuto is FailoverState.Connecting)
        assertEquals("g3", (afterAuto as FailoverState.Connecting).groupId)

        // g3 の唯一の候補が接続タイムアウトで諦められ、次候補が無いまま
        // 切替が起きる状況を作る。
        clock.advance(g3.config.connectTimeoutSec * 1_000L + 1_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(2, vpn.disconnectCalls)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-p"))

        // 修正前: pendingConnectGroupId に "g2" が残ったままだったので、ここで
        // advanceAfterFailingOver がそれを読み、g3 自身の巡回が終わっていない
        // のに無関係な g2（uuid-x）へ横取りされていた。
        // 修正後: onAutoConnect が pendingConnectGroupId を消しているので、g3
        // 自身の巡回が正しく完了し（候補は1件のみ）Exhausted に入る。
        assertTrue(controller.state is FailoverState.Exhausted)
        assertEquals("g3", (controller.state as FailoverState.Exhausted).groupId)
        assertEquals(listOf("uuid-a", "uuid-p"), vpn.connectCalls)
    }
}
