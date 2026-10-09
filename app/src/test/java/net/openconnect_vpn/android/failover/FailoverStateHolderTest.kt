package net.openconnect_vpn.android.failover

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailoverStateHolder] は `object`（プロセス内シングルトン）なので、
 * テスト間で値が漏れないよう毎回 [FailoverStateHolder.reset] で initial state
 * (Idle) に戻す。
 */
class FailoverStateHolderTest {

    /** 速度起因の切替の途中でメンバーが消える経路を作るため可変にしてある。 */
    private var members = listOf("uuid-a", "uuid-b")

    private val group
        get() = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = members,
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )

    @After
    fun tearDown() {
        FailoverStateHolder.reset()
    }

    /**
     * 速度起因の切替が進行中で、`FailoverService.dispatch` が理由を投影へ
     * 書き終えた状態を作る。ここから先の「その切替をどう抜けるか」が
     * 各テストの対象である。
     *
     * 実物の [FailoverController] を動かすので、状態遷移は本番と同じものになる
     * （理由の消える条件を、投影の入力だけを手で組み立てて確かめると、
     * 状態機械が実際にその状態へ来るのかを検査できない）。
     */
    private fun startedSlowLinkSwitch(
        clock: FakeClock,
        vpn: FakeVpnController,
    ): FailoverController {
        // 「遅い」は常に真でよい。slowLinkProvider を引くのは onSlowLinkSwitch
        // （`Healthy` の Tick）だけで、下の Healthy までの道に Tick は無い。
        val controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = FakeNetworkGate(available = true),
            slowLinkProvider = { true },
        )

        // uuid-a を Healthy まで進める（接続直後の猶予も越える）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L) // graceAfterConnectSec = 15 を越える
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // 速度低下による切替が始まり、dispatch が理由を投影へ書く。
        controller.handle(FailoverEvent.Tick)
        assertTrue((controller.state as FailoverState.FailingOver).bySlowLink)
        FailoverStateHolder.onFailoverStateChanged(controller.state)
        assertEquals("g1", FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        return controller
    }

    @Test
    fun `初期状態は Idle でグループも無い`() {
        assertEquals(FailoverState.Idle, FailoverStateHolder.state.value)
        assertNull(FailoverStateHolder.activeGroup.value)
    }

    @Test
    fun `publish した内容がそのまま読み返せる`() {
        val healthy = FailoverState.Healthy(
            groupId = "g1",
            candidateIndex = 0,
            consecutiveFailures = 0,
            lastProbeAtMs = 1_000L,
        )

        FailoverStateHolder.publish(healthy, group)

        assertEquals(healthy, FailoverStateHolder.state.value)
        assertEquals(group, FailoverStateHolder.activeGroup.value)
    }

    @Test
    fun `reset で Idle とグループ無しに戻る`() {
        FailoverStateHolder.publish(
            FailoverState.Connecting(groupId = "g1", candidateIndex = 0, startedAtMs = 0L),
            group,
        )

        FailoverStateHolder.reset()

        assertEquals(FailoverState.Idle, FailoverStateHolder.state.value)
        assertNull(FailoverStateHolder.activeGroup.value)
    }

    @Test
    fun `裁定R19 - 切替理由は状態が FailingOver を抜けても残り 明示的な消去でだけ消える`() {
        assertNull(FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        FailoverStateHolder.publishSlowLinkSwitch("g1")
        // 切替が終わって Healthy へ進んでも理由は残る（publish は理由に触らない）。
        FailoverStateHolder.publish(
            FailoverState.Healthy(
                groupId = "g1",
                candidateIndex = 1,
                consecutiveFailures = 0,
                lastProbeAtMs = 1_000L,
            ),
            group,
        )
        assertEquals("g1", FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        FailoverStateHolder.clearSlowLinkSwitch()
        assertNull(FailoverStateHolder.lastSlowLinkSwitchGroupId.value)
    }

    @Test
    fun `裁定R19 - reset は切替理由も消す`() {
        FailoverStateHolder.publishSlowLinkSwitch("g1")

        FailoverStateHolder.reset()

        assertNull(FailoverStateHolder.lastSlowLinkSwitchGroupId.value)
    }

    @Test
    fun `最終再レビュー指摘4（FIX 1）- 速度低下切替のあとの死活切替は理由を消す`() {
        // 速度低下による切替が起きて理由が記録される。
        FailoverStateHolder.onFailoverStateChanged(
            FailoverState.FailingOver(
                groupId = "g1",
                failedIndex = 0,
                awaitingUuid = "uuid-a",
                startedAtMs = 0L,
                bySlowLink = true,
            ),
        )
        assertEquals("g1", FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        // 時間が経ち、今度は死活起因の切替が別グループで起きる。
        // このとき前回の「経路品質で切替」という理由を残してはいけない
        // （直前の切替は死活起因であり、速度低下の文言は嘘になる）。
        FailoverStateHolder.onFailoverStateChanged(
            FailoverState.FailingOver(
                groupId = "g1",
                failedIndex = 1,
                awaitingUuid = "uuid-b",
                startedAtMs = 10_000L,
                bySlowLink = false,
            ),
        )

        assertNull(FailoverStateHolder.lastSlowLinkSwitchGroupId.value)
    }

    /**
     * 統合レビューの所見1（medium）: 速度起因の `FailingOver` の**最中に**利用者の
     * 明示的な接続（グループ行の決定、または行の「初回ログイン」）が届いたときの
     * 切替理由。
     *
     * `FailoverService.onStartCommand` は先に [FailoverStateHolder.clearSlowLinkSwitch]
     * を呼び（唯一の消去点）、続けて dispatch する。その dispatch は
     * [FailoverStateHolder.onFailoverStateChanged] を**毎回**通るので、
     * `stopBeforeStarting` が返す状態に `bySlowLink = true` が残っていると、
     * **消したばかりの理由が同じ dispatch の中で書き戻る。** 残った理由が指すのは
     * 保留（`pendingConnect`）に横取りされて**完了しなかった**切替であり、
     * 裁定R19 が避けようとしている「間違った理由」そのものである。
     *
     * ここは投影の側ではなく状態機械の側（`stopBeforeStarting` が
     * `bySlowLink = false` に落とす）で直してある。この順序をそのまま踏む。
     */
    @Test
    fun `統合レビュー所見1 - 保留に横取りされた速度起因の切替の理由は残らない`() {
        val clock = FakeClock(1_000L)
        val vpn = FakeVpnController()
        val controller = startedSlowLinkSwitch(clock, vpn)

        // 切断確認を待っているあいだ（DISCONNECT_WAIT_MS = 3秒）に利用者が
        // 明示的に接続する。ACTION_CONNECT_GROUP の順序どおり、先に理由を消し、
        // 続けて同じ dispatch が状態を投影する。
        FailoverStateHolder.clearSlowLinkSwitch()
        controller.handle(FailoverEvent.UserConnectGroup("g1", fromIndex = 1))
        FailoverStateHolder.onFailoverStateChanged(controller.state)

        // 状態は裁定26 のとおり切断待ちのまま（保留先だけが差し替わる）。
        // ただしこの切替はもう次候補へ進まないので、速度起因の印は降りている。
        assertFalse((controller.state as FailoverState.FailingOver).bySlowLink)
        assertNull(
            "利用者の明示的な接続が消した理由を、同じ dispatch で書き戻してはならない",
            FailoverStateHolder.lastSlowLinkSwitchGroupId.value,
        )
    }

    /**
     * 追加審議の経路1（**実際に居座るのはこれ**）: 消灯による切断。
     *
     * 裁定44 により TV の画面が消えると `FailoverService.onScreenOff` が
     * `UserDisconnect` を流す（`ACTION_DISCONNECT` もまったく同じ経路）。
     * 速度起因の `FailingOver` の最中にこれが来ると `onUserDisconnect` は
     * 保留を捨てて `Idle` を返すので、**その切替は次候補へ前進しない。**
     * 画面が消えているあいだは誰も見ていないため、この経路の誤表示だけは
     * 消えずに残り、次に利用者がホームを開いたときに読まれる。
     */
    @Test
    fun `消灯で切断したら前進しなかった速度起因の切替の理由は残らない`() {
        val clock = FakeClock(1_000L)
        val vpn = FakeVpnController()
        val controller = startedSlowLinkSwitch(clock, vpn)

        // 消灯（裁定44）。ACTION_DISCONNECT も同じイベントを流す。
        controller.handle(FailoverEvent.UserDisconnect)
        FailoverStateHolder.onFailoverStateChanged(controller.state)

        assertEquals(FailoverState.Idle, controller.state)
        assertNull(
            "前進しなかった切替の理由を、切断後の行に出してはならない",
            FailoverStateHolder.lastSlowLinkSwitchGroupId.value,
        )
    }

    /**
     * 追加審議の経路1の続き: 「消灯 → 点灯でそのまま `Connecting`」。
     *
     * 点灯すると裁定44 により `AutoConnectGroup` が飛ぶ。待つべき放棄候補が
     * 無いので `stopBeforeStarting` は null を返し、状態は `FailingOver` を
     * 経ずに**いきなり `Connecting`** になる。`Connecting` は「切替が前進した
     * 先」なので投影は理由に触らない——つまりこの経路で理由が消えるかどうかは
     * 消灯時（`Idle`）に消えたかどうかだけで決まる。
     *
     * しかも点灯後の再接続は**グループの先頭候補**から始まるので、ここに
     * 「経路品質で切替」が出ていると、今のセッションでは起きていない切替を
     * 説明することになる。
     */
    @Test
    fun `消灯から点灯で繋ぎ直しても切替理由は戻らない`() {
        val clock = FakeClock(1_000L)
        val vpn = FakeVpnController()
        val controller = startedSlowLinkSwitch(clock, vpn)

        controller.handle(FailoverEvent.UserDisconnect)
        FailoverStateHolder.onFailoverStateChanged(controller.state)
        assertNull(FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        // 点灯（裁定44）。先頭候補から無人で繋ぎ直す。
        controller.handle(FailoverEvent.AutoConnectGroup("g1"))
        FailoverStateHolder.onFailoverStateChanged(controller.state)

        val state = controller.state as FailoverState.Connecting
        assertEquals(0, state.candidateIndex) // 先頭から繋ぎ直している
        assertNull(
            "点灯後の接続は別の出来事であり、前の切替の理由を引き継がない",
            FailoverStateHolder.lastSlowLinkSwitchGroupId.value,
        )
    }

    /**
     * 追加審議で見つかった**3つめ**の抜け道: `Exhausted` に着地する場合。
     *
     * `onSlowLinkSwitch` は行き先があることを確かめてから切替を始めるが、
     * 切断確認を待つ3秒の窓の中で行き先が消えることはある（据え置きの残件
     * R11。ここではその窓でメンバーが削除された場合を作る）。すると
     * `advanceAfterFailingOver` → `startCandidateFrom` が起動できる候補を
     * 見つけられず `Exhausted` に落ちる——**切替は前進せず、遅くはあっても
     * 繋がっていたトンネルまで失う。**
     *
     * 経路ごとの特例を足していく形ではこれを取り落とす。投影が見るのは
     * 「前進したか」であって経路の種類ではない、という形にしてある。
     */
    @Test
    fun `行き先が消えて枯渇に落ちたら前進しなかった切替の理由は残らない`() {
        val clock = FakeClock(1_000L)
        val vpn = FakeVpnController()
        val controller = startedSlowLinkSwitch(clock, vpn)

        // 切断確認を待っている窓の中で uuid-b がグループから外された。
        members = listOf("uuid-a")

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        FailoverStateHolder.onFailoverStateChanged(controller.state)

        assertTrue(controller.state is FailoverState.Exhausted)
        assertEquals("起動できる候補が無いので何も繋いでいない", 1, vpn.connectCalls.size)
        assertNull(
            "前進しなかった切替の理由を、枯渇中の行に出してはならない",
            FailoverStateHolder.lastSlowLinkSwitchGroupId.value,
        )
    }

    /**
     * 上の3件の裏返し: **前進した**切替の理由は、切替が作った接続が生きている
     * あいだ残る（仕様書 5 の目的そのもの）。`Connecting` → `Verifying` →
     * `Healthy` のどこでも消えない。
     *
     * これが無いと「常に消す」実装でも上の3件が通ってしまう。
     */
    @Test
    fun `前進した速度起因の切替の理由は接続が生きているあいだ残る`() {
        val clock = FakeClock(1_000L)
        val vpn = FakeVpnController()
        val controller = startedSlowLinkSwitch(clock, vpn)

        // 切断確認 → 次候補（uuid-b）が起動する＝切替が前進した。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        FailoverStateHolder.onFailoverStateChanged(controller.state)
        assertEquals("uuid-b", vpn.connectCalls.last())
        assertTrue(controller.state is FailoverState.Connecting)
        assertEquals("g1", FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-b"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-b"))
        FailoverStateHolder.onFailoverStateChanged(controller.state)
        assertTrue(controller.state is FailoverState.Verifying)
        assertEquals("g1", FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        FailoverStateHolder.onFailoverStateChanged(controller.state)
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(
            "切替が作った接続が生きているあいだは理由を出し続ける",
            "g1",
            FailoverStateHolder.lastSlowLinkSwitchGroupId.value,
        )
    }

    @Test
    fun `裁定65（指摘7）- state を MutableStateFlow へキャストして書き込もうとすると失敗する`() {
        // asStateFlow() は ReadonlyStateFlow でラップして返すため、
        // MutableStateFlow の実装クラスとは異なり、このキャストは実行時に落ちる。
        assertThrows(ClassCastException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (FailoverStateHolder.state as MutableStateFlow<FailoverState>).value = FailoverState.Idle
        }
    }
}
