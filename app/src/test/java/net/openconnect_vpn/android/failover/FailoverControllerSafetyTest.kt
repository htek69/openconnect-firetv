package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailoverControllerSafetyTest {

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

    // --- S1: 認証失敗した候補は再試行しない ---

    @Test
    fun `認証中に切断された候補は除外され次候補へ進む`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `認証失敗した候補はセッション中スキップされる`() {
        // uuid-a を認証失敗させる
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        // uuid-b で繋がったあと障害 -> uuid-c へ。先頭に戻っても uuid-a は試されない
        controller.handle(FailoverEvent.UserDisconnect)
        vpn.connectCalls.size.let { /* 記録はそのまま */ }
        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        // 先頭候補 uuid-a は除外済みなので uuid-b から始まる
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `認証を通過した候補は除外されない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticated))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertFalse("uuid-a" in controller.excludedUuids)
    }

    // --- S2: 下層ネットワークが死んでいるときは切り替えない ---

    @Test
    fun `下層ネット断中はプローブを打たない`() {
        toHealthy()
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))

        clock.advance(31_000L)
        assertFalse(controller.shouldProbeNow())
    }

    @Test
    fun `下層ネット断中の切断では切替しない`() {
        toHealthy()
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        // 候補は消費されず、接続要求も増えない
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `下層ネット復帰で即プローブできる状態に戻る`() {
        toHealthy()
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))
        clock.advance(31_000L)
        assertFalse(controller.shouldProbeNow())

        network.available = true
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = true))

        assertTrue(controller.shouldProbeNow())
    }

    // --- S3: ユーザーの意図的な切断を障害と誤認しない ---

    @Test
    fun `ユーザー切断で Idle に入り自動切替が止まる`() {
        toHealthy()

        controller.handle(FailoverEvent.UserDisconnect)

        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(1, vpn.disconnectCalls)

        // 続いて届く Disconnected は障害扱いしない
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        assertTrue(controller.state is FailoverState.Idle)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    // --- VPN 許可未取得 ---

    @Test
    fun `VPN 許可が未取得なら Idle に留まりフラグが立つ`() {
        vpn.nextResult = ConnectResult.NeedsUserConsent

        controller.handle(FailoverEvent.UserConnectGroup("g1"))

        assertTrue(controller.state is FailoverState.Idle)
        assertTrue(controller.needsUserConsent)
    }

    // --- Ruling 13: S3 with UUID correlation ---

    @Test
    fun `切替前の古い Disconnected は新しい候補を除外しない`() {
        toHealthy()

        // uuid-a は Healthy。プローブ失敗で切替が始まる
        // （Ruling 25: まず切断を要求して FailingOver に入り、uuid-a の確認を待つ）
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)

        // uuid-a の確認が届いて uuid-b へ進む
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals("uuid-b", vpn.connectCalls.last())
        val callsBefore = vpn.connectCalls.size

        // uuid-a の Disconnected がもう一度、遅れて重複して届く（旧候補の古い通知）。
        // currentCandidateUuid は既に uuid-b を指しているので、Ruling 13 の
        // UUID 照合で弾かれなければならない。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // uuid-b は一度も試していない除外対象ではない。接続要求も増えない。
        assertFalse("uuid-b" in controller.excludedUuids)
        assertEquals(callsBefore, vpn.connectCalls.size)
    }

    // --- Ruling 14: S2 immediate probe on network recovery ---

    @Test
    fun `下層ネット復帰で間隔満了前でもプローブできる`() {
        toHealthy()
        val interval = group.config.probeIntervalSec * 1_000L

        // プローブ間隔より短い時間だけ進める（29秒、30秒以下）
        clock.advance(interval - 2_000L)
        assertFalse(controller.shouldProbeNow())

        // ネットが一度落ちて復帰する
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))
        assertFalse(controller.shouldProbeNow())

        network.available = true
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = true))

        // 間隔満了前だが、復帰フラグにより即座にプローブできる
        assertTrue(controller.shouldProbeNow())
        // 一度だけ消費される（再度呼ぶと false になる）
        assertFalse(controller.shouldProbeNow())
    }

    // --- Ruling 15: Recovery flag scoping ---

    @Test
    fun `復帰フラグは新しい候補が S4 猶予期間を通過するのを阻害しない`() {
        // 健全な状態から始める
        toHealthy()

        // ネットが一度落ちて復帰する。フラグが立つ。
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))
        network.available = true
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = true))
        // ここで shouldProbeNow() を呼ばない。フラグは消費されず、新しい候補に漏れる。

        // ユーザーが新しい接続を要求する
        controller.handle(FailoverEvent.UserDisconnect)
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))

        // Verifying 状態に到達
        assertTrue(controller.state is FailoverState.Verifying)

        // 猶予期間より短い時間だけ進める（14秒）
        val grace = group.config.graceAfterConnectSec * 1_000L
        clock.advance(grace - 1_000L)

        // 新しい候補は S4 期間中なので shouldProbeNow() は false のはずだが、
        // 復帰フラグが漏れていると true になってしまう。
        assertFalse(controller.shouldProbeNow())
    }

    private fun toHealthy() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
    }

    // --- Ruling 21: UserPrompt は「人が見ていない」候補でだけ認証失敗扱いする ---

    @Test
    fun `ユーザーが接続した候補が認証ダイアログで止まっても現状を維持する`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        val stateBefore = controller.state

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))

        assertEquals(stateBefore, controller.state)
        assertFalse("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `閾値超過で自動切替した候補が認証ダイアログで止まったら除外して次候補へ進む`() {
        toHealthy()
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        // Ruling 25: 切替1段階目。uuid-a の確認を待つ
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals("uuid-b", vpn.connectCalls.last())

        // uuid-b が認証ダイアログで止まる（人はいない）。
        // 裁定30: UserPrompt 自体は「人の入力が必要」を意味しない
        // （既存コアは自動入力でも必ず1回送るため）。USER_PROMPT_WAIT_MS を
        // 過ぎても次の状態へ進まなかった場合だけダイアログが出ているとみなし、
        // 除外して次候補へ進む。これも onUnattendedUserPrompt 経由の failOver
        // であり、まだ生きている可能性があるので Ruling 25 によりまず
        // 切断要求 -> FailingOver に入る。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(10_000L) // USER_PROMPT_WAIT_MS
        controller.handle(FailoverEvent.Tick)

        assertTrue("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.FailingOver)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))

        assertEquals("uuid-c", vpn.connectCalls.last())
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `枯渇後の再試行で開始した候補が認証ダイアログで止まったら除外して次候補へ進む`() {
        // uuid-a: Healthy に到達したあと閾値超過で失格する（S1 の除外対象ではない）
        toHealthy()
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        // Ruling 25: 確認ステップを挟んでから次候補へ進む
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())

        // uuid-b: 認証段階まで到達してから切断 -> Ruling 23 により S1 で除外
        // （Ruling 23 前は認証段階に到達したかどうかを見ていなかったため、
        // Authenticating を経ずに Disconnected を送るだけで除外されていた。
        // このテストの主眼は Ruling 21 の再試行時 UserPrompt 処理であり、
        // ここで uuid-b/uuid-c を除外させるのは「Exhausted へ落として
        // uuid-a だけが再試行される」状況を作るための前提設定に過ぎないので、
        // Authenticating を追加することはテストの弱体化ではない）
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-b"))
        assertEquals("uuid-c", vpn.connectCalls.last())

        // uuid-c も同様に認証段階まで到達してから切断 -> 除外。候補が尽きて Exhausted へ
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-c"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertTrue(controller.state is FailoverState.Exhausted)
        assertFalse("uuid-a" in controller.excludedUuids)
        assertTrue("uuid-b" in controller.excludedUuids)
        assertTrue("uuid-c" in controller.excludedUuids)

        // バックオフが明けて Tick が再試行する。除外されていない uuid-a から試す
        val exhausted = controller.state as FailoverState.Exhausted
        clock.advance(Backoff.delayMsForAttempt(exhausted.attempt) + 1_000L)
        controller.handle(FailoverEvent.Tick)
        assertEquals("uuid-a", vpn.connectCalls.last())

        // uuid-a が認証ダイアログで止まる。自動再試行なので人はいない。
        // 裁定30: USER_PROMPT_WAIT_MS を過ぎても次の状態へ進まなければ除外して進める。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))
        clock.advance(10_000L) // USER_PROMPT_WAIT_MS
        controller.handle(FailoverEvent.Tick)

        assertTrue("uuid-a" in controller.excludedUuids)
        // Ruling 25: まだ FailingOver（確認待ち）。確認できたら候補が尽きて Exhausted へ進む
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        // uuid-b, uuid-c も除外済みなので、候補が尽きて再び Exhausted へ進む
        assertTrue(controller.state is FailoverState.Exhausted)
    }

    // --- 裁定30: UserPrompt は「人が必要」を意味しない（欠陥14・実機で確定） ---

    @Test
    fun `自動入力で即座に Authenticating へ進む UserPrompt は候補を除外しない`() {
        // 欠陥14 の回帰テスト。実機では認証情報が完全に保存された健全な候補
        // （v-server）が起動から約1秒で除外されていた。既存コアは
        // onProcessAuthForm の冒頭で無条件に STATE_USER_PROMPT を送るため、
        // 保存済み認証情報による完全に自動的なログインでも UserPrompt が
        // 必ず観測され、その直後（ミリ秒単位）に Authenticating へ進む。
        // 修正前のコード（UserPrompt を観測した時点で除外）ならこの時点で
        // 焼き切られるので、このテストはその欠陥を確実に検出する。
        toHealthy()
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals("uuid-b", vpn.connectCalls.last())

        // 保存済み認証情報での自動ログイン: UserPrompt の直後（時刻を進めずに）
        // Authenticating へ進む。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-b"))

        // 十分に時間を進めてから Tick しても、除外も切替も起きてはならない
        // （Authenticating へ進んだ時点で userPromptSinceMs は解除済み）。
        clock.advance(10_000L * 2) // USER_PROMPT_WAIT_MS * 2
        controller.handle(FailoverEvent.Tick)

        assertFalse("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `UserPrompt のまま USER_PROMPT_WAIT_MS 経過したら除外して次候補へ進む`() {
        toHealthy()
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(10_000L) // USER_PROMPT_WAIT_MS
        controller.handle(FailoverEvent.Tick)

        assertTrue("uuid-b" in controller.excludedUuids)
        // Ruling 25 により切替は2段階。ここでは FailingOver に入る（次候補はまだ起動しない）。
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    @Test
    fun `USER_PROMPT_WAIT_MS 未満では除外しない`() {
        toHealthy()
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(9_999L) // USER_PROMPT_WAIT_MS - 1
        controller.handle(FailoverEvent.Tick)

        assertFalse("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    @Test
    fun `ユーザー自身が接続した候補は UserPrompt で止まっても除外されない`() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))

        // ブリーフは USER_PROMPT_WAIT_MS * 10 を指定しているが、それは
        // connectTimeoutSec（既定45秒）を超えてしまい、UserPrompt とは無関係の
        // Ruling 22a の接続タイムアウトで切り替わってしまう（この経路は除外は
        // しないが、状態が Connecting のままではなくなる）。この事故を避けつつ
        // 「時間が経っても UserPrompt 由来では何も起きない」ことを検証するため、
        // connectTimeoutSec 未満に収まる USER_PROMPT_WAIT_MS * 2 を使う。
        clock.advance(10_000L * 2) // USER_PROMPT_WAIT_MS * 2
        controller.handle(FailoverEvent.Tick)

        assertFalse("uuid-a" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    @Test
    fun `多段フォームの UserPrompt は毎回リセットされる`() {
        toHealthy()
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())

        // 1段目のフォーム
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(9_999L) // USER_PROMPT_WAIT_MS - 1

        // 1段目のフォームが終わり（Authenticating）、2段目のフォームが表示される
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
        clock.advance(9_999L) // USER_PROMPT_WAIT_MS - 1
        controller.handle(FailoverEvent.Tick)

        // 合計では USER_PROMPT_WAIT_MS を超えているが、2回目のフォームには
        // 自分の猶予があるので除外されない。
        assertFalse("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.Connecting)
    }

    // --- Ruling 23: 認証段階に到達したかどうかで S1 の除外判定を分ける ---

    @Test
    fun `認証段階に到達してから切断された候補は除外され次候補へ進む`() {
        // S1 の本来の対象ケース。Ruling 23 の前後どちらでも除外されなければならない。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `認証段階に到達しないまま切断された候補は除外されないが次候補へ進む`() {
        // 実機で確認された不具合そのもの。10_255_255_1 のような到達不能ホストは
        // TLS 接続すら成立せず Authenticating に届かないまま Disconnected が来る。
        // これはネットワーク障害であり認証失敗ではないので除外してはならない。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertFalse("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `全候補が認証前に切断してもバックオフ後は全候補から再試行できる`() {
        // 実機のログ: Connecting idx=0,1,2 が全て認証前に切断し、3件とも除外され、
        // Exhausted の再試行が毎回全滅して attempt が伸び続けバックオフが
        // 10分の上限へ張り付いた。グループが恒久的に死ぬ不具合そのもの。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        repeat(3) {
            controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        }

        assertTrue(controller.state is FailoverState.Exhausted)
        assertTrue(controller.excludedUuids.isEmpty())

        // バックオフが明けて Tick が再試行する。除外が空のままなので先頭候補から
        val exhausted = controller.state as FailoverState.Exhausted
        clock.advance(Backoff.delayMsForAttempt(exhausted.attempt) + 1_000L)
        controller.handle(FailoverEvent.Tick)

        assertEquals("uuid-a", vpn.connectCalls.last())
        assertTrue(controller.excludedUuids.isEmpty())
    }
}
