package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun `初回は30秒`() {
        assertEquals(30_000L, Backoff.delayMsForAttempt(0))
    }

    @Test
    fun `試行ごとに倍になる`() {
        assertEquals(30_000L, Backoff.delayMsForAttempt(0))
        assertEquals(60_000L, Backoff.delayMsForAttempt(1))
        assertEquals(120_000L, Backoff.delayMsForAttempt(2))
        assertEquals(240_000L, Backoff.delayMsForAttempt(3))
    }

    @Test
    fun `上限は10分で打ち止め`() {
        assertEquals(480_000L, Backoff.delayMsForAttempt(4))
        assertEquals(600_000L, Backoff.delayMsForAttempt(5))
        assertEquals(600_000L, Backoff.delayMsForAttempt(6))
        assertEquals(600_000L, Backoff.delayMsForAttempt(99))
    }

    @Test
    fun `負の試行回数は初回と同じ扱い`() {
        assertEquals(30_000L, Backoff.delayMsForAttempt(-1))
    }
}
