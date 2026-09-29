package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random

/**
 * [SlowLinkDetector] の**性質テスト**（手書きシナリオではない）。
 *
 * 既存の `SlowLinkDetectorTest` の14件はすべて「この列を流したらこうなる」を1本ずつ
 * 書いたもので、**書いた人が思いついた組み合わせしか守らない。** この判定器は
 * 裁定R20 で**判定規則そのものを後から書き換えた**箇所（「5秒ごとの小区間が全部
 * 床を下回る」→「窓全体の平均で判定」）であり、書き換えで感度が上がった結果
 * 仕様書§6-1 の誤判定が主経路になっている。ここではサンプル列を機械生成して流し、
 * **どの列でも破れてはならない性質**を**毎サンプル後に**検査する。
 *
 * 依存は増やさない（kotest / jqwik は使わない）。[Random] のシードは固定配列
 * （[SEEDS]）なので、破れたら同じ列が同じ順番で再現する。破れたときは全列と、
 * 削れるところまで削った最小列の両方を出す。
 *
 * **閾値は既定値だけで終わらせない**（[THRESHOLDS]）。窓の長さ・受信の床・送信の
 * 需要をずらした7組で同じ性質を検査する。
 *
 * ## 判定を写さずに性質を書くための道具
 *
 * 性質の前提（「窓の平均が床未満」など）は、**製品の整数演算を写さずに**
 * [BigInteger] の厳密な比較で書く（[rateAtLeast] / [rateBelow]）。製品は
 * `dRx * 8 * 1000 / spanMs / 1000` すなわち **floor(8*dRx/spanMs)** を kbps として
 * 使うので、整数の閾値 F に対して
 *
 * - `floor(8*dRx/span) < F` ⟺ `8*dRx < F*span`（F が整数なので厳密に同値）
 * - `floor(8*dTx/span) > D` ⟺ `8*dTx >= (D+1)*span`（同上）
 *
 * が成り立つ。前提はこの右辺で書くので、**製品の丸め方を前提に埋め込んでいない**
 * （kbps という単位の粒度そのものは仕様が決めていることなので、送信側の
 * 「超える」は D と D+1 のあいだの帯を含まない——そこは判定器が整数 kbps で
 * 比較する以上どちらとも言えない帯であり、生成器もその帯を狙わない）。
 *
 * 窓の中身（[SlowLinkDetector] の private な `samples`）は覗かない。判定に使われる
 * 最古のサンプルは、KDoc に書かれた窓の規則そのもの——「`atMs <= 最新 - 窓` を
 * 満たす最後のサンプル（無ければ判定は出ない）」——から入力列だけで導く
 * （[oldestInWindow]）。時刻が厳密に増えていてカウンタが巻き戻っていない列に
 * 限って使うので、この導出は一意である。
 */
class SlowLinkDetectorPropertyTest {

    // ------------------------------------------------------------------ 入力

    /** 1回の採取。すべて**絶対値**なので、途中の手を落としても残りの意味が変わらない。 */
    private data class Tick(val atMs: Long, val rxBytes: Long, val txBytes: Long) {
        fun render(): String = "d.onSample(${atMs}L, IfaceBytes(${rxBytes}L, ${txBytes}L))"
    }

    /**
     * 生成する列の種類。**実際に起きうる列だけ**を作る——起きえない入力で落ちても
     * 偽の失敗になり、裁定する人の時間を無駄にするだけである。
     *
     * - [REALISTIC]: 実機のティック（点灯中5秒・消灯中30秒）にゆらぎを付けた列。
     * - [ROLLBACK]: 上に加えてカウンタの巻き戻し（トンネル作り直し・受信のみの巻き戻し）。
     * - [GAP]: 上に加えて採取が数分止まる（`/proc/net/dev` が読めない等）。
     * - [ALL_FAST]: **どの区間も受信速度が床以上**の列（P4 の前提を狙い撃ちする）。
     * - [HUGE_JUMP]: [ALL_FAST] に前方への巨大な飛びを混ぜる。上限は物理から
     *   導いた [MAX_PRODUCIBLE_DELTA]（3PB）で、**旧式の桁あふれの境
     *   （1.15PB）の両側**を狙う（P4b / P5b。半分は「受信は床未満・送信だけが
     *   巨大」の形にして、取りこぼしの側からも境をまたぐ）。
     * - [HOSTILE]: 時刻の重複・逆行、カウンタの減少、0 と Long の上限近く。
     * - [SHORT]: 窓の長さに届かない幅しか持たない列（P1 の前提）。
     */
    private enum class Mode { REALISTIC, ROLLBACK, GAP, ALL_FAST, HUGE_JUMP, HOSTILE, SHORT }

    private data class Case(
        val seed: Long,
        val index: Int,
        val mode: Mode,
        val thresholds: SlowLinkThresholds,
        val ticks: List<Tick>,
    ) {
        fun withTicks(next: List<Tick>): Case = copy(ticks = next)
    }

    // ------------------------------------------------------------ 破れた記録

    private data class Violation(
        val property: String,
        val kind: String,
        val detail: String,
        val case: Case,
        val atStep: Int,
        val state: String,
    ) {
        val key: String get() = "$property/$kind"
    }

    // ------------------------------------------------------------ 検査の入口

    @Test
    fun `P1 窓が埋まる前はどんな列でも遅いと判定しない`() = assertHolds("P1")

    @Test
    fun `P2 例外を投げない`() = assertHolds("P2")

    @Test
    fun `P3 巻き戻りの後は新しい窓が丸ごと埋まるまで判定しない`() = assertHolds("P3")

    /**
     * **誤検知が無いこと。** 窓内のどの区間も受信速度が床以上なら遅くない。
     * これは数学的にも成り立つ（各区間で `8*dRx_i >= F*dt_i` なら、足し合わせて
     * `8*ΣdRx >= F*Σdt`、すなわち窓の平均も F 以上）。**破れたら実装側の
     * 誤りである可能性が高い。**
     *
     * 前提は生成器の意図ではなく**実際に生成された列から**計算する
     * （[checkNoFalsePositive]）ので、生成器が意図どおりに作れていなくても
     * 偽の失敗にはならない。
     */
    @Test
    fun `P4 窓のどの区間も床以上なら誤検知しない 実機の速度域`() = assertHolds("P4")

    /**
     * P4 と**同じ性質**を、64bit 演算があふれる域の入力に対して検査する。
     * 性質は1文字も緩めていない。分けてあるのは入力の域が別で、裁定の材料が
     * 違うからである（実機の速度域は P4、`/proc/net/dev` が極端な値を返した
     * 場合の前方への飛びはこちら）。
     *
     * 製品は `dRx * 8 * 1000` を作るので、窓のデルタが
     * `Long.MAX_VALUE / 8000`（= [SAFE_DELTA] ≒ 1.15e15 バイト）を超えると
     * 桁あふれする。**あふれる域で誤検知が出るならこのテストは落ちる。**
     * 落ちたまま報告する（直すか、域の外と見なすかは依頼者の裁定）。
     */
    @Test
    fun `P4b 64bit があふれる域でも誤検知しない`() = assertHolds("P4b")

    /**
     * **取りこぼしが無いこと。** 裁定R20 の規則そのもの: 窓が埋まっていて
     * （かつ古すぎず）、窓全体の平均受信が床未満・平均送信が需要超なら遅い。
     */
    @Test
    fun `P5 窓の平均が遅ければ取りこぼさない`() = assertHolds("P5")

    /**
     * P5 と**同じ性質**を、64bit 演算があふれる域（窓のデルタが [SAFE_DELTA] 超）に
     * 対して検査する。P4b と同じ理由で分けてある。
     */
    @Test
    fun `P5b 64bit があふれる域でも取りこぼさない`() = assertHolds("P5b")

    @Test
    fun `P6 reset は初期状態に戻す`() = assertHolds("P6")

    @Test
    fun `P7 窓の2倍を超える幅の古い証拠で判定しない`() = assertHolds("P7")

    @Test
    fun `P8 同じ列なら判定は完全に一致し isSlow は状態を変えない`() = assertHolds("P8")

    /**
     * **窓平均の式の書き換えが判定を変えないことの証明。**
     *
     * `SlowLinkDetector.isSlow()` は以前 `d * 8 * 1000 / spanMs / 1000` と書いていた
     * ものを `d * 8 / spanMs` に変えた（P4b / P5b が見つけた桁あふれの余裕を
     * 3桁ぶん取り戻すため）。非負の整数では両者は厳密に等しい——
     * `a = q*b + r`（`0 <= r < b`）とおくと
     * `floor(a*1000/b) = 1000q + floor(1000r/b)` で `0 <= floor(1000r/b) <= 999`
     * なので、さらに 1000 で割ると繰り上がり無しで `q` に戻る。
     *
     * **その等式を主張ではなく検査として置く。** 旧式があふれない域
     * （デルタ <= [SAFE_DELTA]）の全入力で、
     *
     * 1. 縁（0・1・7・8・999・1000・1001・[SAFE_DELTA] の前後）と無作為の組、
     * 2. **この性質テストが生成する列が実際に作る窓**（[allCases] を流し直して、
     *    各時点の窓の `dRx` / `dTx` と幅をそのまま使う）
     *
     * の両方について、旧式・新式・[BigInteger] による厳密な `floor(8d/span)` の
     * 3つが**完全に一致する**ことを要求する。将来「`* 1000` を書き戻しても
     * 同じでは？」と考えた読者がここを読めば、同じではない理由（余裕が3桁減る）と
     * 同じである理由（値は変わらない）の両方が分かる。
     */
    @Test
    fun `P9 窓平均の式の書き換えは判定を変えない`() {
        var pairs = 0
        val mismatches = mutableListOf<String>()

        fun compare(dBytes: Long, spanMs: Long, where: String) {
            // 旧式があふれる域は比較の対象にならない（あふれた値と比べても意味が無い）。
            if (dBytes < 0 || dBytes > SAFE_DELTA || spanMs <= 0) return
            pairs++
            val old = dBytes * 8 * 1000 / spanMs / 1000
            val new = dBytes * 8 / spanMs
            val exact = BigInteger.valueOf(dBytes).multiply(EIGHT).divide(BigInteger.valueOf(spanMs))
            if (old != new || BigInteger.valueOf(new) != exact) {
                if (mismatches.size < 10) {
                    mismatches.add("d=$dBytes span=$spanMs 旧式=$old 新式=$new 厳密=$exact （$where）")
                }
            }
        }

        // (1) 縁の総当たりと無作為の組。
        val edgeDeltas = listOf(
            0L, 1L, 7L, 8L, 9L, 999L, 1_000L, 1_001L, 8_191L, 8_192L,
            125_000L, 1_000_000L, 1_000_000_007L,
            SAFE_DELTA - 1, SAFE_DELTA, SAFE_DELTA / 2, SAFE_DELTA / 8,
        )
        val edgeSpans = listOf(
            1L, 2L, 7L, 8L, 999L, 1_000L, 1_001L, 4_999L, 5_000L, 30_000L,
            60_000L, 60_001L, 119_999L, 120_000L, 3_600_000L, 86_400_000L,
        )
        for (d in edgeDeltas) for (span in edgeSpans) compare(d, span, "縁")
        for (seed in SEEDS) {
            val rnd = Random(seed)
            repeat(500) {
                compare(rnd.nextLong(0, SAFE_DELTA), rnd.nextLong(1, 200_000), "無作為")
            }
        }

        // (2) 生成された列が実際に作る窓。判定がもたれる (デルタ, 幅) の組そのもの。
        for (case in allCases()) {
            val windowMs = case.thresholds.windowSec * 1_000L
            case.ticks.forEachIndexed { i, tick ->
                val oldestIndex = oldestInWindow(case.ticks, i, windowMs) ?: return@forEachIndexed
                val oldest = case.ticks[oldestIndex]
                val span = tick.atMs - oldest.atMs
                compare(tick.rxBytes - oldest.rxBytes, span, "生成された窓 seed=${case.seed} 列=${case.index}")
                compare(tick.txBytes - oldest.txBytes, span, "生成された窓 seed=${case.seed} 列=${case.index}")
            }
        }

        assertTrue(
            "旧式と新式が食い違う組がある（$pairs 組を突き合わせた）。" +
                "**製品コードを直してはならない。性質を緩めてもならない。**\n  " +
                mismatches.joinToString("\n  "),
            mismatches.isEmpty(),
        )
        assertTrue("突き合わせた組が少なすぎる（$pairs 組）。生成器が窓を作れていない", pairs > 10_000)
    }

    /**
     * **桁あふれの境を知識として記録する。** これは性質ではない——境の**向こう側**で
     * 正しく振る舞うことは要求しない（要求できない。あふれた積に意味は無い）。
     * 要求するのは「境がどこにあるか」と「生成器がその内側にとどまっていること」である。
     *
     * ## 経緯（「この計算はあふれないのか？」と考えた読者のために）
     *
     * 最初の提出でこの性質テストは P4b / P5b の破れを報告した。原因は当時の
     * `dRx * 8 * 1000 / spanMs / 1000` の `* 1000` で、中間結果が1000倍になるため
     * デルタが `Long.MAX_VALUE / 8000`（≒1.15PB）を超えると積があふれ、
     * 受信・送信の速度が無意味な値になって**誤検知と取りこぼしの両方**が出た。
     * 裁定は3点:
     *
     * 1. 式を `dRx * 8 / spanMs` に簡約する（`* 1000` と `/ 1000` は非負整数では
     *    相殺するので、**コードが減って余裕が1000倍になる**）。**等価であることの
     *    証明は P9** が旧式と新式の突き合わせとして持っている。
     * 2. **その桁あふれは実機では到達しない。** 60秒で 1.15PB は約153Tbps を要する。
     * 3. **入力の域の指定（「Long の上限近く」）が誤りだった。** カウンタはカーネルが
     *    単調に増やすので、デルタは実際に流れた通信量で抑えられる。作り直された
     *    インターフェースは小さい値から始まり、それは負のデルタ＝既存の
     *    巻き戻しの門が捨てる場合である。よって生成器の上限を物理から導いた
     *    [MAX_PRODUCIBLE_DELTA] に狭めた（導出はその KDoc）。
     *
     * **赤かったのは製品の欠陥ではなく、入力の域の指定の誤りである。**
     */
    @Test
    fun `桁あふれの境の記録 これを超えるデルタでは演算が厳密でなくなる`() {
        val newBound = Long.MAX_VALUE / 8L // いまの式 `d * 8 / span` の境
        val oldBound = Long.MAX_VALUE / 8_000L // 旧式 `d * 8 * 1000 / span / 1000` の境
        assertEquals("旧式の境は新式の境の 1000 分の1であった", 1_000L, newBound / oldBound)

        // 境の内側では Long の演算が厳密（BigInteger と一致する）。
        for (span in listOf(1L, 999L, 1_000L, 60_000L, 120_000L, 240_000L)) {
            assertEquals(
                "新式は境（$newBound）まで厳密であること",
                BigInteger.valueOf(newBound).multiply(EIGHT).divide(BigInteger.valueOf(span)),
                BigInteger.valueOf(newBound * 8 / span),
            )
            assertEquals(
                "旧式は旧い境（$oldBound）まで厳密であったこと",
                BigInteger.valueOf(oldBound).multiply(EIGHT).divide(BigInteger.valueOf(span)),
                BigInteger.valueOf(oldBound * 8 * 1000 / span / 1000),
            )
        }
        // 境の1つ外では積が回り込む。ここから先の判定に意味は無い（だから性質にしない）。
        assertTrue("新式は境の1つ外で回り込む", (newBound + 1) * 8 < 0L)
        assertTrue("旧式は旧い境の1つ外で回り込んだ", (oldBound + 1) * 8 * 1000 < 0L)

        // 生成器が境の内側にとどまっていること（判定が下される形の列について）。
        // ここが破れたら、性質の破れではなく **[MAX_PRODUCIBLE_DELTA] の導出を
        // 見直す合図**である。
        var windows = 0
        var largest = 0L
        for (case in allCases()) {
            if (case.mode !in JUDGED_MODES) continue
            val windowMs = case.thresholds.windowSec * 1_000L
            case.ticks.forEachIndexed { i, tick ->
                val oldestIndex = oldestInWindow(case.ticks, i, windowMs) ?: return@forEachIndexed
                val oldest = case.ticks[oldestIndex]
                windows++
                largest = maxOf(largest, tick.rxBytes - oldest.rxBytes, tick.txBytes - oldest.txBytes)
            }
        }
        assertTrue("窓を1つも見ていない（生成器が窓を作れていない）", windows > 1_000)
        assertTrue(
            "生成器が新式の境（$newBound）を超える窓のデルタを作っている（最大 $largest）。" +
                "性質の破れではなく、MAX_PRODUCIBLE_DELTA=$MAX_PRODUCIBLE_DELTA の導出を見直すこと",
            largest <= newBound,
        )
        // 旧い境は**またいでいる**こと（またいでいなければ P4b / P5b が空回りする）。
        assertTrue(
            "生成器が旧い境（$oldBound）を超える窓のデルタを1つも作っていない。" +
                "P4b / P5b が空回りしている（境の記録として意味が無い）。最大 $largest",
            largest > oldBound,
        )
    }

    /**
     * **網に歯があることの確認。** 生成器が興味の無いところだけを回っていたら、
     * 性質は「破れない」のではなく「試されていない」だけである。各性質の前提が
     * 実際に踏まれていること、そして**判定が真になる場面に届いていること**を
     * ここで固定する。
     */
    @Test
    fun `生成器は各性質の前提に到達している`() {
        val required = listOf(
            "判定が真になった場面",
            "P1 窓が埋まらない列",
            "P3 巻き戻り直後",
            "P4 全区間が床以上",
            "P4b 全区間が床以上かつ 64bit があふれる域",
            "P5 窓平均が遅い",
            "P5b 窓平均が遅くかつ 64bit があふれる域",
            "P7 窓が古すぎる場面",
        )
        val missing = required.filter { (corpus.coverage[it] ?: 0) == 0 }
        assertTrue(
            "生成器が踏んでいない前提がある: $missing\n" +
                "列数=${corpus.cases} 採取数=${corpus.ticks} 所要=${corpus.elapsedMs}ms\n" +
                "到達記録=${corpus.coverage.toSortedMap()}",
            missing.isEmpty(),
        )
    }

    private fun assertHolds(property: String) {
        val found = corpus.worst.filterKeys { it.startsWith("$property/") }
        if (found.isEmpty()) return
        val message = buildString {
            appendLine(
                "性質 $property が破れた（${found.size} 種類、" +
                    "${corpus.cases} 列 / ${corpus.ticks} 採取中）。" +
                    "**製品コードを直してはならない。性質を緩めてもならない。**",
            )
            for (violation in found.values.sortedBy { it.case.ticks.size }) {
                appendLine()
                appendLine(renderFull(violation))
                appendLine(renderMinimal(shrink(violation)))
            }
        }
        fail(message)
    }

    // ------------------------------------------------------------ 出力と削り込み

    private fun renderFull(violation: Violation): String = buildString {
        appendLine("=== ${violation.key} : ${violation.detail}")
        appendLine("  出現回数: ${corpus.counts[violation.key]}")
        appendLine("  シード: seed=${violation.case.seed} 列=${violation.case.index} 種類=${violation.case.mode}")
        appendLine("  閾値: ${violation.case.thresholds}")
        appendLine("  破れた時点: 第${violation.atStep + 1}手 ${violation.state}")
        appendLine("  --- 生成された列（全 ${violation.case.ticks.size} 手）---")
        appendLine(renderTicks(violation.case))
    }

    private fun renderMinimal(violation: Violation): String = buildString {
        appendLine("  --- 最小の列（削れるところまで削った ${violation.case.ticks.size} 手）---")
        appendLine("  val d = SlowLinkDetector(${violation.case.thresholds})")
        appendLine(renderTicks(violation.case))
        appendLine("  破れた時点: 第${violation.atStep + 1}手 ${violation.state}")
    }

    /** 各手の後ろに、その区間の実効速度（読む人が手で確かめられるように）を添える。 */
    private fun renderTicks(case: Case): String = buildString {
        case.ticks.forEachIndexed { i, tick ->
            append("  ${i + 1}. ${tick.render()}")
            if (i > 0) {
                val prev = case.ticks[i - 1]
                val dt = tick.atMs - prev.atMs
                append("  // dt=${dt}ms")
                if (dt > 0) {
                    append(" rx=${kbpsText(tick.rxBytes - prev.rxBytes, dt)}")
                    append(" tx=${kbpsText(tick.txBytes - prev.txBytes, dt)}")
                }
            }
            appendLine()
        }
    }

    private fun kbpsText(dBytes: Long, dtMs: Long): String {
        if (dtMs <= 0) return "?"
        val kbps = BigInteger.valueOf(dBytes).multiply(EIGHT).divide(BigInteger.valueOf(dtMs))
        return "${kbps}kbps"
    }

    /** 同じ破れ（同じ種別キー）を保ったまま手を落として最小列にする。 */
    private fun shrink(violation: Violation): Violation {
        val check = CHECKS[violation.property] ?: return violation
        val sink = mutableMapOf<String, Int>()
        var best = violation
        var budget = 800
        var chunk = best.case.ticks.size / 2
        while (chunk >= 1 && budget > 0) {
            budget--
            val replayed = replay(check, best.case.withTicks(best.case.ticks.drop(chunk)), violation.key, sink)
            if (replayed != null) best = replayed else chunk /= 2
        }
        var at = 0
        while (at < best.case.ticks.size && budget > 0) {
            budget--
            val reduced = best.case.ticks.toMutableList()
            reduced.removeAt(at)
            val replayed = replay(check, best.case.withTicks(reduced), violation.key, sink)
            if (replayed != null) best = replayed else at++
        }
        return best
    }

    private fun replay(
        check: (Case, MutableMap<String, Int>) -> Violation?,
        case: Case,
        key: String,
        sink: MutableMap<String, Int>,
    ): Violation? {
        if (case.ticks.isEmpty()) return null
        val found = check(case, sink) ?: return null
        return if (found.key == key) found else null
    }

    private companion object {

        /** 固定シード。ここを変えない限り、同じ失敗が同じ手順で再現する。 */
        private val SEEDS = listOf(1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L)
        private const val CASES_PER_SEED = 160

        private val MODES = listOf(
            Mode.REALISTIC, Mode.ROLLBACK, Mode.GAP,
            Mode.ALL_FAST, Mode.HUGE_JUMP, Mode.HOSTILE, Mode.SHORT,
        )

        /**
         * 閾値の組。既定値（実機: 窓60秒・床1000kbps・需要5kbps）に加えて、
         * 設定画面が実際に出せる床の両端（250 / 5000 kbps。`SettingsStepper` の
         * `SLOW_RX_KBPS_MIN` / `SLOW_RX_KBPS_MAX`）と、窓・需要をずらした組を含める。
         */
        private val THRESHOLDS = listOf(
            SlowLinkThresholds(),
            SlowLinkThresholds(windowSec = 60, slowRxKbps = 250),
            SlowLinkThresholds(windowSec = 60, slowRxKbps = 5_000),
            SlowLinkThresholds(windowSec = 10, slowRxKbps = 1_000),
            SlowLinkThresholds(windowSec = 10, slowRxKbps = 250, demandTxKbps = 0),
            SlowLinkThresholds(windowSec = 120, slowRxKbps = 2_500, demandTxKbps = 50),
            SlowLinkThresholds(windowSec = 5, slowRxKbps = 1, demandTxKbps = 1),
        )

        /** 窓が「古すぎる」の境（[SlowLinkDetector] の `STALE_SPAN_FACTOR`）。 */
        private const val STALE_SPAN_FACTOR = 2L

        private val EIGHT: BigInteger = BigInteger.valueOf(8L)

        /**
         * 製品が作る `d * 8 * 1000` が Long に収まるデルタの上限
         * （`Long.MAX_VALUE / 8000` ≒ 1.15e15 バイト = 1.15PB）。
         *
         * これを超えるデルタは**64bit 演算があふれる域**であり、性質の破れを
         * P4/P5（実機の速度域）と P4b/P5b（あふれる域）に分けるのに使う。
         * 実機の回線は窓（最大2分）でこの量を運べない——ここに届くのは
         * `/proc/net/dev` が極端な値を返した場合だけである。
         */
        private val SAFE_DELTA = Long.MAX_VALUE / 8_000L

        /**
         * 累計カウンタを上限近くから始める場合の開始値。足しても Long を
         * 超えない余白（1TB 強）を残す——本物の `/proc/net/dev` のカウンタは
         * 巻き戻らずに増えるだけなので、負にしてはならない。
         */
        private val NEAR_MAX_BASE = Long.MAX_VALUE - (1L shl 40)

        /**
         * **1回の前方への飛び（デルタ）の上限。物理から導く。**
         *
         * 最初の提出では上限を `Long.MAX_VALUE` の近くまで取っていた（依頼書が
         * 「Long の上限近く」を敵対的な縁として名指していたため）。裁定により
         * **これは入力の域の指定が誤りだった**: `/proc/net/dev` のカウンタは
         * カーネルが単調に増やすものなので、デルタは**実際に流れた通信量**で
         * 抑えられる。作り直されたインターフェースは**小さい値**から始まり、
         * それは負のデルタ＝既存の巻き戻しの門が捨てる場合である。
         *
         * 導出:
         *
         * 1. 対象は2017年の Fire TV Stick（実測は数十Mbps）。**この端末が決して
         *    見ない速さ**として **100Gbps** を採る（ギガビット LAN の100倍、
         *    端末の無線の約1000倍）。
         * 2. 判定がもたれる最長の区間は、ここで試す最長の窓 **120秒** ×
         *    古さの上限 [STALE_SPAN_FACTOR]（2倍）= **240秒**。
         * 3. 100Gbps × 240秒 = 24Tbit = 3TB = **3e12 バイト**。
         * 4. そこへ**3桁の余裕**を積んで **3e15 バイト（3PB）**を上限とする。
         *
         * この値の位置づけ（どちらの境からも離れていることが要点）:
         *
         * - 旧式 `d * 8 * 1000` の桁あふれの境 `Long.MAX_VALUE / 8000` ≒ 1.15PB の
         *   **約2.6倍**。つまり P4b / P5b の前提（旧式があふれる域）は**いまも
         *   踏まれる**——境がどこにあったかの記録が空回りしない。
         * - いまの式 `d * 8` の境 `Long.MAX_VALUE / 8` ≒ 1.15EB の**約384分の1**。
         *   窓が最大13サンプルぶん積んでも `d * 8` はあふれない
         *   （13 × 3e15 × 8 = 3.1e17 < 9.2e18）。この関係は
         *   `桁あふれの境の記録…` のテストが毎回確かめる。
         */
        private const val MAX_PRODUCIBLE_DELTA = 3_000_000_000_000_000L

        /** 判定が下される形の列を作る種類（時刻が増え、カウンタが単調な列を狙うもの）。 */
        private val JUDGED_MODES = listOf(
            Mode.REALISTIC, Mode.ROLLBACK, Mode.GAP, Mode.ALL_FAST, Mode.HUGE_JUMP,
        )

        // -------------------------------------------------------------- 性質

        private val CHECKS: Map<String, (Case, MutableMap<String, Int>) -> Violation?> = mapOf(
            "P1" to { c, cov -> checkNotBeforeWindowFull(c, cov) },
            "P2" to { c, cov -> checkNoThrow(c, cov) },
            "P3" to { c, cov -> checkRollbackRestartsWindow(c, cov) },
            "P4" to { c, cov -> checkNoFalsePositive(c, cov) },
            "P4b" to { c, cov -> checkNoFalsePositive(c, cov) },
            "P5" to { c, cov -> checkNoMiss(c, cov) },
            "P5b" to { c, cov -> checkNoMiss(c, cov) },
            "P6" to { c, cov -> checkResetIsFresh(c, cov) },
            "P7" to { c, cov -> checkNoStaleVerdict(c, cov) },
            "P8" to { c, cov -> checkDeterministic(c, cov) },
        )

        /** 検査の順番。P4 と P4b は同じ関数なので1回だけ走らせる。 */
        private val RUN_ORDER = listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8")

        /**
         * P1: 窓が埋まる前は、どんなサンプル列でも false。
         *
         * 前提は「列全体の幅が窓に届かない」——判定に使われるサンプルは必ず列の
         * 部分集合なので、その幅も窓に届かない（内部を覗かずに書ける形）。
         */
        private fun checkNotBeforeWindowFull(case: Case, cov: MutableMap<String, Int>): Violation? {
            val windowMs = case.thresholds.windowSec * 1_000L
            val min = case.ticks.minOfOrNull { it.atMs } ?: return null
            val max = case.ticks.maxOfOrNull { it.atMs } ?: return null
            if (max - min >= windowMs) return null
            bump(cov, "P1 窓が埋まらない列")
            val d = SlowLinkDetector(case.thresholds)
            case.ticks.forEachIndexed { i, tick ->
                d.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                if (d.isSlow()) {
                    return Violation(
                        "P1", "早すぎる判定",
                        "列全体の幅は ${max - min}ms しかない（窓 ${windowMs}ms）のに isSlow()=true",
                        case, i, "列の幅=${max - min}ms 窓=${windowMs}ms",
                    )
                }
            }
            return null
        }

        /** P2: 例外を投げない。時刻の重複・逆行、カウンタの減少、極端な値を含む任意の列に対して。 */
        private fun checkNoThrow(case: Case, cov: MutableMap<String, Int>): Violation? {
            bump(cov, "P2 任意の列")
            val d = SlowLinkDetector(case.thresholds)
            case.ticks.forEachIndexed { i, tick ->
                try {
                    d.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                    d.isSlow()
                } catch (t: Throwable) {
                    return Violation(
                        "P2", t.javaClass.simpleName,
                        "${t.javaClass.name}: ${t.message}", case, i, "投げた手=${tick.render()}",
                    )
                }
            }
            try {
                d.reset()
                d.isSlow()
            } catch (t: Throwable) {
                return Violation(
                    "P2", "reset:" + t.javaClass.simpleName,
                    "${t.javaClass.name}: ${t.message}", case, case.ticks.size - 1, "reset() 後",
                )
            }
            return null
        }

        /**
         * P3: カウンタが巻き戻ったあと、負のデルタから導かれた判定を出さない。
         * 巻き戻り以後に判定が出るには**新しい窓が丸ごと埋まる**必要がある。
         *
         * 時刻が厳密に増える列だけを対象にする（そうでない列では、どのサンプルが
         * 窓に入ったかを入力列だけからは言えない）。この条件のもとでは全サンプルが
         * 窓に入るので、巻き戻った手 j より後の判定は必ず「j 以降の窓」に基づく。
         */
        private fun checkRollbackRestartsWindow(case: Case, cov: MutableMap<String, Int>): Violation? {
            if (!strictlyIncreasing(case.ticks)) return null
            val windowMs = case.thresholds.windowSec * 1_000L
            val d = SlowLinkDetector(case.thresholds)
            var rollbackAt: Long? = null
            var rollbackStep = -1
            var prev: Tick? = null
            case.ticks.forEachIndexed { i, tick ->
                val before = prev
                if (before != null && (tick.rxBytes < before.rxBytes || tick.txBytes < before.txBytes)) {
                    rollbackAt = tick.atMs
                    rollbackStep = i
                }
                d.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                prev = tick
                val since = rollbackAt
                if (since != null && tick.atMs - since < windowMs) {
                    bump(cov, "P3 巻き戻り直後")
                    if (d.isSlow()) {
                        return Violation(
                            "P3", "巻き戻り後の判定",
                            "第${rollbackStep + 1}手（t=$since）でカウンタが巻き戻ったのに、" +
                                "そこから ${tick.atMs - since}ms（窓 ${windowMs}ms 未満）で isSlow()=true",
                            case, i,
                            "巻き戻り=第${rollbackStep + 1}手 経過=${tick.atMs - since}ms 窓=${windowMs}ms",
                        )
                    }
                }
            }
            return null
        }

        /**
         * P4 / P4b: 窓内のどの区間も受信速度が床以上なら誤検知しない。
         *
         * 「どの区間も床以上」は**列の先頭からその手までの全区間**について確かめる
         * （窓は列の部分区間なので、これは必要な前提より強い＝偽の失敗を出さない側）。
         * 巻き戻り（負のデルタ）が現れた時点で前提は落ちる。
         */
        private fun checkNoFalsePositive(case: Case, cov: MutableMap<String, Int>): Violation? {
            if (!strictlyIncreasing(case.ticks)) return null
            val floor = case.thresholds.slowRxKbps
            val first = case.ticks.first()
            val d = SlowLinkDetector(case.thresholds)
            var allFast = true
            var prev: Tick? = null
            case.ticks.forEachIndexed { i, tick ->
                val before = prev
                if (before != null) {
                    val dRx = tick.rxBytes - before.rxBytes
                    val dt = tick.atMs - before.atMs
                    if (!rateAtLeast(dRx, dt, floor)) allFast = false
                }
                d.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                prev = tick
                if (allFast && i >= 1) {
                    // 窓のデルタは「先頭からここまで」を超えないので、それが
                    // [SAFE_DELTA] 以内なら 64bit 演算はあふれない域である。
                    val overflowDomain = tick.rxBytes - first.rxBytes > SAFE_DELTA ||
                        tick.txBytes - first.txBytes > SAFE_DELTA
                    val property = if (overflowDomain) "P4b" else "P4"
                    bump(
                        cov,
                        if (overflowDomain) "P4b 全区間が床以上かつ 64bit があふれる域" else "P4 全区間が床以上",
                    )
                    if (d.isSlow()) {
                        return Violation(
                            property, "誤検知",
                            "先頭からこの手までの全区間で受信速度が床（${floor}kbps）以上なのに isSlow()=true",
                            case, i, "床=${floor}kbps 窓=${case.thresholds.windowSec * 1_000L}ms",
                        )
                    }
                }
            }
            return null
        }

        /**
         * P5: 窓が埋まっており（かつ古すぎず）、窓全体の平均受信が床未満・平均送信が
         * 需要超なら true。裁定R20 の規則そのもの。
         *
         * 判定に使われる最古のサンプルは [oldestInWindow] で入力列から導く。
         * 時刻が厳密に増え、カウンタが巻き戻らない列に限る（窓が捨てられないので
         * 導出が一意になる）。
         */
        private fun checkNoMiss(case: Case, cov: MutableMap<String, Int>): Violation? {
            if (!strictlyIncreasing(case.ticks)) return null
            if (!nonDecreasingCounters(case.ticks)) return null
            val windowMs = case.thresholds.windowSec * 1_000L
            val floor = case.thresholds.slowRxKbps
            val demand = case.thresholds.demandTxKbps
            val d = SlowLinkDetector(case.thresholds)
            case.ticks.forEachIndexed { i, tick ->
                d.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                val oldestIndex = oldestInWindow(case.ticks, i, windowMs) ?: return@forEachIndexed
                val oldest = case.ticks[oldestIndex]
                val span = tick.atMs - oldest.atMs
                // 窓が埋まっていない、または古すぎる（この2つは P1 / P7 の領分）。
                if (span < windowMs || span > windowMs * STALE_SPAN_FACTOR) return@forEachIndexed
                val dRx = tick.rxBytes - oldest.rxBytes
                val dTx = tick.txBytes - oldest.txBytes
                if (!rateBelow(dRx, span, floor)) return@forEachIndexed
                if (!rateAtLeast(dTx, span, demand + 1)) return@forEachIndexed
                val overflowDomain = dRx > SAFE_DELTA || dTx > SAFE_DELTA
                val property = if (overflowDomain) "P5b" else "P5"
                bump(cov, if (overflowDomain) "P5b 窓平均が遅くかつ 64bit があふれる域" else "P5 窓平均が遅い")
                if (!d.isSlow()) {
                    return Violation(
                        property, "取りこぼし",
                        "窓（第${oldestIndex + 1}手から ${span}ms）の平均が受信 " +
                            "${kbps(dRx, span)}kbps < 床 ${floor}kbps・送信 ${kbps(dTx, span)}kbps > 需要 " +
                            "${demand}kbps なのに isSlow()=false",
                        case, i,
                        "窓=第${oldestIndex + 1}手〜第${i + 1}手 幅=${span}ms dRx=$dRx dTx=$dTx",
                    )
                }
            }
            return null
        }

        /**
         * P6: `reset()` は初期状態に戻す。reset 後に同じ列を流したら、新しい
         * インスタンスに同じ列を流したのと**各時点で**同じ結果になる。
         */
        private fun checkResetIsFresh(case: Case, cov: MutableMap<String, Int>): Violation? {
            bump(cov, "P6 任意の列")
            val reused = SlowLinkDetector(case.thresholds)
            for (tick in case.ticks) reused.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
            reused.reset()
            if (reused.isSlow()) {
                return Violation("P6", "reset 直後の判定", "reset() の直後に isSlow()=true", case, 0, "reset() 直後")
            }
            val fresh = SlowLinkDetector(case.thresholds)
            case.ticks.forEachIndexed { i, tick ->
                reused.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                fresh.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                val a = reused.isSlow()
                val b = fresh.isSlow()
                if (a != b) {
                    return Violation(
                        "P6", "reset 後の食い違い",
                        "reset 後の判定=$a 新しいインスタンス=$b", case, i, "reset 後=$a 新規=$b",
                    )
                }
            }
            return null
        }

        /**
         * P7: 判定が `windowSec` の2倍を超える幅のサンプルにもたれていない。
         *
         * 判定が真になるには「最新から数えて窓〜窓の2倍の帯に入るサンプル」が
         * 必要である（それが窓の最古になる）。その帯に**1つも入っていない**なら
         * 判定は偽でなければならない。候補は「最後に窓が捨てられた手（巻き戻り）
         * 以降」に限る——製品はそれより古いサンプルを持っていないので、候補を
         * 多めに見ることは偽の失敗を出さない側に寄る。
         */
        private fun checkNoStaleVerdict(case: Case, cov: MutableMap<String, Int>): Violation? {
            if (!strictlyIncreasing(case.ticks)) return null
            val windowMs = case.thresholds.windowSec * 1_000L
            val stale = windowMs * STALE_SPAN_FACTOR
            val d = SlowLinkDetector(case.thresholds)
            var base = 0
            var prev: Tick? = null
            case.ticks.forEachIndexed { i, tick ->
                val before = prev
                if (before != null && (tick.rxBytes < before.rxBytes || tick.txBytes < before.txBytes)) base = i
                d.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                prev = tick
                var inBand = false
                var tooOld = false
                for (j in base..i) {
                    val span = tick.atMs - case.ticks[j].atMs
                    if (span in windowMs..stale) inBand = true
                    if (span > stale) tooOld = true
                }
                if (!inBand) {
                    if (tooOld) bump(cov, "P7 窓が古すぎる場面")
                    if (d.isSlow()) {
                        return Violation(
                            "P7", "古い証拠での判定",
                            "窓〜窓の2倍（${windowMs}ms〜${stale}ms）の帯に入るサンプルが1つも無いのに isSlow()=true",
                            case, i, "帯=${windowMs}〜${stale}ms 候補の起点=第${base + 1}手",
                        )
                    }
                }
            }
            return null
        }

        /**
         * P8: 同じ列を流せば各時点の判定が完全に一致する。
         * あわせて **`isSlow()` が状態を変えない**ことも押さえる（片方は毎手
         * 3回問い、もう片方は1回だけ問う）。
         */
        private fun checkDeterministic(case: Case, cov: MutableMap<String, Int>): Violation? {
            bump(cov, "P8 任意の列")
            val once = SlowLinkDetector(case.thresholds)
            val thrice = SlowLinkDetector(case.thresholds)
            case.ticks.forEachIndexed { i, tick ->
                once.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                thrice.onSample(tick.atMs, IfaceBytes(tick.rxBytes, tick.txBytes))
                val a = once.isSlow()
                thrice.isSlow()
                thrice.isSlow()
                val b = thrice.isSlow()
                if (a) bump(cov, "判定が真になった場面")
                if (a != b) {
                    return Violation(
                        "P8", "判定が揺れる",
                        "同じ列なのに判定が違う（1回問う=$a 3回問う=$b）", case, i, "1回=$a 3回=$b",
                    )
                }
            }
            return null
        }

        // ---------------------------------------------------------- 補助（厳密な比較）

        /** `8*dBytes / dtMs >= kbps`（= 製品の floor(8*dBytes/dtMs) >= kbps）を桁あふれ無しで判定。 */
        private fun rateAtLeast(dBytes: Long, dtMs: Long, kbps: Int): Boolean {
            if (dBytes < 0 || dtMs <= 0) return false
            val bits = BigInteger.valueOf(dBytes).multiply(EIGHT)
            val need = BigInteger.valueOf(kbps.toLong()).multiply(BigInteger.valueOf(dtMs))
            return bits.compareTo(need) >= 0
        }

        /** `8*dBytes / dtMs < kbps`（= 製品の floor(8*dBytes/dtMs) < kbps）を桁あふれ無しで判定。 */
        private fun rateBelow(dBytes: Long, dtMs: Long, kbps: Int): Boolean {
            if (dtMs <= 0) return false
            val bits = BigInteger.valueOf(dBytes).multiply(EIGHT)
            val need = BigInteger.valueOf(kbps.toLong()).multiply(BigInteger.valueOf(dtMs))
            return bits.compareTo(need) < 0
        }

        private fun kbps(dBytes: Long, dtMs: Long): BigInteger =
            BigInteger.valueOf(dBytes).multiply(EIGHT).divide(BigInteger.valueOf(dtMs))

        /**
         * 判定に使われる窓の最古のサンプルの添字。[SlowLinkDetector] の KDoc に
         * 書かれた規則（「窓の始点をまたぐ1つだけは残す」）から導く:
         * `atMs <= 最新 - 窓` を満たす**最後の**サンプル。無ければ判定は出ない。
         */
        private fun oldestInWindow(ticks: List<Tick>, newestIndex: Int, windowMs: Long): Int? {
            val cutoff = ticks[newestIndex].atMs - windowMs
            for (j in newestIndex downTo 0) {
                if (ticks[j].atMs <= cutoff) return j
            }
            return null
        }

        private fun strictlyIncreasing(ticks: List<Tick>): Boolean {
            for (i in 1 until ticks.size) {
                if (ticks[i].atMs <= ticks[i - 1].atMs) return false
            }
            return true
        }

        private fun nonDecreasingCounters(ticks: List<Tick>): Boolean {
            for (i in 1 until ticks.size) {
                if (ticks[i].rxBytes < ticks[i - 1].rxBytes) return false
                if (ticks[i].txBytes < ticks[i - 1].txBytes) return false
            }
            return true
        }

        private fun bump(cov: MutableMap<String, Int>, name: String) {
            cov[name] = (cov[name] ?: 0) + 1
        }

        // ---------------------------------------------------------------- 生成器

        private fun generate(rnd: Random, mode: Mode, th: SlowLinkThresholds): List<Tick> = when (mode) {
            Mode.REALISTIC -> steady(rnd, th, gaps = false, rollbacks = false)
            Mode.ROLLBACK -> steady(rnd, th, gaps = false, rollbacks = true)
            Mode.GAP -> steady(rnd, th, gaps = true, rollbacks = false)
            Mode.ALL_FAST -> allFast(rnd, th, huge = false)
            Mode.HUGE_JUMP -> allFast(rnd, th, huge = true)
            Mode.HOSTILE -> hostile(rnd)
            Mode.SHORT -> short(rnd, th)
        }

        /**
         * 実機のティックを模した列。間隔は窓の 1/12〜1/2（既定の窓60秒なら
         * 5秒〜30秒。点灯中・消灯中のティックがそのまま入る）にゆらぎを付ける。
         * 速度は床・需要の周りを狙う帯から引く（[band]）。
         */
        private fun steady(rnd: Random, th: SlowLinkThresholds, gaps: Boolean, rollbacks: Boolean): List<Tick> {
            val windowMs = th.windowSec * 1_000L
            val divisor = listOf(12, 6, 4, 2)[rnd.nextInt(4)]
            val baseTick = (windowMs / divisor).coerceAtLeast(200L)
            val count = 2 * divisor + 2 + rnd.nextInt(2 * divisor + 1)
            var at = startTime(rnd)
            var rx = startCounter(rnd)
            var tx = startCounter(rnd)
            val rxBand = band(rnd, th.slowRxKbps)
            val txBand = band(rnd, th.demandTxKbps + 1)
            val out = ArrayList<Tick>(count + 1)
            out.add(Tick(at, rx, tx))
            repeat(count) {
                var dt = jitter(rnd, baseTick)
                // 採取が止まる（`/proc/net/dev` が読めない・端末が眠る）。
                if (gaps && rnd.nextInt(100) < 15) dt = baseTick * (3 + rnd.nextInt(10))
                at += dt
                rx = add(rx, bytesFor(pick(rnd, rxBand, burst = true), dt))
                tx = add(tx, bytesFor(pick(rnd, txBand, burst = false), dt))
                if (rollbacks && rnd.nextInt(100) < 12) {
                    // トンネル作り直し（両方0付近へ）／片側だけの巻き戻り。
                    when (rnd.nextInt(3)) {
                        0 -> {
                            rx = rnd.nextLong(0, 4_096)
                            tx = rnd.nextLong(0, 4_096)
                        }
                        1 -> rx = if (rx > 0) rnd.nextLong(0, rx) else 0
                        else -> tx = if (tx > 0) rnd.nextLong(0, tx) else 0
                    }
                }
                out.add(Tick(at, rx, tx))
            }
            return out
        }

        /**
         * **どの区間も受信速度が床以上**になる列。区間ごとに
         * `dRx = ceil(F*dt/8) + 余り` を積む。[huge] のときは
         * `/proc/net/dev` が極端な値を返した場合の前方への飛び（Long の上限近く）を
         * 混ぜる——桁あふれの境（`Long.MAX_VALUE / 8000`）の周りを狙う。
         */
        private fun allFast(rnd: Random, th: SlowLinkThresholds, huge: Boolean): List<Tick> {
            val windowMs = th.windowSec * 1_000L
            val divisor = listOf(12, 6, 4, 2)[rnd.nextInt(4)]
            val baseTick = (windowMs / divisor).coerceAtLeast(200L)
            val count = 2 * divisor + 2 + rnd.nextInt(divisor + 1)
            /**
             * [huge] の半分は「**受信は床未満・送信だけが巨大**」という形にする。
             * 受信まで床以上にすると P5 の前提（窓平均の受信が床未満）が立たず、
             * P5b が空回りしてしまう——旧式の境を**取りこぼしの側から**踏むには
             * この形が要る。
             */
            val slowRx = huge && rnd.nextBoolean()
            var at = startTime(rnd)
            var rx = 0L
            var tx = 0L
            val out = ArrayList<Tick>(count + 1)
            out.add(Tick(at, rx, tx))
            repeat(count) {
                val dt = jitter(rnd, baseTick)
                at += dt
                var dRx = if (slowRx) {
                    bytesFor(rnd.nextInt(0, (th.slowRxKbps / 2).coerceAtLeast(1)), dt)
                } else {
                    val least = ceilDiv(th.slowRxKbps.toLong() * dt, 8L)
                    add(least, rnd.nextLong(0, 1L + least.coerceAtMost(1_000_000L)))
                }
                var dTx = bytesFor(pick(rnd, band(rnd, th.demandTxKbps + 1), burst = false), dt)
                if (huge && rnd.nextInt(100) < 40) {
                    if (!slowRx) dRx = hugeDelta(rnd)
                    dTx = hugeDelta(rnd)
                }
                rx = add(rx, dRx)
                tx = add(tx, dTx)
                out.add(Tick(at, rx, tx))
            }
            return out
        }

        /** 時刻の重複・逆行、カウンタの減少、0 と Long の上限近くを混ぜた列。 */
        private fun hostile(rnd: Random): List<Tick> {
            val count = 2 + rnd.nextInt(14)
            var at = weirdTime(rnd, 0L)
            var rx = weirdCounter(rnd, 0L)
            var tx = weirdCounter(rnd, 0L)
            val out = ArrayList<Tick>(count)
            out.add(Tick(at, rx, tx))
            repeat(count) {
                at = weirdTime(rnd, at)
                rx = weirdCounter(rnd, rx)
                tx = weirdCounter(rnd, tx)
                out.add(Tick(at, rx, tx))
            }
            return out
        }

        /** 列全体の幅が窓に届かない列（P1 の前提）。中身は敵対的でよい。 */
        private fun short(rnd: Random, th: SlowLinkThresholds): List<Tick> {
            val windowMs = th.windowSec * 1_000L
            val start = startTime(rnd)
            val count = 2 + rnd.nextInt(12)
            var rx = startCounter(rnd)
            var tx = startCounter(rnd)
            val out = ArrayList<Tick>(count)
            repeat(count) {
                val at = start + rnd.nextLong(0, windowMs)
                rx = weirdCounter(rnd, rx)
                tx = weirdCounter(rnd, tx)
                out.add(Tick(at, rx, tx))
            }
            // 7割は時刻順（実機の並び）、3割はそのまま（重複・逆行を含む並び）。
            return if (rnd.nextInt(100) < 70) out.sortedBy { it.atMs } else out
        }

        /** 速度 kbps を選ぶ帯。閾値 [pivot] の周りを狙う。 */
        private fun band(rnd: Random, pivot: Int): IntRange {
            val p = pivot.coerceAtLeast(1)
            return when (rnd.nextInt(6)) {
                0 -> 0..0
                1 -> 0..(p / 2 + 1)
                2 -> (p - 1).coerceAtLeast(0)..(p + 1)
                3 -> 0..(p * 2)
                4 -> p..(p * 4)
                else -> 0..20_000
            }
        }

        private fun pick(rnd: Random, range: IntRange, burst: Boolean): Int {
            val base = if (range.last <= range.first) range.first else rnd.nextInt(range.first, range.last + 1)
            // 動画プレイヤーが区間ごとにまとめて取りに来る形（裁定R20 の主題）。
            return if (burst && rnd.nextInt(100) < 12) base * (2 + rnd.nextInt(19)) else base
        }

        private fun bytesFor(kbps: Int, dtMs: Long): Long = kbps.toLong() * dtMs / 8L

        private fun jitter(rnd: Random, baseTick: Long): Long {
            val spread = (baseTick / 10).coerceAtLeast(1L)
            return (baseTick + rnd.nextLong(-spread, spread + 1)).coerceAtLeast(1L)
        }

        private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

        /** Long を超えない足し算（本物のカウンタは負にならない）。 */
        private fun add(value: Long, delta: Long): Long =
            if (delta > 0 && value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

        /**
         * 起動からの経過時間（`SystemClock.elapsedRealtime`）を模した開始時刻。
         * 0（起動直後）から数年ぶんまで。
         */
        private fun startTime(rnd: Random): Long = when (rnd.nextInt(4)) {
            0 -> 0L
            1 -> rnd.nextLong(1, 10_000)
            2 -> rnd.nextLong(0, 86_400_000L)
            else -> rnd.nextLong(0, 100L * 86_400_000L)
        }

        /** 累計カウンタの開始値。0（作り直した直後）から Long の上限近くまで。 */
        private fun startCounter(rnd: Random): Long = when (rnd.nextInt(5)) {
            0 -> 0L
            1 -> rnd.nextLong(0, 1_000)
            2 -> rnd.nextLong(0, 1_000_000_000L)
            3 -> rnd.nextLong(0, 1_000_000_000_000L)
            else -> NEAR_MAX_BASE
        }

        /**
         * 前方への巨大な飛び。**旧式の桁あふれの境（`Long.MAX_VALUE / 8000`
         * ≒ 1.15PB）をまたぐ**値を狙い、上限は物理から導いた
         * [MAX_PRODUCIBLE_DELTA]（3PB）で止める。境の**両側**を引くので、
         * 「境がどこにあったか」の記録として働く。
         */
        private fun hugeDelta(rnd: Random): Long {
            val boundary = Long.MAX_VALUE / 8_000L
            return when (rnd.nextInt(6)) {
                0 -> boundary
                1 -> boundary + rnd.nextLong(1, 1_000_000L)
                2 -> boundary - rnd.nextLong(0, 1_000_000L)
                3 -> 1L shl 50 // 境の少し下（1.13PB）
                4 -> 1L shl 51 // 境の少し上（2.25PB）
                else -> rnd.nextLong(boundary, MAX_PRODUCIBLE_DELTA)
            }
        }

        private fun weirdTime(rnd: Random, prev: Long): Long = when (rnd.nextInt(8)) {
            0 -> prev
            1 -> (prev - 1).coerceAtLeast(0L)
            2 -> (prev - rnd.nextLong(0, 120_000)).coerceAtLeast(0L)
            3 -> add(prev, rnd.nextLong(1, 5_000))
            4 -> add(prev, rnd.nextLong(1, 600_000))
            5 -> 0L
            6 -> Long.MAX_VALUE
            else -> Long.MAX_VALUE - rnd.nextLong(0, 120_000)
        }

        private fun weirdCounter(rnd: Random, prev: Long): Long = when (rnd.nextInt(9)) {
            0 -> 0L
            1 -> prev
            2 -> (prev - 1).coerceAtLeast(0L)
            3 -> prev / 2
            4 -> add(prev, rnd.nextLong(0, 1_000_000L))
            5 -> add(prev, rnd.nextLong(0, Long.MAX_VALUE / 4))
            6 -> Long.MAX_VALUE
            7 -> Long.MAX_VALUE - rnd.nextLong(0, 1_000_000L)
            else -> rnd.nextLong(0, Long.MAX_VALUE)
        }

        // ---------------------------------------------------------------- 実行

        class Corpus {
            val worst = mutableMapOf<String, Violation>()
            val counts = mutableMapOf<String, Int>()
            val coverage = mutableMapOf<String, Int>()
            var cases = 0
            var ticks = 0
            var elapsedMs = 0L
        }

        /** 全シードぶんを一度だけ流す（すべてのテストが同じ結果を読む）。 */
        val corpus: Corpus by lazy { buildCorpus() }

        /**
         * 検査する列の全体。シードと列番号から種を作るので、**この関数を何度呼んでも
         * 同じ列が同じ順番で出る**（[buildCorpus] と P9 が同じ列を見られる）。
         */
        fun allCases(): List<Case> {
            val out = ArrayList<Case>(SEEDS.size * CASES_PER_SEED)
            for (seed in SEEDS) {
                for (index in 0 until CASES_PER_SEED) {
                    val rnd = Random(seed * 1_000_003L + index)
                    val mode = MODES[index % MODES.size]
                    val thresholds = THRESHOLDS[rnd.nextInt(THRESHOLDS.size)]
                    out.add(Case(seed, index, mode, thresholds, generate(rnd, mode, thresholds)))
                }
            }
            return out
        }

        private fun buildCorpus(): Corpus {
            val corpus = Corpus()
            val startedAt = System.nanoTime()
            for (case in allCases()) {
                corpus.cases++
                corpus.ticks += case.ticks.size
                for (name in RUN_ORDER) {
                    val violation = CHECKS.getValue(name)(case, corpus.coverage) ?: continue
                    corpus.counts[violation.key] = (corpus.counts[violation.key] ?: 0) + 1
                    val known = corpus.worst[violation.key]
                    if (known == null || violation.case.ticks.size < known.case.ticks.size) {
                        corpus.worst[violation.key] = violation
                    }
                }
            }
            corpus.elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            return corpus
        }
    }
}
