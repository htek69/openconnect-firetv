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

    /**
     * 裁定86（H2）でこのテストを書き直した。
     *
     * 以前の版は「`onAutoConnect` は現在の状態を見ずに即座に接続する（それ自体は
     * 別の、より大きな問題）」というコメントを添えて、**その誤った挙動を期待値
     * として固定していた**（`FailingOver` の確認待ち中に `AutoConnectGroup` が
     * 割り込んで `vpn.connect()` まで走ることを assert していた）。
     *
     * 新しい契約: 候補の起動を始める3経路（`onUserConnect` / `onAutoConnect` /
     * `onExhaustedRetry`）はすべて共通の前処理を通り、まだ生きている可能性の
     * ある候補があるうちは Ruling 25 の2段階を必ず経る。`onAutoConnect` も
     * 例外ではない。
     *
     * F6（保留先を持ち越さない）の性質はこの契約のもとでも保たれる: 割り込んだ
     * `AutoConnectGroup` は保留先を自分（g3）に**差し替える**ので、古い g2 が
     * 後から横取りすることは無い。それをこのテストの後半で確認する。
     */
    @Test
    fun `FailingOver中のAutoConnectGroupは割り込まず保留先を差し替えてから2段階を経る`() {
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

        // ユーザーが g2 を指示 -> FailingOver(g1), 保留先 = g2。
        controller.handle(FailoverEvent.UserConnectGroup("g2"))
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        // 確認が来る前に、契約違反の呼び出し元（今日の FailoverService は
        // Idle からしか AutoConnectGroup を投げないが、将来増えたときの
        // 保険）を想定して直接 AutoConnectGroup を投げる。
        controller.handle(FailoverEvent.AutoConnectGroup("g3"))

        // 修正前はここで即座に vpn.connect("uuid-p") が走っていた（uuid-a の
        // スレッドが生きているかもしれないのに、新しい候補を起動していた）。
        // 修正後は割り込まない: disconnect() の再送も startedAtMs の更新も
        // せず（裁定26）、保留先だけが g3 に差し替わる。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(1, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        // 待っていた uuid-a の切断確認が届く -> 差し替えた保留先（g3）へ進む。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals(listOf("uuid-a", "uuid-p"), vpn.connectCalls)
        val afterAuto = controller.state
        assertTrue(afterAuto is FailoverState.Connecting)
        assertEquals("g3", (afterAuto as FailoverState.Connecting).groupId)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-p"))

        // g3 の唯一の候補が接続タイムアウトで諦められ、次候補が無いまま
        // 切替が起きる状況を作る。
        clock.advance(g3.config.connectTimeoutSec * 1_000L + 1_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(2, vpn.disconnectCalls)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-p"))

        // F6 の性質: 古い保留先 g2（uuid-x）に横取りされない。g3 自身の巡回が
        // 正しく完了し（候補は1件のみ）Exhausted に入る。
        assertTrue(controller.state is FailoverState.Exhausted)
        assertEquals("g3", (controller.state as FailoverState.Exhausted).groupId)
        assertEquals(listOf("uuid-a", "uuid-p"), vpn.connectCalls)
    }

    // --- 裁定86（H2）: 放棄候補の確認を3経路で共有する ---

    /**
     * H2 の失敗シナリオそのもの（実運用で踏める経路）:
     *
     * 1. `Healthy(A)` からプローブ失敗で `FailingOver(awaiting=A)`。
     * 2. A の確認が来ないまま `DISCONNECT_WAIT_MS` を過ぎ、諦めて次候補 B へ
     *    （`unconfirmedAbandonedUuid = A` が残る）。
     * 3. TV の画面が消える（裁定44 で `UserDisconnect` → `Idle`）。
     * 4. TV の画面が点く（`AutoConnectGroup`）。
     *
     * 修正前はここで A の生存を確認せずに先頭候補（= A）へ即 `vpn.connect()` を
     * 呼んでいた。修正後は `unconfirmedAbandonedUuid` を見て2段階を経る。
     */
    @Test
    fun `画面点灯時のAutoConnectGroupは放棄候補の切断確認を先に待つ`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = true,
        )
        val (controller, _) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // プローブ失敗の閾値到達 -> FailingOver(awaiting uuid-a)。
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)

        // 確認が来ないまま諦めて次候補 uuid-b へ。uuid-a の生存は未確認のまま。
        clock.advance(3_000L)
        controller.handle(FailoverEvent.Tick)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // 画面が消える（裁定44）。
        controller.handle(FailoverEvent.UserDisconnect)
        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(2, vpn.disconnectCalls)

        // 画面が点く。FailoverService は Idle なので AutoConnectGroup を投げる。
        controller.handle(FailoverEvent.AutoConnectGroup("g1"))

        // 修正前: ここで即 vpn.connect("uuid-a")（3件目の connect）が走っていた。
        // 修正後: uuid-a の確認が無いので、まず切断を要求して待つ。
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
        assertEquals(3, vpn.disconnectCalls)
        val failingOver = controller.state
        assertTrue(failingOver is FailoverState.FailingOver)
        assertEquals("uuid-a", (failingOver as FailoverState.FailingOver).awaitingUuid)

        // 確認が届いてから先頭候補を起動する。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals(listOf("uuid-a", "uuid-b", "uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    /**
     * 裁定86（H2）: 上のシナリオで起動される候補が**無人**のままであること。
     *
     * 保留（`PendingConnect`）が有人・無人を運ばないと、`AutoConnectGroup` から
     * 保留された接続が `advanceAfterFailingOver` で有人として起動してしまい、
     * 裁定30 の「`UserPrompt` から進まなければ除外して次へ」が働かなくなる
     * （裁定36 が防いでいる事故: 無人の TV が認証ダイアログの前で無期限に止まる）。
     */
    @Test
    fun `放棄候補を待ってから起動した候補も無人のままなのでUserPromptで除外される`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b"),
            autoFailoverEnabled = true,
        )
        val (controller, _) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        clock.advance(3_000L)
        controller.handle(FailoverEvent.Tick)
        // uuid-b へ進んだが uuid-a は未確認のまま。
        // 裁定86（L1）: uuid-b の Connecting を実機同様に観測させる。これを
        // 省くと sawCoreConnecting が false のままになり、あとで待つ uuid-a の
        // Disconnected が裁定31a のガードに捨てられる（3秒のタイムアウト待ちに
        // なる。詳細は sawCoreConnecting の KDoc）。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        // そのまま画面が消えて点く。
        controller.handle(FailoverEvent.UserDisconnect)
        controller.handle(FailoverEvent.AutoConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // 保留から起動された uuid-a。認証フォームでダイアログが出て止まる。
        assertEquals(listOf("uuid-a", "uuid-b", "uuid-a"), vpn.connectCalls)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))
        clock.advance(11_000L)
        controller.handle(FailoverEvent.Tick)

        // 無人として起動されていれば、裁定30 により除外されて次候補へ向かう。
        // 有人（unattended = false）で起動していると userPromptSinceMs が
        // 立たないため、ここで何も起きない。
        assertTrue(controller.excludedUuids.contains("uuid-a"))
    }

    /**
     * 裁定86（H2）: 3経路目（`onExhaustedRetry`）。バックオフ満了の自動再試行も
     * 放棄候補の確認を先に済ませる。
     */
    @Test
    fun `枯渇後の自動再試行も放棄候補の切断確認を先に待つ`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a"),
            autoFailoverEnabled = true,
        )
        val (controller, _) = controllerWith(group)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        // プローブ失敗 -> FailingOver -> 確認が来ないまま諦める。単独候補なので
        // 次候補が無く Exhausted へ（uuid-a は未確認のまま）。
        repeat(group.config.failureThreshold) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        clock.advance(3_000L)
        controller.handle(FailoverEvent.Tick)
        val exhausted = controller.state
        assertTrue(exhausted is FailoverState.Exhausted)
        assertEquals(1, vpn.disconnectCalls)

        // バックオフ満了。
        clock.now = (exhausted as FailoverState.Exhausted).retryAtMs
        controller.handle(FailoverEvent.Tick)

        // 修正前: ここで即 vpn.connect("uuid-a") を呼び直していた。
        // 修正後: まず切断を要求して確認を待つ。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(2, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals(listOf("uuid-a", "uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Connecting)
    }
}
