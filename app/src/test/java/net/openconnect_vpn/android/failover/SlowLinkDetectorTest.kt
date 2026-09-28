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

    /** バイトカウンタの状態を保持しながら供給する。 */
    private data class FeedResult(val timeMs: Long, val rxBytes: Long, val txBytes: Long)

    private fun SlowLinkDetector.feedWithState(
        startMs: Long,
        startRx: Long,
        startTx: Long,
        sec: Int,
        rxKbps: Int,
        txKbps: Int,
    ): FeedResult {
        var rx = startRx
        var tx = startTx
        var t = startMs
        onSample(t, IfaceBytes(rx, tx))
        repeat(sec) {
            t += 1_000
            rx += rxKbps * 1000L / 8
            tx += txKbps * 1000L / 8
            onSample(t, IfaceBytes(rx, tx))
        }
        return FeedResult(t, rx, tx)
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
    fun `送信が閾値ちょうどでは需要とみなさない`() {
        // 条件は「閾値を上回る」なので、境界の 5 kbps では成立しない。
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 120, rxKbps = 100, txKbps = 5)
        assertFalse(d.isSlow())
    }

    @Test
    fun `受信が閾値以上なら遅いと判定しない`() {
        val d = SlowLinkDetector(th)
        d.feed(0, sec = 120, rxKbps = 4000, txKbps = 50)
        assertFalse(d.isSlow())
    }

    @Test
    fun `小区間が閾値を上回っても窓の平均が下回れば遅い`() {
        // 裁定R20 の要点。10秒ごとに 1秒だけ 2000 kbps へ跳ね上がる回線
        // （動画プレイヤーが区間ごとにまとめて取りに来る遅い回線の形）。
        // 小区間の全部が閾値未満であることを求める以前の規則ではこの列は
        // **永久に成立しなかった**が、窓の平均は 290 kbps なので遅いと判定する。
        val d = SlowLinkDetector(th)
        var result = FeedResult(0, 0, 0)
        repeat(7) {
            result = d.feedWithState(
                result.timeMs, result.rxBytes, result.txBytes,
                sec = 9, rxKbps = 100, txKbps = 50,
            )
            result = d.feedWithState(
                result.timeMs, result.rxBytes, result.txBytes,
                sec = 1, rxKbps = 2000, txKbps = 50,
            )
        }
        assertTrue(d.isSlow())
    }

    @Test
    fun `窓の平均が閾値を上回れば遅いと判定しない`() {
        // 逆側の固定。落ち込みが長くても、窓の平均（約 1083 kbps）が床を
        // 上回るなら切り替えない。
        val d = SlowLinkDetector(th)
        val result = d.feedWithState(0, 0, 0, sec = 51, rxKbps = 100, txKbps = 50)
        d.feedWithState(result.timeMs, result.rxBytes, result.txBytes, sec = 10, rxKbps = 6000, txKbps = 50)
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

    @Test
    fun `時刻が進まないサンプルは窓を壊さない`() {
        // 満たした窓に同じ時刻・時刻が戻ったサンプルが来ても、判定は変わらず
        // 0 除算にもならない。
        val d = SlowLinkDetector(th)
        val result = d.feedWithState(0, 0, 0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())

        d.onSample(result.timeMs, IfaceBytes(result.rxBytes + 100, result.txBytes + 100))
        assertTrue(d.isSlow())

        d.onSample(result.timeMs - 5_000, IfaceBytes(result.rxBytes + 200, result.txBytes + 200))
        assertTrue(d.isSlow())
    }

    @Test
    fun `採取が止まって窓が古くなったら遅いと判定しない`() {
        // 窓が埋まったあと採取が5分止まり、そのあいだも遅い通信が続いていた
        // という累計値が来た場合。平均だけ見れば条件を満たすが、これは
        // 「直近の窓」の証拠ではないので判定を出してはならない
        // （古い判定で切り替わると、利用者には原因不明の切断に見える）。
        val d = SlowLinkDetector(th)
        val result = d.feedWithState(0, 0, 0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())

        val gapSec = 300
        val afterGapMs = result.timeMs + gapSec * 1_000L
        val rxAfterGap = result.rxBytes + gapSec * (100 * 1000L / 8)
        val txAfterGap = result.txBytes + gapSec * (50 * 1000L / 8)
        d.onSample(afterGapMs, IfaceBytes(rxAfterGap, txAfterGap))
        assertFalse(d.isSlow())

        // 採取が戻って窓ぶんの新しい証拠が揃えば、また判定できる。
        d.feedWithState(afterGapMs, rxAfterGap, txAfterGap, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
    }

    @Test
    fun `ティックが飛んでも窓の平均は累計値から正しく出る`() {
        // 消灯中（30秒ティック）のように粗い間隔でも、累計カウンタの差を取るので
        // 欠けた区間のバイト数は平均に含まれる。
        val d = SlowLinkDetector(th)
        val rxPerSec = 100 * 1000L / 8
        val txPerSec = 50 * 1000L / 8
        listOf(0L, 30_000L, 60_000L, 90_000L).forEach { t ->
            val sec = t / 1_000
            d.onSample(t, IfaceBytes(sec * rxPerSec, sec * txPerSec))
        }
        assertTrue(d.isSlow())
    }

    @Test
    fun `受信カウンタのみが巻き戻った時も数え直す`() {
        val d = SlowLinkDetector(th)
        val result = d.feedWithState(0, 0, 0, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())

        val t = result.timeMs + 1_000
        val rxBackward = result.rxBytes / 2
        val txContinue = result.txBytes + 50 * 1000L / 8
        d.onSample(t, IfaceBytes(rxBackward, txContinue))

        assertFalse(d.isSlow())

        d.feedWithState(t, rxBackward, txContinue, sec = 61, rxKbps = 100, txKbps = 50)
        assertTrue(d.isSlow())
    }
}
