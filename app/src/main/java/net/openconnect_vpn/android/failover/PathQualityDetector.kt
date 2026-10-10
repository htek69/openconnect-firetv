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

    /**
     * 項目25: `reset()` のあと、受信のサンプルを窓に入れない期間。
     *
     * **なぜ必要か。** 2026-10-10 の実機（`docs/MANUAL-TEST.md` 節10 の N-1 2回目）で、
     * トンネルが上がった直後の23秒間に約504 KB（175 kbps）のバーストが来た。
     * 接続性の検証・名前解決・VPN 復帰で再開した同期などで、**利用者の通信ではない。**
     * 窓は候補が変わると空になるので接続の瞬間から埋まり始め、**接続から60秒後の
     * 最初の判定は、その大半がこのバーストで構成される。** 待機中に1回切り替わった。
     *
     * **猶予（`graceAfterConnectSec` = 15秒）を延ばしても直らない。** あれは判定の
     * タイミングだけを遅らせるので、窓の中身は変わらない。バーストを窓から追い出すには
     * 猶予が「窓＋バーストの長さ」以上でなければならず、バーストの長さは保証されない。
     *
     * **代償。** 切替のあと、最短の検知時間が 60 秒から 90 秒（抑制＋窓）に延びる。
     * 一周の所要時間も候補あたり約30秒ぶん延びる。
     */
    private var suppressing = false

    /** 抑制の起点。`reset()` では null に戻し、次のサンプルの時刻で決める。 */
    private var suppressFromMs: Long? = null

    /** [judge] が連続して真になり始めた時刻。偽になったら null に戻す（項目25）。 */
    private var degradedSinceMs: Long? = null

    /** 最後に採取した時刻。連続している長さを測るのに使う。 */
    private var lastSampleAtMs: Long? = null

    private val windowMs: Long get() = WINDOW_SEC * 1000L

    fun onSample(atMs: Long, bytes: IfaceBytes) {
        if (suppressing) {
            val from = suppressFromMs
            if (from == null) {
                // 判定器は時計を持たないので、`reset()` が呼ばれた時刻は知りえない。
                // **リセット後の最初のサンプルの時刻**を抑制の起点にする。
                suppressFromMs = atMs
                pruneRtt(atMs)
                return
            }
            if (atMs - from < SUPPRESS_AFTER_RESET_MS) {
                pruneRtt(atMs)
                return
            }
            suppressing = false
        }
        rx.onSample(atMs, bytes)
        // 応答時間の窓も採取の時刻で刈る。プローブが止まっても採取は続くので、
        // ここで刈らないと窓より古い応答時間が判定を生かし続ける
        // （受信側が持つ「古い証拠では判定しない」保護の、応答時間側の対）。
        pruneRtt(atMs)
        lastSampleAtMs = atMs
        // 連続している長さを数える。**崩れたら数え直す**（項目25）。
        if (judge()) {
            if (degradedSinceMs == null) degradedSinceMs = atMs
        } else {
            degradedSinceMs = null
        }
    }

    /**
     * プローブ1周期の結果を受け取る。**到達できなかった周期のサンプルも入れる**
     * （実測の所要時間。1回目が失敗した周期は1個だけ。[ProbeOutcome] の KDoc）。
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
        // 項目25: 測る対象が変わった＝これからトンネルが張られる（または張り替わった）。
        // 直後のバーストを窓に入れないため、抑制を掛け直す。
        suppressing = true
        suppressFromMs = null
        degradedSinceMs = null
        lastSampleAtMs = null
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
     * **捨てると何が壊れるか（条件つき）。** 一時的な読み取り失敗が1回あるだけで、最大
     * 60 秒ぶんの応答時間の証拠が消える。すると (1) 判定は受信の窓が埋まり直しても
     * 応答時間が貯まり直すまで偽のまま、(2) 一周の記録が [rttSamples] から読む
     * サンプルが足りなくなり、その候補は最良の選定（サンプル数の門）から静かに外れる。
     *
     * **ただし既定の設定ではほぼ効かない。** 受信の窓は最古と最新の差が 60 秒に
     * 満たないあいだ平均を返さない（`SlowLinkDetector.windowAverage`）ので、読み取りが
     * 失敗したあと判定が真に戻るには、どのみち 60 秒ぶんの受信の再計測が要る。既定の
     * プローブ間隔 30 秒なら、その 60 秒のあいだに応答時間は 10 サンプルまで貯まり直す。
     * 判定が偽→真になる時点も、一周の記録が読む時点も、判定が真だった直後であり、
     * それは受信の窓が埋まっていることを含意する。**効いてくるのは、プローブ間隔が
     * 60 秒に近づく・超える設定のとき**である（間隔 60 秒で 60 秒の窓に**最悪** 5 サンプル
     * ＝記録の最小サンプル数。刈りは「窓より厳密に古い」ものだけなので周期が2つ入る
     * ことがあり、最大 10。120 秒では 0〜5 サンプルで応答時間の側が律速になる。
     * 間隔は 10〜120 秒で設定できる）。
     *
     * トンネルの張り替え（インターフェースの入れ替わり）には使わない。あちらは
     * 測る対象が変わるので [reset] である。
     */
    fun resetThroughput() {
        rx.reset()
        // **連続成立の計時も捨てる。** 受信の窓が無いあいだ [judge] は偽なので、
        // 計時を残すと「窓を捨てたのに判定が真のまま」になる（試験が捕らえた）。
        degradedSinceMs = null
        // **抑制には触らない。** これは「同じトンネルでバイト数が1 tick 読めなかった」
        // だけで、接続直後のバーストは無い（裁定17）。ここで抑制を掛け直すと、
        // 読み取りが一度失敗するたびに検知が30秒遅れる。
    }

    /** 窓の中の応答時間のサンプル（一周の記録が使う。[LapRecord]）。 */
    fun rttSamples(): List<Long> = rtt.map { it.rttMs }

    /**
     * 項目25 の続き: **判定が連続 [SUSTAIN_MS] ぶん成立していなければ発火させない。**
     *
     * **なぜ。** 2026-10-10 の N-1 3回目（抑制を入れたあと）でも不合格だった。
     * 接続直後のバーストは抑制で外せたが、**端末は待機中にも自発的にバーストを出す**
     * （実測 247.8 / 259.2 kbps。接続から7分後・14分後で、接続とは無関係）。
     * 抑制はこれには効かない。一方、観測したバーストはいずれも**20秒ほどの単発**だった。
     * 単発は窓（60秒）に一度しか入らないので、**連続2窓ぶんは成立し続けられない。**
     *
     * **連続の計時は [onSample] の時刻で行う。** 判定器は時計を持たないという作法を
     * 崩さないため、呼び出し側に時刻を渡させない（この関数の形も変えない）。
     * プローブ（[onProbe]）だけでは計時を動かさない——次の採取で拾う。
     * 採取は画面点灯中5秒・待機中30秒間隔なので、120秒を測るには充分である。
     *
     * **代償。** 切替後の最短検知時間が、抑制30秒＋窓60秒＋連続120秒で**約3分半**になる。
     * 検知の速さより待機中の静けさを採るという判断である（仕様書 §2）。
     */
    fun isDegraded(): Boolean {
        val since = degradedSinceMs ?: return false
        val last = lastSampleAtMs ?: return false
        return last - since >= SUSTAIN_MS
    }

    /** その瞬間に条件が揃っているか。連続性は見ない（[isDegraded] が見る）。 */
    private fun judge(): Boolean {
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

    companion object {
        /**
         * 窓の長さ（秒）。**利用者が変えられる項目にしない。**
         * 検知までの時間は仕様書と画面の文面に出ており（画面では
         * `tv.SettingsText.pathQualitySummary` が「〜状態が 60 秒続いたときに切り替えます」と
         * 利用者に伝える）、ここを可変にすると二重管理になる。仕様書 §2-A のとおり、
         * **比較の記録を増やすのは窓を長くすることではなく採取回数を増やすこと**である。
         *
         * **外から読めるのは、画面の文面がこの値から導かれるため**
         * （`tv.SettingsText.pathQualitySummary`）。読めることと変えられることは別で、
         * 設定項目（[SlowLinkSettings]）には出していない。文面に `60` を直書きすると、
         * 窓を変えたとき画面だけが古い値を言い続ける。
         */
        const val WINDOW_SEC = 60

        /**
         * 項目25: `reset()` のあと受信のサンプルを窓に入れない時間（ミリ秒）。
         *
         * **30秒の根拠は実測1件である。** 2026-10-10 の実機で観測したバーストは
         * 23秒だった。余裕を見て30秒にしてあるが、**一度の測定から選んだ値**であり、
         * 別の端末・別の時間帯でより長いバーストが出れば足りない。
         * 足りなければこの値を上げること——ただし検知がそのぶん遅くなる。
         */
        const val SUPPRESS_AFTER_RESET_MS = 30_000L

        /**
         * 項目25 の続き: 判定が**連続して**成立していなければならない長さ（ミリ秒）。
         *
         * **窓2つぶん（120秒）である。** 観測した待機中のバーストはいずれも20秒ほどの
         * 単発で、窓（60秒）に一度しか入らない。窓から抜ければ平均が下限を割って
         * 判定が偽になるので、**単発では120秒続けられない。**
         *
         * **効かない場合を正直に書く。** 20秒より遥かに長く続くバーストには効かない。
         * 観測できたのが単発だっただけで、常にそうだという保証は無い。
         */
        const val SUSTAIN_MS = WINDOW_SEC * 2 * 1_000L
    }
}
