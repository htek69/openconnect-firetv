package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
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

    /**
     * 受信 [rxKbps] と応答時間 [rtt] を**判定が出るまで**流す。
     *
     * **なぜ 190 秒ぶんなのか（元は 60 秒だった）。** 項目25 の修正で
     * **判定は連続 [PathQualityDetector.SUSTAIN_MS]（120秒）成立しなければ
     * 発火しない**ようになった。窓が埋まるのが 60 秒なので、発火するのは
     * 180 秒あたりである。**ここを短くするとこのファイルの対照（`assertTrue`）が
     * 一斉に落ちる**——値の試験が時間の試験に化けてしまうので、短くしないこと。
     * 時間そのものを見る試験は `続いている通信では連続2窓ぶん経ってから発火する` にある。
     */
    private fun fill(d: PathQualityDetector, rxKbps: Long, rtt: List<Long>) {
        var rx = 0L
        var atMs = 0L
        while (atMs <= 190_000L) {
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = rtt))
            rx += rxKbps * 5_000L / 8
            atMs += 5_000L
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
    fun `reset は受信の窓を捨てる（応答時間が新しくても発火しない）`() {
        // reset が応答時間の窓しか捨てないと、古い受信の窓が残って、
        // 直後に入れた新しい応答時間と合わさって発火する。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.isDegraded())
        d.reset()
        d.onProbe(60_000L, ProbeOutcome(reachable = true, rttMs = listOf(300L, 90L, 600L)))
        assertFalse(d.isDegraded())
    }

    @Test
    fun `reset は応答時間の窓を捨てる（受信が帯に入っていても発火しない）`() {
        // reset が受信の窓しか捨てないと、古い応答時間（t=60_000 のぶんは
        // 新しい窓にも残る時刻）が残って、受信の帯が満たされた時点で発火する。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.isDegraded())
        d.reset()
        var rx = 0L
        for (i in 0..12) {
            d.onSample(60_000L + i * 5_000L, IfaceBytes(rx, 0L))
            rx += 424L * 5_000L / 8
        }
        assertFalse(d.isDegraded())
    }

    @Test
    fun `reset は応答時間のサンプルそのものを空にする（一周の記録が読む側）`() {
        // 上の2つは判定の真偽で間接的に見ている。一周の記録が実際に読むのは
        // rttSamples なので、そこを直接固定する。reset が応答時間を残す実装
        // （受信だけ捨てる実装）なら空にならず落ちる。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        assertTrue(d.rttSamples().isNotEmpty())
        d.reset()
        assertTrue(d.rttSamples().isEmpty())
    }

    // ---- resetThroughput: バイト数が読めなかった tick（裁定R20 は受信の窓の話） ----

    /** [fill] の直後（t=60_000）から、受信だけを 13 サンプル（span 60秒）与え直す。 */
    /**
     * 受信だけを埋め直す（[rtt] を渡せば応答時間も与える）。
     * **連続2窓ぶん（項目25）を超えるまで流す**ので、条件が揃うなら発火まで行く。
     * 開始は [fill] の終端（190秒）より後である——古い時刻のサンプルは
     * `SlowLinkDetector.onSample` が捨てるので、時刻を戻してはいけない。
     */
    private fun refillRxOnly(d: PathQualityDetector, rxKbps: Long, rtt: List<Long>? = null) {
        var rx = 0L
        var atMs = 195_000L
        while (atMs <= 400_000L) {
            d.onSample(atMs, IfaceBytes(rx, 0L))
            if (rtt != null && atMs % 30_000L == 0L) {
                d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = rtt))
            }
            rx += rxKbps * 5_000L / 8
            atMs += 5_000L
        }
    }

    /**
     * 窓（60秒）ぶんだけ流す。**窓の中身**を見る試験のためのヘルパで、
     * 判定の発火は見ない（発火には連続2窓が要るので [fill] を使う）。
     * 元の `fill` はこの長さだったので、中身の期待値はこちらに残す。
     */
    private fun fillWindow(d: PathQualityDetector, rxKbps: Long, rtt: List<Long>) {
        var rx = 0L
        for (i in 0..12) {
            val atMs = i * 5_000L
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = rtt))
            rx += rxKbps * 5_000L / 8
        }
    }

    /**
     * 読み取り失敗のあと、受信の窓が埋まり直せば発火する。
     *
     * **項目25 の修正で、この試験の意味が狭まったことを記録する。** 元はここで
     * 「`resetThroughput` が**残した**応答時間が判定へ効くこと」を見ていた。
     * しかし連続2窓（120秒）を要求するようになったので、受信が埋まり直して
     * 発火するまでに**応答時間の窓（60秒）は完全に入れ替わる**。
     * つまり**残した応答時間だけで発火させることは、もう作れない。**
     * 残っていること自体は `resetThroughput は応答時間のサンプルを残す` が見ている。
     * ここで見るのは「読み取り失敗が恒久的な無効化にならない」ことである。
     */
    @Test
    fun `読み取り失敗のあと受信の窓が埋まり直せば発火する`() {
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        d.resetThroughput()
        assertFalse("受信が測れないあいだは偽", d.isDegraded())
        refillRxOnly(d, rxKbps = 424, rtt = listOf(300L, 90L, 600L))
        assertTrue("埋まり直せば発火すること", d.isDegraded())
    }

    @Test
    fun `resetThroughput のあと受信の窓が埋まり直しても、応答時間が良ければ発火しない`() {
        // 上の対照。「窓が埋まり直したら無条件に真」になる実装を弾く。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(70L, 74L, 73L))
        d.resetThroughput()
        refillRxOnly(d, rxKbps = 424)
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

    // ---- 窓より古い応答時間では判定しない ----

    @Test
    fun `プローブが止まったあと窓を過ぎた応答時間では発火しない`() {
        // 守るもの: 判定は窓より古い証拠で生き残ってはならない。受信側の
        // SlowLinkDetector は最古と最新の差が古すぎれば偽に戻す（STALE_SPAN_FACTOR）。
        // 応答時間側にも同じ保護が要る。プローブが止まっても採取（onSample）は
        // 続くので、onSample の時刻で応答時間の窓も刈らないと、t=0 の
        // 応答時間が受信の帯が満たされるかぎり判定を駆動し続ける。
        val d = PathQualityDetector(settings)
        d.onProbe(0L, ProbeOutcome(reachable = true, rttMs = listOf(300L, 90L, 600L)))
        var rx = 0L
        for (i in 0..36) {
            d.onSample(i * 5_000L, IfaceBytes(rx, 0L))
            rx += 424L * 5_000L / 8
        }
        // t=180_000。受信の窓は埋まり帯の中、応答時間は 180 秒前のものだけ。
        assertFalse(d.isDegraded())
    }

    // ---- 境界（厳密な大小）。各ケースは境界ちょうどで発火しないことを見て、
    //      境界の1つ外側（対照）では発火することで、その境界が効いていることを示す ----

    @Test
    fun `応答時間の中央値がちょうど閾値なら発火しない`() {
        // 厳密な「超える」。中央値 250 == degradedRttMs、ばらつき 400 は十分大きい。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(50L, 250L, 450L))
        assertFalse(d.isDegraded())
        val control = PathQualityDetector(settings)
        fill(control, rxKbps = 424, rtt = listOf(50L, 251L, 450L))
        assertTrue(control.isDegraded())
    }

    @Test
    fun `ばらつきがちょうど閾値なら発火しない`() {
        // 厳密な「超える」。最大−最小 150 == degradedJitterMs、中央値 400 は十分大きい。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 424, rtt = listOf(300L, 400L, 450L))
        assertFalse(d.isDegraded())
        val control = PathQualityDetector(settings)
        fill(control, rxKbps = 424, rtt = listOf(300L, 400L, 451L))
        assertTrue(control.isDegraded())
    }

    @Test
    fun `受信の窓の平均がちょうど上限なら発火しない`() {
        // 厳密な「未満」。fill は 12 区間 x 625 x kbps バイトを 60 秒で割るので
        // 平均はちょうど rxKbps になる（端数なし）。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 1000, rtt = listOf(300L, 90L, 600L))
        assertFalse(d.isDegraded())
        val control = PathQualityDetector(settings)
        fill(control, rxKbps = 999, rtt = listOf(300L, 90L, 600L))
        assertTrue(control.isDegraded())
    }

    @Test
    fun `受信の窓の平均がちょうど下限なら発火しない`() {
        // 厳密な「より大きい」。
        val d = PathQualityDetector(settings)
        fill(d, rxKbps = 50, rtt = listOf(300L, 90L, 600L))
        assertFalse(d.isDegraded())
        val control = PathQualityDetector(settings)
        fill(control, rxKbps = 51, rtt = listOf(300L, 90L, 600L))
        assertTrue(control.isDegraded())
    }

    // ---- rttSamples の中身 ----

    @Test
    fun `rttSamples は窓の中のサンプルをそのまま返す`() {
        val d = PathQualityDetector(settings)
        fillWindow(d, rxKbps = 424, rtt = listOf(300L, 90L))
        // プローブは t=0, 30_000, 60_000 の3周期。
        assertEquals(listOf(300L, 90L, 300L, 90L, 300L, 90L), d.rttSamples())
    }

    @Test
    fun `rttSamples に窓より古いサンプルは残らない`() {
        val d = PathQualityDetector(settings)
        d.onProbe(0L, ProbeOutcome(reachable = true, rttMs = listOf(1L, 2L)))
        d.onProbe(70_000L, ProbeOutcome(reachable = true, rttMs = listOf(3L, 4L)))
        assertEquals(listOf(3L, 4L), d.rttSamples())

        // 採取だけが進んだ場合も同じ（onSample の時刻で刈る）。
        d.onSample(70_000L + 61_000L, IfaceBytes(0L, 0L))
        assertTrue(d.rttSamples().isEmpty())
    }

    // ---- 項目25: 接続直後のバーストを最初の窓に入れない（2026-10-10 の N-1 不合格） ----

    /**
     * 実機 2026-10-10 の N-1 で起きた形を固定する。トンネルが上がった直後の
     * 23秒間に約504 KB（175 kbps）のバーストが来て、そのあとは 0.3 kbps だった。
     * バーストは**利用者の通信ではない**（接続性の検証・名前解決・同期の再開）。
     * 旧実装はこのバーストで窓が帯の内側に入り、待機中に切り替わった。
     */
    @Test
    fun `接続直後のバーストは受信の窓に入らない`() {
        val d = PathQualityDetector(settings)
        d.reset()
        var rx = 0L
        // 0〜25秒: 175 kbps のバースト（実機 2026-10-10 の実測。約504 KB）
        for (i in 0..5) {
            d.onSample(i * 5_000L, IfaceBytes(rxBytes = rx, txBytes = 0L))
            rx += 175L * 5_000L / 8
        }
        // 25〜60秒: 待機中（実測 0.3 kbps はバイトに丸めると実質0）。応答時間は劣化している
        for (i in 6..12) {
            val atMs = i * 5_000L
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) {
                d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = listOf(700L, 300L, 900L, 650L, 800L)))
            }
        }
        // **ここが欠陥の瞬間である。** 窓は span 60 秒ぶん埋まり、その中身は
        // ほぼ全部が接続直後のバースト（約504 KB → 約72 kbps で帯の内側）。
        // 抑制が無いと判定が真になり、待機中に切り替わる（項目25）。
        assertFalse("接続直後のバーストで待機中に発火してはならない", d.isDegraded())
    }

    /**
     * 抑制は**候補が変わったとき**だけに掛かる。採取が続いている最中の
     * 読み取り失敗（[PathQualityDetector.resetThroughput]）では掛からない——
     * あれは同じトンネルでバイト数が読めなかっただけで、バーストは無い（裁定17）。
     */
    @Test
    fun `読み取り失敗の窓の捨て直しでは抑制が掛からない`() {
        val d = PathQualityDetector(settings)
        // まず抑制を抜けるまで採取する（reset から 30 秒以上）
        var rx = 0L
        for (i in 0..7) {
            d.onSample(i * 5_000L, IfaceBytes(rxBytes = rx, txBytes = 0L))
            rx += 424L * 5_000L / 8
        }
        // 読み取り失敗で受信の窓だけ捨てる
        d.resetThroughput()
        // 直後から 60 秒ぶん採取すれば平均が出る（抑制が掛かっていたら出ない）
        val base = 40_000L
        rx = 0L
        // 連続2窓ぶん（項目25）まで流す。抑制が掛かっていればここでも偽になる。
        for (i in 0..50) {
            val atMs = base + i * 5_000L
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) {
                d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = listOf(300L, 90L, 600L, 280L, 310L)))
            }
            rx += 424L * 5_000L / 8
        }
        assertTrue("読み取り失敗のあとは抑制せず、60秒で判定できること", d.isDegraded())
    }

    /**
     * 抑制の起点は**リセット後の最初のサンプルの時刻**である。判定器は時計を
     * 持たないので、`reset()` が呼ばれた時刻そのものは知りえない。
     */
    @Test
    fun `抑制の起点はリセット後の最初のサンプルである`() {
        val d = PathQualityDetector(settings)
        d.reset()
        // 最初のサンプルが t=1,000,000 でも、そこから 30 秒は抑制される
        val t0 = 1_000_000L
        var rx = 0L
        for (i in 0..5) {
            d.onSample(t0 + i * 5_000L, IfaceBytes(rxBytes = rx, txBytes = 0L))
            rx += 424L * 5_000L / 8
        }
        // t0+25秒までは抑制中なので、ここで 60 秒ぶん足しても平均は出ない
        for (i in 6..12) {
            val atMs = t0 + i * 5_000L
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = listOf(300L, 90L, 600L, 280L, 310L)))
            rx += 424L * 5_000L / 8
        }
        // 抑制後に窓が埋まるのは t0+30秒 から 60 秒後＝t0+90秒。まだ届かない。
        assertFalse("抑制の 30 秒を含めて窓を数えてはならない", d.isDegraded())
    }


    // ---- 項目25 の続き: 単発のバーストでは発火させない（連続2窓を要求する） ----

    /**
     * 5秒ごとに採取し、30秒ごとにプローブを与える。累計バイトは [rxKbps] で進める。
     * 戻り値は最後に与えた時刻。
     */
    private fun drive(
        d: PathQualityDetector,
        fromMs: Long,
        toMs: Long,
        rxKbps: Long,
        rtt: List<Long>,
        rxStart: Long = 0L,
    ): Long {
        var rx = rxStart
        var atMs = fromMs
        while (atMs <= toMs) {
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = rtt))
            rx += rxKbps * 5_000L / 8
            atMs += 5_000L
        }
        return atMs - 5_000L
    }

    private val degradedRtt = listOf(700L, 300L, 900L, 650L, 800L)

    /**
     * 実機 2026-10-10 の3回目の N-1 で起きた形を固定する。接続とは無関係な時刻に
     * **20秒ほどの単発のバースト**（実測 247.8 / 259.2 kbps）が出て、そこで切り替わった。
     * バーストは窓（60秒）に一度しか入らないので、**判定が連続2窓ぶん成立しない。**
     */
    @Test
    fun `待機中の単発のバーストでは発火しない`() {
        val d = PathQualityDetector(settings)
        // 0〜20秒だけ 247 kbps、その後は待機（累計を進めない）
        var rx = 0L
        var atMs = 0L
        while (atMs <= 20_000L) {
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = degradedRtt))
            rx += 247L * 5_000L / 8
            atMs += 5_000L
        }
        // 25秒以降は待機（受信0）。応答時間は劣化したまま与え続ける
        while (atMs <= 300_000L) {
            d.onSample(atMs, IfaceBytes(rxBytes = rx, txBytes = 0L))
            if (atMs % 30_000L == 0L) d.onProbe(atMs, ProbeOutcome(reachable = true, rttMs = degradedRtt))
            assertFalse("単発のバーストで発火してはならない（t=$atMs）", d.isDegraded())
            atMs += 5_000L
        }
    }

    /**
     * 続いている通信では発火する。ただし**窓が埋まった瞬間ではなく、
     * そこから連続2窓ぶん（120秒）成立し続けてから**である。
     */
    @Test
    fun `続いている通信では連続2窓ぶん経ってから発火する`() {
        val d = PathQualityDetector(settings)
        // 窓が埋まる（span 60秒）まで
        drive(d, 0L, 60_000L, rxKbps = 424, rtt = degradedRtt)
        assertFalse("窓が埋まった瞬間に発火してはならない", d.isDegraded())
        // さらに 115 秒（合計 175 秒）ではまだ足りない
        val rxAt60 = 424L * 5_000L / 8 * 13
        drive(d, 65_000L, 175_000L, rxKbps = 424, rtt = degradedRtt, rxStart = rxAt60)
        assertFalse("120秒に届く前に発火してはならない", d.isDegraded())
        // 合計 185 秒まで続けると、成立し始めた 60 秒から 120 秒を超える
        val rxAt175 = 424L * 5_000L / 8 * 36
        drive(d, 180_000L, 185_000L, rxKbps = 424, rtt = degradedRtt, rxStart = rxAt175)
        assertTrue("連続2窓ぶん成立したら発火すること", d.isDegraded())
    }

    /**
     * 連続していることが要点である。途中で条件が崩れたら、**数え直す。**
     */
    @Test
    fun `条件が途中で崩れたら連続の計時は数え直す`() {
        val d = PathQualityDetector(settings)
        // 90 秒ぶん成立させる（まだ 120 秒には足りない）
        drive(d, 0L, 150_000L, rxKbps = 424, rtt = degradedRtt)
        assertFalse(d.isDegraded())
        // 応答時間が健全な値に戻る＝条件が崩れる
        val rxAt150 = 424L * 5_000L / 8 * 31
        drive(d, 155_000L, 215_000L, rxKbps = 424, rtt = listOf(70L, 74L, 73L, 71L, 75L), rxStart = rxAt150)
        assertFalse("崩れたのだから発火しない", d.isDegraded())
        // 再び劣化させる。崩れた時点から 120 秒を数え直すので、60 秒では足りない
        val rxAt215 = 424L * 5_000L / 8 * 44
        drive(d, 220_000L, 280_000L, rxKbps = 424, rtt = degradedRtt, rxStart = rxAt215)
        assertFalse("数え直しの途中で発火してはならない", d.isDegraded())
    }

}
