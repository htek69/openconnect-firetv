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

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @After
    fun tearDown() {
        FailoverStateHolder.reset()
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
        // このとき前回の「速度低下で切替」という理由を残してはいけない
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
        var slow = false
        val controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = FakeNetworkGate(available = true),
            slowLinkProvider = { slow },
        )

        // uuid-a を Healthy まで進める（接続直後の猶予も越える）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = "uuid-a"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = "uuid-a"))
        clock.advance(16_000L) // graceAfterConnectSec = 15 を越える
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)

        // 速度低下による切替が始まり、dispatch が理由を投影へ書く。
        slow = true
        controller.handle(FailoverEvent.Tick)
        assertTrue((controller.state as FailoverState.FailingOver).bySlowLink)
        FailoverStateHolder.onFailoverStateChanged(controller.state)
        assertEquals("g1", FailoverStateHolder.lastSlowLinkSwitchGroupId.value)

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
