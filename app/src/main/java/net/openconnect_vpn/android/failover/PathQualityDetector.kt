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

    /**
     * 受信速度の窓と応答時間の窓の**両方**を捨てる。測っている対象そのものが変わった
     * ときに使う（トンネルの張り替え・候補の入れ替わり）。前の対象の証拠は、どちらの窓
     * にも次の対象のものとして残してはならない。
     *
     * **この関数が応答時間も消すことに依存する事実がある**
     * （`FailoverControllerLapRttWiringTest` が振る舞いとして固定している）。
     * 一周の記録は、候補を**離れる瞬間**に [rttSamples] を読んで「離れる候補の応答時間の
     * 中央値とばらつき」を写す。そのあと測り直しが走る（`FailoverService.dispatch` が
     * `controller.handle`＝読む側のあとに `syncSlowLinkCandidate`＝消す側を呼ぶ）。
     * [reset] が応答時間を消さなければ、次の候補の最初の判定に前の候補の値が混じる。
     * 逆に、読む前にこの関数（または読み取り失敗用でない消し方）が走れば記録は空になる。
     */
    fun reset() {
        rx.reset()
        rtt.clear()
    }

    /**
     * 受信速度の窓**だけ**を捨てる。応答時間の窓は残す。
     *
     * **使う場面。** 同じ対象のまま、その tick だけバイト数が読めなかったとき
     * （インターフェース名を解決できない・`/proc/net/dev` を読めない）。
     * 裁定R20「測れなければ窓を捨てる」はバイト数の窓についての裁定であり、
     * 応答時間はその tick にも測れていた。捨てる理由が無い。
     *
     * **なぜ判定を緩めないのか。** [isDegraded] は先頭で `rx.windowAverage() ?: return false`
     * を返す。受信の帯は応答時間の条件と**独立の連言**なので、受信が測れないあいだは
     * 応答時間が何であれ判定は偽である。つまり応答時間を残しても
     * 「遅い」と**誤って言う**方向には何も働かない。影響するのは、受信の窓が
     * 埋まり直したあとに「遅くない」と**誤って言う**方向だけである。
     *
     * **捨てると何が壊れるか。** 一時的な読み取り失敗が1回あるだけで、最大 60 秒ぶんの
     * 応答時間の証拠が消える。(1) 判定は受信の窓が埋まり直しても、応答時間が
     * 貯まり直すまで偽のまま。(2) 一周の記録が [rttSamples] から読むサンプルが
     * 空になり、その候補は最良の選定（サンプル数の門）から静かに外れる。
     *
     * トンネルの張り替え（インターフェースの入れ替わり）には使わない。あちらは
     * 測る対象が変わるので [reset] である。
     */
    fun resetThroughput() {
        rx.reset()
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
