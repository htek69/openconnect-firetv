package net.openconnect_vpn.android.failover

/**
 * 仕様書 §2: 応答時間と受信速度から「経路が劣化している」を判定する。
 *
 * **時計を持たない。** 時刻は [onSample] / [onProbe] の引数で受け取るので、
 * Android にも実時間にも依存しない（既存の [SlowLinkDetector] と同じ作法）。
 *
 * **なぜ応答時間が本体なのか。** 受信速度は `min(容量, 需要)` であり、受動観測では
 * 「回線が細い」と「誰も要求していない」を分離できない。旧仕様は送信速度を需要の
 * 代用にしたが、上り主体の通信で崩れた（実機で受信 5 / 送信 86 kbps の誤判定。
 * `docs/MANUAL-TEST.md` 節 10-5-1）。**応答時間は需要に依存しない。**
 *
 * **受信速度は帯の条件として残す。** 下限（[SlowLinkSettings.rxFloorKbps]）は
 * **待機中の誤判定を防ぐ要**である。これが無いと「待機中かつ経路劣化」で発火する
 * （仕様書 §2 の注記。初稿に実際にあった穴）。
 */
class PathQualityDetector(private val settings: SlowLinkSettings) {

    /** 受信速度の窓は既存の判定器を使い回す（窓の刈り取りと裁定R20 を踏襲）。 */
    private val rx = SlowLinkDetector(
        SlowLinkThresholds(
            windowSec = WINDOW_SEC,
            slowRxKbps = settings.slowRxKbps,
        ),
    )

    private class RttSample(val atMs: Long, val rttMs: Long)

    private val rtt = ArrayDeque<RttSample>()

    private val windowMs: Long get() = WINDOW_SEC * 1000L

    fun onSample(atMs: Long, bytes: IfaceBytes) {
        rx.onSample(atMs, bytes)
        // 応答時間の窓も採取の時刻で刈る。プローブが止まっても採取は続くので、
        // ここで刈らないと窓より古い応答時間が判定を生かし続ける
        // （受信側が持つ「古い証拠では判定しない」保護の、応答時間側の対）。
        pruneRtt(atMs)
    }

    /**
     * プローブ1周期の結果を受け取る。**到達できなかった周期のサンプルも入れる**
     * （呼び出し側がタイムアウト値を所要時間として渡す。[ProbeOutcome] の KDoc）。
     */
    fun onProbe(atMs: Long, outcome: ProbeOutcome) {
        for (v in outcome.rttMs) rtt.addLast(RttSample(atMs, v))
        pruneRtt(atMs)
    }

    private fun pruneRtt(nowMs: Long) {
        val cutoffMs = nowMs - windowMs
        while (rtt.isNotEmpty() && rtt.first().atMs < cutoffMs) rtt.removeFirst()
    }

    fun reset() {
        rx.reset()
        rtt.clear()
    }

    /** 窓の中の応答時間のサンプル（一周の記録が使う。[LapRecord]）。 */
    fun rttSamples(): List<Long> = rtt.map { it.rttMs }

    fun isDegraded(): Boolean {
        // 受信速度の帯。上限は既存の判定器が持つが、**送信の条件は使わない**ので
        // ここでは窓の平均を直接見る。
        val avg = rx.windowAverage() ?: return false
        if (avg.rxKbps >= settings.slowRxKbps) return false
        if (settings.rxFloorKbps > 0 && avg.rxKbps <= settings.rxFloorKbps) return false

        val samples = rtt.map { it.rttMs }
        if (samples.isEmpty()) return false
        val o = ProbeOutcome(reachable = true, rttMs = samples)
        val median = o.medianRttMs ?: return false
        val spread = o.spreadMs ?: return false
        return median > settings.degradedRttMs && spread > settings.degradedJitterMs
    }

    private companion object {
        /**
         * 窓の長さ（秒）。**利用者が変えられる項目にしない。**
         * 検知までの時間は仕様書と画面の文面に出ており（「60秒ぶんを均して」）、
         * ここを可変にすると二重管理になる。仕様書 §2-A のとおり、
         * **比較の記録を増やすのは窓を長くすることではなく採取回数を増やすこと**である。
         */
        const val WINDOW_SEC = 60
    }
}
