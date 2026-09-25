package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 裁定93: 「人が認証ダイアログに答えられる状態か」を裁定30 の除外判定の条件に
 * 加えたことを固定する。
 *
 * 利用者の報告（削除→再登録した接続先でパスワードを聞かれない）の2つ目の原因。
 * 裁定92 でダイアログが実際に出せるようになると、`onUnattendedPromptTimeout` が
 * `UserPrompt` から10秒で候補を除外して切り替えるため、**利用者が入力している
 * 最中にダイアログごと消える。** 裁定30 の KDoc は最初から「無人運用の候補が…
 * 誰も答えられないので」と書いていたのに、実装は有人かどうかを見ていなかった。
 *
 * S1（認証失敗した候補を自動再試行から外す）は壊さない: 変えたのは
 * 「人が答えられるのに勝手に諦める」経路だけで、誰も見ていないときの除外は
 * 従来どおりである。
 */
class FailoverControllerDialogHostTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    /** 供給関数が返す値。テストの途中で切り替えられる。 */
    private var dialogHostAttached = false

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
        dialogHostAttached = false
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            dialogHostAttachedProvider = { dialogHostAttached },
        )
    }

    /**
     * uuid-b（無人運用で開始した候補）が `UserPrompt` で止まっている状態を作る。
     *
     * 裁定30 の除外は「人が見ている保証が無い」候補（Ruling 21 の
     * `currentCandidateUnattended`）にしか適用されないため、ユーザーが押した接続
     * （`UserConnectGroup`）の第1候補では除外判定が走らない。走る形＝自動切替で
     * 到達した第2候補を、実機と同じ順序（Healthy → プローブ失敗 → FailingOver →
     * 切断確認 → 次候補）で作る。
     *
     * 裁定94 の注: 絞り込みの場所は `userPromptSinceMs` の arming から
     * `onUnattendedPromptTimeout` へ移った（有人候補でも arming されるようになった）。
     * このヘルパが作る状態と、この後の除外の有無は変わらない。
     *
     * 呼び出し後の時刻 = uuid-b の `Connecting.startedAtMs` = `UserPrompt` 受信時刻
     * （以降 `clock.advance` した分がそのまま両方の経過時間になる）。
     */
    private fun toUnattendedUserPromptOnCandidateB() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected))
        clock.advance(16_000L) // graceAfterConnectSec を越える
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        repeat(3) { // failureThreshold
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))
    }

    // --- 1: 現行の回帰（誰も見ていないときの除外は従来どおり） ---

    @Test
    fun `供給関数が false のとき UserPrompt から10秒で候補が除外される`() {
        toUnattendedUserPromptOnCandidateB()

        dialogHostAttached = false
        clock.advance(10_000L) // USER_PROMPT_WAIT_MS（private companion なので実数で書く）
        controller.handle(FailoverEvent.Tick)

        assertTrue("uuid-b" in controller.excludedUuids)
        // Ruling 25 により切替は2段階。ここでは FailingOver に入る。
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    // --- 2: 人が答えられる間は諦めない ---

    @Test
    fun `供給関数が true の間は10秒経っても除外されない`() {
        toUnattendedUserPromptOnCandidateB()
        dialogHostAttached = true

        clock.advance(10_000L) // USER_PROMPT_WAIT_MS（private companion なので実数で書く）
        controller.handle(FailoverEvent.Tick)

        assertFalse("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.Connecting)

        // さらに待っても（接続タイムアウト未満の範囲では）何も起きない。
        // 10秒 + 20秒 = 30秒 < connectTimeoutSec（既定45秒）。
        clock.advance(20_000L)
        controller.handle(FailoverEvent.Tick)

        assertFalse("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    // --- 3: 無限に待つ状態は作らない（Ruling 22 が上限を持ち続ける） ---

    /**
     * **裁定94 で書き換えたテストである。**
     *
     * 元の主張は「供給関数が true でも `Connecting` の接続タイムアウトは従来どおり
     * 効く」だった。裁定94 はまさにそれを止める裁定であり（人が入力している最中に
     * 45秒で畳むのは Fire TV の D-pad 入力に対して短すぎる）、主張はもう成り立たない。
     * 裁定93 が言いたかったこと——「`Connecting` で null を返しても無期限にはならない。
     * 上限は別に存在する」——を、裁定94 のもとで成り立つ形に直したのがこのテスト。
     * 上限が復活する条件そのものは `FailoverControllerHumanAuthInputTest` が固定する。
     */
    @Test
    fun `供給関数が true でも UserPrompt を抜けたあとは接続タイムアウトが効く`() {
        toUnattendedUserPromptOnCandidateB()
        dialogHostAttached = true
        // ヘルパの中で Healthy → FailingOver の切替が1回 disconnect している。
        val disconnectsBefore = vpn.disconnectCalls

        // 裁定94: `UserPrompt` で止まっている間は畳まれない。
        clock.advance(group.config.connectTimeoutSec * 1_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Connecting)

        // コアが `UserPrompt` を抜けた（保存済み認証情報での自動入力が終わった、
        // または人が答えた）。ここで裁定94 の起点の置き直しが1回だけ起きる。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-b"))

        // 置き直した起点からさらに connectTimeoutSec 沈黙すれば従来どおり畳む。
        clock.advance(group.config.connectTimeoutSec * 1_000L)
        controller.handle(FailoverEvent.Tick)

        // 前進している（無期限に留まらない）
        assertTrue(controller.state is FailoverState.FailingOver)
        // 接続タイムアウトは認証失敗ではないので候補は除外しない（Ruling 22a）。
        // これが裁定93 の「null を返しても安全」の根拠でもある: 除外を伴わない
        // 上限に受け渡すので、アカウントロックを招く経路は増えない。
        assertFalse("uuid-b" in controller.excludedUuids)
        // Ruling 25: 生きている可能性のある候補を止めてから次へ進む
        assertEquals(disconnectsBefore + 1, vpn.disconnectCalls)
    }

    // --- 4: 人が離れたら従来の扱いに戻る ---

    @Test
    fun `供給関数が true から false に変わったら以後は除外されるようになる`() {
        toUnattendedUserPromptOnCandidateB()
        dialogHostAttached = true

        clock.advance(10_000L) // USER_PROMPT_WAIT_MS（private companion なので実数で書く）
        controller.handle(FailoverEvent.Tick)
        assertFalse("uuid-b" in controller.excludedUuids)

        // 利用者が TV 画面から離れた（onPause）。待ち時間は既に過ぎているので、
        // 時刻を進めずとも次の tick で除外される
        // （＝「ダイアログを出したまま人が居なくなった」状況）。
        dialogHostAttached = false
        controller.handle(FailoverEvent.Tick)

        assertTrue("uuid-b" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    // --- 5: S1 は供給関数の値に関わらず効く ---

    @Test
    fun `S1 認証失敗による除外は供給関数が true でも効く`() {
        dialogHostAttached = true

        // Ruling 23: 認証段階に到達していながら通過せずに切断された＝認証失敗。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `S1 認証失敗による除外は供給関数が false でも従来どおり効く`() {
        dialogHostAttached = false

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    // --- 補強: Healthy で武装した場合も無期限にはならない ---

    @Test
    fun `供給関数が true で Healthy に UserPrompt が来てもプローブ失敗での切替は効く`() {
        // 裁定93 の「無限に待つ状態を作らない」の Healthy 側の根拠。
        // onUnattendedPromptTimeout が null を返す → onTick の Healthy 分岐は
        // s を返すだけ。上限は onProbeResult の failureThreshold が持つ。
        toUnattendedUserPromptOnCandidateB()
        dialogHostAttached = true

        // uuid-b が接続を完了して Healthy になったあと、再認証フォームなどで
        // UserPrompt が届いて武装したままになる状況を作る。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-b"))

        // 待ち時間を大きく超えても Healthy のまま（除外も切替も起きない）
        clock.advance(10_000L * 10) // USER_PROMPT_WAIT_MS * 10
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Healthy)
        assertFalse("uuid-b" in controller.excludedUuids)

        // しかし疎通が失われれば failureThreshold 回で切り替わる（＝有界）
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        assertTrue(controller.state is FailoverState.FailingOver)
    }
}
