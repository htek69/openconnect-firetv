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
    private var dialogHostAttached = false

    /**
     * 統合レビューの所見5: これまでこのスイートは `needsFirstLoginProvider` を
     * 渡していなかった（既定の `{ false }`）ため、**両機能を同時に動かすテストが
     * 1件も無かった。** 既定は空集合＝誰も飛ばさないので、既存のテストの振る舞いは
     * 無改変である。
     */
    private var needsFirstLogin: Set<String> = emptySet()

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
        dialogHostAttached = false
        needsFirstLogin = emptySet()
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            dialogHostAttachedProvider = { dialogHostAttached },
            needsFirstLoginProvider = { uuid -> uuid in needsFirstLogin },
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

    /** `FailingOver` から [uuid] の切断確認を与えて次候補を `Healthy` まで進める。 */
    private fun confirmDisconnectAndBecomeHealthy(failed: String, next: String) {
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = failed))
        assertEquals(next, vpn.connectCalls.last())
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = next))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = next))
        clock.advance(16_000L)
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
        // Task 7 用: 速度起因の切替であることが状態に残る。
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

    /**
     * 仕様書 4-4: 接続直後の猶予中は速度で切り替えない。
     *
     * **`Healthy` のまま猶予の内側にいる状態**を作る必要がある（`Verifying` の
     * tick には速度の判定が無いので、そこで tick してもこの規則を検査できない）。
     * 実運用では `shouldProbeNow()` が猶予明けまでプローブを許さないため
     * `Healthy` へ入るのは猶予明け後だが、S2/S3/S4 と同じく**呼び出し側の規律
     * だけに頼らず状態機械自身でも猶予を確かめる**ことをここで固定する。
     */
    @Test
    fun `接続直後の猶予中は切り替えない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        controller.handle(FailoverEvent.ProbeResult(reachable = true)) // 猶予明け前に Healthy へ
        assertTrue(controller.state is FailoverState.Healthy)

        slow = true
        clock.advance(5_000L) // 猶予 15 秒の内側
        val before = vpn.connectCalls.size
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(before, vpn.connectCalls.size)
    }

    /**
     * 裁定94: セッション中の再認証でコアが `UserPrompt` に入っても状態は `Healthy`
     * のままである。人が答えられる（描画先がある）あいだは、遅くても切り替えない
     * ——切り替えるとダイアログごと消え、**正しいパスワードを打っている最中に
     * 接続を切られる。**
     */
    @Test
    fun `人が認証ダイアログに答えている最中は遅くても切り替えない`() {
        toHealthyOnA()
        slow = true
        dialogHostAttached = true
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))
        assertTrue(controller.state is FailoverState.Healthy)

        val before = vpn.connectCalls.size
        clock.advance(60_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(before, vpn.connectCalls.size)

        // 描画先が離れれば（利用者が TV 画面から離れた）従来どおり切り替わる。
        // 有界性は時間ではなく「人が居る」という観測そのものに拠る。
        dialogHostAttached = false
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    /**
     * 仕様書 4-4: 次に起動できる候補が無いなら、遅くても切り替えない。
     * 行き先の無い切替は `Exhausted` のバックオフに落ち、**繋がってはいた
     * （ただ遅い）トンネルを失う**——遅いままより悪い。
     *
     * 死活起因の切替で最後の候補まで来た状態を作る（速度の予算は使っていない）。
     */
    @Test
    fun `次に起動できる候補が無いなら遅くても切り替えない`() {
        toHealthyOnA()
        repeat(3) { // failureThreshold: 死活で uuid-b へ移る
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        confirmDisconnectAndBecomeHealthy(failed = "uuid-a", next = "uuid-b")

        slow = true
        val before = vpn.connectCalls.size
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(before, vpn.connectCalls.size)
    }

    /**
     * 統合レビューの §8 テスト1（最重要）: **両機能を同時に動かす唯一のテスト。**
     *
     * 速度起因の切替は**エンジン自身の判断**（人の操作ではない）なので、初回
     * ログインが未了のメンバー——認証情報が保存されておらず、人が居なければ
     * 絶対に成功しない相手——を行き先に数えてはならない。数えてしまうと
     * `onSlowLinkSwitch` の行き先の門を通り抜けた切替が `startCandidateFrom`
     * （そちらは `unattended = true`）でその候補を飛ばし、結局 `Exhausted` に落ちて
     * **繋がってはいた（ただ遅い）トンネルをバックオフのあいだ失う。** 加えて、
     * 無人で起動できない候補へ移そうとすること自体が裁定93/94 の
     * 「利用者の認証ダイアログを畳まない」に反する。
     *
     * 固定する不変条件は `onSlowLinkSwitch` の行き先の門を
     * **`unattended = true` で引くこと**である。`false` に変えても（門を消しても）
     * 既存のどのテストも落ちなかった。ここがその唯一の守り手になる。
     */
    @Test
    fun `初回ログインが未了のメンバーしか残っていなければ遅くても切り替えない`() {
        needsFirstLogin = setOf("uuid-b")
        toHealthyOnA()
        // 起動したのは uuid-a の1回だけで、切断はまだ一度も要求していない
        // （Idle から始めたので Ruling 25 の切断待ちを踏んでいない）。
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(0, vpn.disconnectCalls)

        slow = true
        repeat(5) {
            clock.advance(1_000L) // TICK_INTERVAL_SWITCHING_MS 相当
            controller.handle(FailoverEvent.Tick)
        }

        // 何度打っても切替は始まらない。uuid-b は「起動できる候補」に数えられない。
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals("遅い切替のために切断を要求してはならない", 0, vpn.disconnectCalls)
        assertEquals("新しい候補を起動してはならない", listOf("uuid-a"), vpn.connectCalls)
        // 飛ばしは S1 の除外とは別物なので、集合には何も足さない。
        assertFalse("uuid-b" in controller.excludedUuids)

        // 止めていたのが供給関数（初回ログインの飛ばし）であって別の門ではない
        // ことを示す: 利用者が初回ログインを終えたら、次の tick で切替が起きる。
        needsFirstLogin = emptySet()
        controller.handle(FailoverEvent.Tick)
        assertTrue((controller.state as FailoverState.FailingOver).bySlowLink)
        assertEquals(1, vpn.disconnectCalls)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    /**
     * 速度起因の切替を一周ぶん（候補2件なら1回）行い、uuid-b の `Healthy` で終わる。
     * この時点で予算は使い切っている。
     */
    private fun lapOnceBySlowness() {
        toHealthyOnA()
        slow = true
        controller.handle(FailoverEvent.Tick)
        assertTrue((controller.state as FailoverState.FailingOver).bySlowLink)
        confirmDisconnectAndBecomeHealthy(failed = "uuid-a", next = "uuid-b")
    }

    /**
     * 一周ぶんを使い切ったあと、**切替先のある** `Healthy`（先頭候補）へ戻す。
     * 予算だけが切替を止めていることを検査できる形にするためのヘルパで、戻り道は
     * 死活起因の切替 → 枯渇 → バックオフ満了の再試行という既存の経路を使う
     * （速度の予算には触れない経路である）。
     */
    private fun backToHealthyOnAWithLapSpent() {
        repeat(3) { // 死活で uuid-b を諦める
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))
        assertTrue(controller.state is FailoverState.Exhausted) // 後続の候補が無い

        clock.advance(31_000L) // Backoff.delayMsForAttempt(0) = 30 秒を越える
        controller.handle(FailoverEvent.Tick) // 枯渇後の再試行 → uuid-a を起動
        assertEquals("uuid-a", vpn.connectCalls.last())
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `一周したら速度による切替を止める`() {
        lapOnceBySlowness()
        // 切替先（uuid-b）はあり、除外もされていない。止めているのは予算だけ。
        backToHealthyOnAWithLapSpent()

        val before = vpn.connectCalls.size
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(before, vpn.connectCalls.size)
    }

    @Test
    fun `ユーザー操作の接続で一周の記録が解除される`() {
        lapOnceBySlowness()

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        // Ruling 25: 生きている候補があるので、まず切断を待ってから起動される。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))
        assertEquals("uuid-a", vpn.connectCalls.last())
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))

        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    /** 仕様書 4-5: 本機能の設定が変わったときも一周の記録を解除する。 */
    @Test
    fun `設定の変更で一周の記録が解除される`() {
        lapOnceBySlowness()
        backToHealthyOnAWithLapSpent()

        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy) // 予算切れ

        controller.onSlowLinkSettingsChanged()
        controller.handle(FailoverEvent.Tick)
        assertTrue((controller.state as FailoverState.FailingOver).bySlowLink)
    }

    @Test
    fun `死活による切替は一周後も従来どおり働く`() {
        lapOnceBySlowness()
        repeat(3) { // failureThreshold
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        val failingOver = controller.state as FailoverState.FailingOver
        // 死活起因の切替なので、速度起因の印は付かない。
        assertFalse(failingOver.bySlowLink)
    }
}
