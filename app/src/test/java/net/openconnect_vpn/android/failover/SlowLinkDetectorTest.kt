package net.openconnect_vpn.android.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowLinkDetectorTest {

    private val th = SlowLinkThresholds(windowSec = 60, slowRxKbps = 1000, demandTxKbps = 5)

    /** [rxKbps] / [txKbps] の速度で [sec] 秒ぶん、1秒刻みで供給し、最後の時刻を返す。 */
    private fun SlowLinkDetector.feed(startMs: Long, sec: Int, rxKbps: Int, txKbps: Int): Long {
        var rx = 0L
        var tx = 0L
        var t = startMs
        onSample(t, IfaceBytes(rx, tx))
        repeat(sec) {
            t += 1_000
            rx += rxKbps * 1000L / 8
            tx += txKbps * 1000L / 8
            onSample(t, IfaceBytes(rx, tx))
        }
        return t
    }

    @Test
    fun `窓を満たすまでは遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 59, rxKbps = 100, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `受信が閾値未満で送信があり窓を満たしたら遅い`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
    }

    @Test
    fun `送信がほとんど無ければ待機中とみなし遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 120, rxKbps = 0, txKbps = 1)
        assertFalse(d.isSlow())
    }

    @Test
    fun `受信が閾値以上なら遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 120, rxKbps = 4000, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `途中で速くなったら数え直す`() {
        val d = SlowLinkDetector(th)
        val t = d.feed(0, sec = 50, rxKbps = 100, txKbps = 50)
        d.feed(t, sec = 5, rxKbps = 4000, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `reset すると数え直しになる`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
        d.reset()
        assertFalse(d.isSlow())
    }

    @Test
    fun `カウンタが巻き戻ったら数え直す`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
        d.onSample(100_000, IfaceBytes(0, 0))
        assertFalse(d.isSlow())
    }

    @Test
    fun `同じ時刻の重複サンプルで壊れない`() {
        val d = SlowLinkDetector(th)
        d.onSample(0, IfaceBytes(0, 0))
        d.onSample(0, IfaceBytes(0, 0))
        assertFalse(d.isSlow())
    }
}
