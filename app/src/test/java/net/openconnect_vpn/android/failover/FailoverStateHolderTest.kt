package net.openconnect_vpn.android.failover

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FailoverStateHolder] は `object`（プロセス内シングルトン）なので、
 * テスト間で値が漏れないよう毎回 [FailoverStateHolder.reset] で initial state
 * (Idle) に戻す。
 */
class FailoverStateHolderTest {

    @After
    fun tearDown() {
        FailoverStateHolder.reset()
    }

    @Test
    fun `初期状態は Idle`() {
        assertEquals(FailoverState.Idle, FailoverStateHolder.state.value)
    }

    @Test
    fun `publish した内容がそのまま読み返せる`() {
        val healthy = FailoverState.Healthy(
            groupId = "g1",
            candidateIndex = 0,
            consecutiveFailures = 0,
            lastProbeAtMs = 1_000L,
        )

        FailoverStateHolder.publish(healthy)

        assertEquals(healthy, FailoverStateHolder.state.value)
    }

    @Test
    fun `reset で Idle に戻る`() {
        FailoverStateHolder.publish(
            FailoverState.Connecting(groupId = "g1", candidateIndex = 0, startedAtMs = 0L),
        )

        FailoverStateHolder.reset()

        assertEquals(FailoverState.Idle, FailoverStateHolder.state.value)
    }
}
