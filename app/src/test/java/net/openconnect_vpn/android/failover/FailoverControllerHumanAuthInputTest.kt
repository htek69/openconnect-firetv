package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 裁定94: **人が入力している最中に接続タイムアウト（Ruling 22）で畳まないこと。**
 *
 * 裁定93 は `UserPrompt` の10秒（除外を伴う判定）を止めたが、`Connecting` は
 * Ruling 22 の `onConnectTimeout`（既定45秒、`Connecting.startedAtMs` 起点）で
 * 依然として畳める。Fire TV の D-pad ＋ソフトキーボードでユーザー名とパスワードを
 * 打つのに45秒は足りない。Ruling 22 が上限を課すのは「死んだサーバの前で永久に
 * 固まらない」ためであり、人が入力欄の前にいる状態は「固まっている」ではない。
 *
 * 止める条件は2つの**論理積**である:
 * 1. `dialogHostAttachedProvider`（裁定93）= ダイアログの描画先が bind されている
 * 2. `userPromptSinceMs` が非 null = コアが `UserPrompt` で止まっている
 *
 * このクラスは「両方揃っている間だけ止まる」「片方でも欠ければ従来の上限が効く」
 * 「S1 は壊れない」を固定する。
 */
class FailoverControllerHumanAuthInputTest {

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

    private val connectTimeoutMs: Long get() = group.config.connectTimeoutSec * 1_000L

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
     * 実機で利用者が最初に踏む形: TV 画面の前で「接続」を押し（＝有人。
     * `UserConnectGroup`）、認証フォームのダイアログが出たところ。
     *
     * 呼び出し後の時刻 = `Connecting.startedAtMs` = `UserPrompt` 受信時刻。
     */
    private fun userStartedAndDialogIsUp() {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))
        assertTrue(controller.state is FailoverState.Connecting)
    }

    // --- ブリーフ 1: 両方揃っている間は connectTimeoutSec を過ぎても Connecting ---

    @Test
    fun `供給関数 true かつ UserPrompt 到達中は connectTimeoutSec を過ぎても Connecting のまま`() {
        userStartedAndDialogIsUp()
        dialogHostAttached = true

        // D-pad ＋ソフトキーボードでの入力に45秒では足りない。3分打っていても畳まない。
        clock.advance(connectTimeoutMs * 4)
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.Connecting)
        // 候補を替えていない＝入力中のダイアログの持ち主が生きたまま
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertEquals(0, vpn.disconnectCalls)
        assertTrue(controller.excludedUuids.isEmpty())

        // tick を何度重ねても同じ
        repeat(5) {
            clock.advance(connectTimeoutMs)
            controller.handle(FailoverEvent.Tick)
        }
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }

    /**
     * 同じことを無人経路（自動切替で到達した候補）でも確認する。
     * 裁定94 の止め方は有人・無人を問わない——止める根拠は「描画先があり、かつ
     * コアが `UserPrompt` で止まっている」という観測だけである
     * （実機では裁定44 により画面点灯で `AutoConnectGroup` が飛ぶので、
     * 利用者が目の前にいても候補は無人として始まりうる）。
     */
    @Test
    fun `無人で始まった候補でも供給関数 true かつ UserPrompt 到達中なら Connecting のまま`() {
        dialogHostAttached = true
        controller.handle(FailoverEvent.AutoConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))

        clock.advance(connectTimeoutMs * 2)
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
        assertTrue(controller.excludedUuids.isEmpty())
    }

    // --- ブリーフ 2: UserPrompt に到達していなければ従来どおり接続タイムアウト ---

    @Test
    fun `供給関数 true でも UserPrompt に到達していなければ従来どおり接続タイムアウトする`() {
        // 画面は開いているが、相手が沈黙していて認証フォームすら返ってこない
        // （ブラックホール宛先。Ruling 22 が存在する理由そのもの）。
        dialogHostAttached = true
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))

        clock.advance(connectTimeoutMs)
        controller.handle(FailoverEvent.Tick)

        // Ruling 25 の2段階切替に入っている＝前進した
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)
        // Ruling 22a: タイムアウトはネットワーク障害であり認証失敗ではない
        assertFalse("uuid-a" in controller.excludedUuids)
    }

    // --- ブリーフ 3: 供給関数が false なら従来どおり ---

    @Test
    fun `UserPrompt 到達中でも供給関数 false なら従来どおり接続タイムアウトする`() {
        // 有人で始まった候補（裁定30 の10秒の除外は対象外）。描画先が無いので
        // ダイアログは誰にも見えていない（Fire TV には通知シェードも無い）。
        userStartedAndDialogIsUp()
        dialogHostAttached = false

        clock.advance(connectTimeoutMs)
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)
        assertFalse("uuid-a" in controller.excludedUuids)
    }

    @Test
    fun `UserPrompt 到達中でも供給関数 false なら無人候補は裁定30 の10秒で除外される`() {
        // 無人経路。こちらは接続タイムアウト（45秒）より先に裁定30 の10秒が効く
        // （裁定33 のクランプ）。裁定94 はこの順序を変えていない。
        dialogHostAttached = false
        controller.handle(FailoverEvent.AutoConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.UserPrompt, uuid = "uuid-a"))

        clock.advance(10_000L) // USER_PROMPT_WAIT_MS（private companion なので実数で書く）
        controller.handle(FailoverEvent.Tick)

        assertTrue("uuid-a" in controller.excludedUuids)
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    /**
     * 供給関数が true → false に落ちた時点で、止めていた上限が復活する
     * （＝「ダイアログを出したまま人が居なくなった」）。
     *
     * この経路では起点の置き直しは起きない（置き直しの条件に供給関数が入っている）
     * ので、既に期限を過ぎていれば時刻を進めずとも次の tick で前進する。
     */
    @Test
    fun `止めている間に描画先が離れたら次の tick で接続タイムアウトが効く`() {
        userStartedAndDialogIsUp()
        dialogHostAttached = true

        clock.advance(connectTimeoutMs * 2)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(0, vpn.disconnectCalls)

        // 利用者が TV 画面から離れた（onPause）。時刻は進めない。
        dialogHostAttached = false
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)
        assertFalse("uuid-a" in controller.excludedUuids)
    }

    // --- ブリーフ 4: UserPrompt が解けたら以後の上限は従来どおり効く ---

    @Test
    fun `止めている間に人が答えたら以後はサーバの沈黙に対して従来どおり上限が効く`() {
        userStartedAndDialogIsUp()
        dialogHostAttached = true

        // 90秒かけて打った（既定45秒を大きく超えている）
        clock.advance(90_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Connecting)

        // `OK` を押した → コアが `Authenticating` へ進む。
        // 裁定94: ここで起点を「答え終わった瞬間」へ置き直すので、打っていた90秒を
        // 理由に即座に畳むことはしない（打ち終えた直後に切られては意味が無い）。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-a"))
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals(0, vpn.disconnectCalls)

        // ただし上限そのものは生きている: 答えたあとサーバが connectTimeoutSec
        // 沈黙すれば従来どおり畳む（＝無限に待つ状態は作っていない）。
        clock.advance(connectTimeoutMs)
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals(1, vpn.disconnectCalls)
        assertFalse("uuid-a" in controller.excludedUuids)
    }

    /**
     * 起点の置き直しは「人が答えた」観測1回につき1回だけである。
     * 供給関数が false のまま `UserPrompt` を抜けた場合は置き直さない
     * （誰も答えていないので、打っていた時間を差し引く理由が無い）。
     */
    @Test
    fun `供給関数 false のまま UserPrompt を抜けた場合は起点を置き直さない`() {
        userStartedAndDialogIsUp()
        dialogHostAttached = false

        clock.advance(connectTimeoutMs - 1_000L)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-a"))
        controller.handle(FailoverEvent.Tick)
        // まだ期限前
        assertTrue(controller.state is FailoverState.Connecting)

        clock.advance(1_000L)
        controller.handle(FailoverEvent.Tick)
        // 元の起点のまま期限が来た
        assertTrue(controller.state is FailoverState.FailingOver)
    }

    // --- ブリーフ 5: S1 の回帰 ---

    @Test
    fun `S1 認証失敗による除外は供給関数 true でも効く`() {
        dialogHostAttached = true

        // Ruling 23: 認証段階に到達していながら通過せずに切断された＝認証失敗。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    /**
     * 人がダイアログを閉じた（BACK / キャンセル）場合も S1 は従来どおり働く。
     * コアは認証をやめて切断へ進む。`UserPrompt` を観測しているので Ruling 23 の
     * 「認証段階に到達した」は成立しており、通過していないので除外される。
     * **これが「人が答えている状態」から抜ける2つ目の経路である**
     * （無限に待たないことの担保のひとつ）。
     */
    @Test
    fun `人がダイアログを閉じて切断された候補は供給関数 true でも S1 で除外される`() {
        userStartedAndDialogIsUp()
        dialogHostAttached = true

        clock.advance(60_000L)
        controller.handle(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.Connecting)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertTrue("uuid-a" in controller.excludedUuids)
        // alreadyDown = true なので待たずに次候補を起動する
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    @Test
    fun `S1 認証失敗による除外は供給関数 false でも従来どおり効く`() {
        dialogHostAttached = false

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Authenticating, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))

        assertTrue("uuid-a" in controller.excludedUuids)
        assertEquals(listOf("uuid-a", "uuid-b"), vpn.connectCalls)
    }

    // --- C2 の等価性: arming を無条件にしても除外の有無は変わらない ---

    /**
     * 裁定94（報告書 C2）: `userPromptSinceMs` の arming から
     * `currentCandidateUnattended` の絞り込みを外し、`onUnattendedPromptTimeout`
     * 側へ移した。**有人候補が裁定30 の10秒で除外されないことは変わらない。**
     * ここが崩れると、ユーザーが自分で押した接続が10秒で勝手に切り替わる
     * （Ruling 21 の「有人ならいつまで待たせてもよい」が死ぬ）。
     */
    @Test
    fun `有人で始まった候補は供給関数 false でも裁定30 の10秒では除外されない`() {
        userStartedAndDialogIsUp()
        dialogHostAttached = false

        // 10秒（裁定30 の待ち時間）を過ぎても、接続タイムアウト（45秒）の前なら
        // 何も起きない。除外は無人候補だけのものである。
        clock.advance(10_000L)
        controller.handle(FailoverEvent.Tick)

        assertTrue(controller.state is FailoverState.Connecting)
        assertTrue(controller.excludedUuids.isEmpty())
        assertEquals(listOf("uuid-a"), vpn.connectCalls)
    }
}
