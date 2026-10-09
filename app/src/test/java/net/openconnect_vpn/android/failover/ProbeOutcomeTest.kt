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

    // 以下3件は、既存の2件が通してしまう誤実装を退けるために置く。
    // 既存の入力は平均と中央値が一致し（[30,10,20] は平均も20）、偶数側は既にソート済みなので
    // 「平均を返す」「ソートしない」誤実装が通ってしまう。どの1件も削除しないこと。

    @Test
    fun `外れ値があり順不同の奇数個でも中央値は真ん中の値`() {
        // 中央値 2。平均は 34 なので「平均を返す」誤実装を退ける。
        // 整列せず先頭3件から真ん中を取る誤実装は 1 を返すので退ける。
        val o = ProbeOutcome(reachable = true, rttMs = listOf(100L, 1L, 2L))
        assertEquals(2L, o.medianRttMs)
    }

    @Test
    fun `外れ値があり順不同の偶数個でも中央2つの平均で求める`() {
        // ソート後 1,2,3,100 → (2 + 3) / 2 = 2。平均は 26 なので「平均を返す」誤実装を退ける。
        // ソートせず中央2つを取る誤実装は (100 + 1) / 2 = 50 になるので退ける。
        val o = ProbeOutcome(reachable = true, rttMs = listOf(3L, 100L, 1L, 2L))
        assertEquals(2L, o.medianRttMs)
    }

    @Test
    fun `偶数個の平均が割り切れない場合は切り捨てる`() {
        // 正確な平均は 15.5。切り捨ては 15 だが、四捨五入や切り上げをする誤実装は 16 になるので退ける。
        val o = ProbeOutcome(reachable = true, rttMs = listOf(10L, 21L))
        assertEquals(15L, o.medianRttMs)
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
