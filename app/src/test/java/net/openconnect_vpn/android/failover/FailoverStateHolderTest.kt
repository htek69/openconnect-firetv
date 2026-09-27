package net.openconnect_vpn.android.failover

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `裁定65（指摘7）- state を MutableStateFlow へキャストして書き込もうとすると失敗する`() {
        // asStateFlow() は ReadonlyStateFlow でラップして返すため、
        // MutableStateFlow の実装クラスとは異なり、このキャストは実行時に落ちる。
        assertThrows(ClassCastException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (FailoverStateHolder.state as MutableStateFlow<FailoverState>).value = FailoverState.Idle
        }
    }
}
