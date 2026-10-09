package net.openconnect_vpn.android.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仕様書 §2 の判定。**実機で取った数字を入力に固定する**のがこのテストの要点で、
 * 旧仕様の受信速度だけの判定が実機で成立しなかった経緯は
 * `docs/MANUAL-TEST.md` 節 10-5-1 にある。
 */
class PathQualityDetectorTest {

    private val settings = SlowLinkSettings(
        enabled = true,
        slowRxKbps = 1000,
        rxFloorKbps = 50,
        degradedRttMs = 250,
        degradedJitterMs = 150,
    )

    /** 窓（60秒）を受信 [rxKbps] で埋め、各周期に [rtt] を与える。 */
    private fun fill(d: PathQualityDetector, rxKbps: Long, rtt: List<Long>) {
        var rx = 0L
        // 5秒ごとに13サンプル（span 60秒）。1周期=30秒ごとにプローブ2回。
        for (i in 0..12) {
            val atMs = i * 5_000L
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = rtt))
            rx += rxKbps * 5_000L / 8
        }
    }

    @Test
    fun `実測の遅い接続先で発火する`() {
        // 旧仕様 §2-2: 遅かった接続先は avg 137〜379ms / mdev 140〜336ms。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L, 280L, 310L))
        assertTrue(d.isDegraded())
    }

    @Test
    fun `素の回線の値では発火しない`() {
        // 旧仕様 §2-2: 素の回線は avg 73.5ms / mdev 5.0ms。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(70L, 74L, 73L, 71L, 75L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `2026-10-09 の待機中の誤判定は発火しない（受信が下限未満）`() {
        // 節 10-5-1: 待機中の受信は 0.16 kbps（中央値）。経路は健全だった。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 0, rtt = listOf(70L, 74L, 73L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `待機中で経路が悪くても発火しない（受信の下限が効く）`() {
        // 自己レビューで見つけた穴（仕様書 §2 の注記）。送信の条件を削除する
        // だけでは、待機中かつ経路劣化で発火してしまう。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 0, rtt = listOf(300L, 90L, 600L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `受信が上限を超えていれば経路が悪くても発火しない`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 4000, rtt = listOf(300L, 90L, 600L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `中央値だけ超えてばらつきが小さければ発火しない`() {
        // 地理的に遠い宛先。常時遅いがばらつかない。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(400L, 402L, 398L, 401L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `ばらつきだけ大きくて中央値が小さければ発火しない`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(60L, 70L, 400L, 65L, 62L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `窓が埋まる前は発火しない`() {
        val d = PathQualityDetector(settings)
        d.onSample(0L, IfaceBytes(0L, 0L))
        d.onProbe(0L, ProbeOutcome(reachable = true, rttMs = listOf(300L, 90L, 600L)))
        d.onSample(10_000L, IfaceBytes(530_000L, 0L))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `応答時間のサンプルが無ければ発火しない`() {
        val d = PathQualityDetector(settings)
        var rx = 0L
        for (i in 0..12) {
            d.onSample(i * 5_000L, IfaceBytes(rx, 0L))
            rx += 424L * 5_000L / 8
        }
        assertFalse(d.isDegraded())
    }

    @Test
    fun `reset で初期状態に戻る`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.isDegraded())
        d.reset()
        assertFalse(d.isDegraded())
    }

    @Test
    fun `rxFloorKbps が 0 なら下限なしとして扱う`() {
        val d = PathQualityDetector(settings.copy(rxFloorKbps = 0))
        fill(d, rxKbps = 0, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.isDegraded())
    }

    @Test
    fun `rttSamples は窓の中のサンプルを返す`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L))
        assertTrue(d.rttSamples().isNotEmpty())
    }
}
