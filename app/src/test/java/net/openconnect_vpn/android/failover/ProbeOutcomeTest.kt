package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeOutcomeTest {

    @Test
    fun `サンプルが無ければ中央値もばらつきも null`() {
        val o = ProbeOutcome(reachable = false, rttMs = emptyList())
        assertNull(o.medianRttMs)
        assertNull(o.spreadMs)
    }

    @Test
    fun `奇数個の中央値は真ん中の値`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(30L, 10L, 20L))
        assertEquals(20L, o.medianRttMs)
    }

    @Test
    fun `偶数個の中央値は中央2つの平均（切り捨て）`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(10L, 20L, 30L, 41L))
        // (20 + 30) / 2 = 25
        assertEquals(25L, o.medianRttMs)
    }

    @Test
    fun `ばらつきは最大と最小の差`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(10L, 95L, 20L))
        assertEquals(85L, o.spreadMs)
    }

    @Test
    fun `サンプルが1つならばらつきは0`() {
        val o = ProbeOutcome(reachable = true, rttMs = listOf(42L))
        assertEquals(42L, o.medianRttMs)
        assertEquals(0L, o.spreadMs)
    }
}
