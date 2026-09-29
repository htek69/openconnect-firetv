package net.openconnect_vpn.android.failover

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * [parseProcNetDev] の**性質テスト**（手書きシナリオではない）。
 *
 * 既存の `ProcNetDevTest` の6件は代表的な1枚の `/proc/net/dev` を手で書いたもの。
 * ここでは**行を機械生成して**流し、どの入力でも破れてはならない性質を検査する。
 * 依存は増やさない（[Random] のシードは固定配列 [SEEDS]）。
 *
 * 生成するのは実機が出しうるもの——`/proc/net/dev` は端末とカーネルで書式が違い、
 * 読んでいる途中で切れる（`read` が途中までしか返さない）こともあり、
 * ヘッダ行・空行・列数の違いも実際にある——に、**仕様書が名指しした敵対的な縁**
 * （途中で切れた行、非 ASCII、空行、列数の不足・過剰、Long の上限近く）を加えたもの。
 *
 * 性質は3つ。**壊れた入力に対する「正しい値」を当てる oracle は持たない**
 * （持てば解析器を2つ書くことになり、写し間違いが偽の失敗になる）。代わりに
 *
 * - [checkNoThrow]: どんな入力でも例外を投げない（返り値は null か [IfaceBytes]）。
 * - [checkExactName]: **その名前の行が無いなら必ず null。** `tun0` を指定して
 *   `tun00` / `xtun0` / `tun` の行を拾わない（null の側だけなので oracle が要らない）。
 * - [checkRoundTrip]: **既知の値から行を組み立てて解析すると、その値が返る。**
 *   列が足りない行・数字でない列を指定したときは null になる（非 null の側）。
 */
class ProcNetDevPropertyTest {

    // ------------------------------------------------------------------ 入力

    private enum class Mode { WELL_FORMED, DECOY, MALFORMED, TRUNCATED, GARBAGE }

    private data class Case(
        val seed: Long,
        val index: Int,
        val mode: Mode,
        val lines: List<String>,
        val query: String,
        /** [checkRoundTrip] が期待する値（組み立てた行が健全なときだけ入る）。 */
        val expected: IfaceBytes?,
        /** 期待が「null が返ること」なら true（列が足りない・数字でない行を指定した場合）。 */
        val expectNull: Boolean,
    ) {
        val text: String get() = lines.joinToString("\n")

        fun withLines(next: List<String>): Case = copy(lines = next)
    }

    private data class Violation(
        val property: String,
        val kind: String,
        val detail: String,
        val case: Case,
    ) {
        val key: String get() = "$property/$kind"
    }

    // ------------------------------------------------------------ 検査の入口

    @Test
    fun `N1 どんなバイト列でも例外を投げない`() = assertHolds("N1")

    @Test
    fun `N2 その名前の行が無いときは必ず null を返す`() = assertHolds("N2")

    @Test
    fun `N3 既知の値から組み立てた行を解析するとその値が返る`() = assertHolds("N3")

    @Test
    fun `生成器は各性質の前提に到達している`() {
        val required = listOf(
            "N1 任意の入力",
            "N2 名前の無い問い合わせ",
            "N2 惑わせる名前を含む入力",
            "N3 健全な行",
            "N3 壊れた行",
            "N1 非 ASCII を含む入力",
            "N1 途中で切れた入力",
        )
        val missing = required.filter { (corpus.coverage[it] ?: 0) == 0 }
        assertTrue(
            "生成器が踏んでいない前提がある: $missing\n" +
                "列数=${corpus.cases} 所要=${corpus.elapsedMs}ms\n" +
                "到達記録=${corpus.coverage.toSortedMap()}",
            missing.isEmpty(),
        )
    }

    private fun assertHolds(property: String) {
        val found = corpus.worst.filterKeys { it.startsWith("$property/") }
        if (found.isEmpty()) return
        val message = buildString {
            appendLine(
                "性質 $property が破れた（${found.size} 種類、${corpus.cases} 列中）。" +
                    "**製品コードを直してはならない。性質を緩めてもならない。**",
            )
            for (violation in found.values.sortedBy { it.case.lines.size }) {
                appendLine()
                appendLine(render(violation, "生成された入力", violation.case))
                appendLine(render(violation, "最小の入力", shrink(violation).case))
            }
        }
        fail(message)
    }

    private fun render(violation: Violation, title: String, case: Case): String = buildString {
        appendLine("=== ${violation.key} : ${violation.detail}")
        appendLine("  シード: seed=${case.seed} 列=${case.index} 種類=${case.mode}")
        appendLine("  --- $title（${case.lines.size} 行）---")
        case.lines.forEachIndexed { i, line -> appendLine("  ${i + 1}. \"${escape(line)}\"") }
        appendLine("  parseProcNetDev(text, \"${escape(case.query)}\")")
        appendLine("  返った値: ${parseQuietly(case)}")
    }

    /**
     * 同じ破れ（同じ種別キー）を保ったまま行を落として最小の入力にする。
     *
     * N3 は**期待値が特定の行に結び付いている**ので、問い合わせた名前の行そのものは
     * 削らない（削れば「値が違う」のは当たり前で、最小列が嘘になる）。
     */
    private fun shrink(violation: Violation): Violation {
        val check = CHECKS[violation.property] ?: return violation
        val pinned = violation.property == "N3"
        val sink = mutableMapOf<String, Int>()
        var best = violation
        var budget = 400
        var at = 0
        while (at < best.case.lines.size && budget > 0) {
            budget--
            if (pinned && nameOf(best.case.lines[at]) == best.case.query) {
                at++
                continue
            }
            val reduced = best.case.lines.toMutableList()
            reduced.removeAt(at)
            val replayed = check(best.case.withLines(reduced), sink)
            if (replayed != null && replayed.key == violation.key) best = replayed else at++
        }
        if (pinned) return best
        // 残った行の後ろを削る（途中で切れた入力の最小化）。
        var lineIndex = 0
        while (lineIndex < best.case.lines.size && budget > 0) {
            var cut = best.case.lines[lineIndex].length
            while (cut > 0 && budget > 0) {
                budget--
                val shorter = best.case.lines.toMutableList()
                shorter[lineIndex] = shorter[lineIndex].substring(0, cut - 1)
                val replayed = check(best.case.withLines(shorter), sink)
                if (replayed != null && replayed.key == violation.key) {
                    best = replayed
                    cut--
                } else {
                    break
                }
            }
            lineIndex++
        }
        return best
    }

    private fun parseQuietly(case: Case): String = try {
        parseProcNetDev(case.text, case.query).toString()
    } catch (t: Throwable) {
        "${t.javaClass.name}: ${t.message}"
    }

    private companion object {

        private val SEEDS = listOf(1L, 2L, 3L, 5L, 8L, 13L)
        private const val CASES_PER_SEED = 220

        private val MODES = listOf(
            Mode.WELL_FORMED, Mode.DECOY, Mode.MALFORMED, Mode.TRUNCATED, Mode.GARBAGE,
        )

        /** 実機に出るインターフェース名。 */
        private val REAL_NAMES = listOf(
            "tun0", "tun1", "wlan0", "eth0", "lo", "rmnet_data0", "dummy0", "sit0", "ip6tnl0", "p2p0",
        )

        /**
         * `tun0` を指定したときに**拾ってはならない**名前。前方一致・後方一致・
         * 部分一致・大文字違いをひととおり。
         */
        private val DECOY_NAMES = listOf(
            "tun00", "xtun0", "tun", "tun0x", "TUN0", "tun 0", "tun0:", " tun0", "0tun",
        )

        private val HEADER_LINES = listOf(
            "Inter-|   Receive                                                |  Transmit",
            " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
        )

        /** 非 ASCII・制御文字を含む文字の池（`/proc` を読んだ結果が化けた場合）。 */
        private val GARBAGE_CHARS: List<Char> =
            (" \t:|-_0123456789abcdefTUNtun".toList()) +
                listOf('\u0000', '\u0001', '\u001b', '\u007f', ' ', 'é', 'ü', '中', '東', '京', 'あ', '（', '：', '�', '\uD83D', '\uDE00')

        private val CHECKS: Map<String, (Case, MutableMap<String, Int>) -> Violation?> = mapOf(
            "N1" to { c, cov -> checkNoThrow(c, cov) },
            "N2" to { c, cov -> checkExactName(c, cov) },
            "N3" to { c, cov -> checkRoundTrip(c, cov) },
        )

        /** N1: 例外を投げない。返るのは null か [IfaceBytes] のどちらかだけ。 */
        private fun checkNoThrow(case: Case, cov: MutableMap<String, Int>): Violation? {
            bump(cov, "N1 任意の入力")
            if (case.mode == Mode.TRUNCATED) bump(cov, "N1 途中で切れた入力")
            if (case.text.any { it.code > 127 }) bump(cov, "N1 非 ASCII を含む入力")
            return try {
                parseProcNetDev(case.text, case.query)
                null
            } catch (t: Throwable) {
                Violation("N1", t.javaClass.simpleName, "${t.javaClass.name}: ${t.message}", case)
            }
        }

        /**
         * N2: 名前の完全一致。行の名前は書式が決めている（行を [String.trim] して
         * 最初の `:` の前。`:` が無ければ名前は空）ので、**指定した名前と一致する行が
         * 1つも無いなら null でなければならない**。
         */
        private fun checkExactName(case: Case, cov: MutableMap<String, Int>): Violation? {
            if (case.lines.any { nameOf(it) == case.query }) return null
            if (case.query.isEmpty()) bump(cov, "N2 名前の無い問い合わせ")
            if (case.mode == Mode.DECOY) bump(cov, "N2 惑わせる名前を含む入力")
            bump(cov, "N2 一致する行が無い入力")
            val got = try {
                parseProcNetDev(case.text, case.query)
            } catch (t: Throwable) {
                return null // 例外は N1 の領分。
            }
            if (got == null) return null
            return Violation(
                "N2", "別の行を拾った",
                "\"${escape(case.query)}\" という名前の行は無いのに $got を返した" +
                    "（行の名前=${case.lines.map { escape(nameOf(it)) }}）",
                case,
            )
        }

        /**
         * N3: 往復。既知の値から行を組み立てて解析すると、その値が返る。
         * 列が足りない行・数字でない列を指定した場合は null が返る。
         */
        private fun checkRoundTrip(case: Case, cov: MutableMap<String, Int>): Violation? {
            val expected = case.expected
            if (expected == null && !case.expectNull) return null
            if (expected != null) bump(cov, "N3 健全な行") else bump(cov, "N3 壊れた行")
            val got = try {
                parseProcNetDev(case.text, case.query)
            } catch (t: Throwable) {
                return null // 例外は N1 の領分。
            }
            if (expected != null && got != expected) {
                return Violation(
                    "N3", "値が違う",
                    "\"${escape(case.query)}\" の行は rx=${expected.rxBytes} tx=${expected.txBytes} " +
                        "として組み立てたのに $got を返した",
                    case,
                )
            }
            if (expected == null && got != null) {
                return Violation(
                    "N3", "壊れた行から値を返した",
                    "\"${escape(case.query)}\" の行は列が足りない（または数字でない）のに $got を返した",
                    case,
                )
            }
            return null
        }

        /** 行の名前（書式の定義そのもの）。 */
        private fun nameOf(line: String): String =
            line.trim().substringBefore(':', missingDelimiterValue = "")

        private fun bump(cov: MutableMap<String, Int>, name: String) {
            cov[name] = (cov[name] ?: 0) + 1
        }

        private fun escape(text: String): String = buildString {
            for (ch in text) {
                when {
                    ch == '\\' -> append("\\\\")
                    ch == '"' -> append("\\\"")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch.code < 0x20 || ch.code == 0x7f || ch.isSurrogate() ->
                        append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
        }

        // ---------------------------------------------------------------- 生成器

        private fun generate(rnd: Random, mode: Mode, seed: Long, index: Int): Case = when (mode) {
            Mode.WELL_FORMED -> wellFormed(rnd, seed, index, decoys = false)
            Mode.DECOY -> wellFormed(rnd, seed, index, decoys = true)
            Mode.MALFORMED -> malformed(rnd, seed, index)
            Mode.TRUNCATED -> truncated(rnd, seed, index)
            Mode.GARBAGE -> garbage(rnd, seed, index)
        }

        /**
         * 健全な `/proc/net/dev`。ヘッダ・空行・惑わせる名前の行を混ぜ、
         * 問い合わせる名前は「必ず在る名前」「惑わせる名前」「無い名前」「空」から選ぶ。
         */
        private fun wellFormed(rnd: Random, seed: Long, index: Int, decoys: Boolean): Case {
            val names = mutableListOf<String>()
            repeat(1 + rnd.nextInt(4)) { names.add(REAL_NAMES[rnd.nextInt(REAL_NAMES.size)]) }
            if (decoys) repeat(1 + rnd.nextInt(3)) { names.add(DECOY_NAMES[rnd.nextInt(DECOY_NAMES.size)]) }

            /** 行と、その行が「その名前の健全な統計行」として組み立てたものなら値。 */
            val built = mutableListOf<Pair<String, IfaceBytes?>>()
            if (rnd.nextInt(100) < 80) HEADER_LINES.forEach { built.add(it to null) }
            for (name in names.shuffled(rnd)) {
                val bytes = IfaceBytes(counter(rnd), counter(rnd))
                val line = statLine(rnd, name, bytes.rxBytes.toString(), bytes.txBytes.toString(), columns = 16)
                // 行に書かれた名前が意図した名前と違うもの（`tun0:` や先頭に空白のある
                // 名前）は「その名前の行」ではないので、期待値は持たせない。
                built.add(line to if (nameOf(line) == name) bytes else null)
            }
            if (rnd.nextInt(100) < 30) built.add((if (rnd.nextBoolean()) "" else "   ") to null)

            // 解析器は**最初に一致した行**を採るのが書式の定義。期待値も最初の行から採る。
            val values = mutableMapOf<String, IfaceBytes>()
            val seen = mutableSetOf<String>()
            for ((line, bytes) in built) {
                val actual = nameOf(line)
                if (!seen.add(actual)) continue
                if (bytes != null) values[actual] = bytes
            }
            val query = when (rnd.nextInt(10)) {
                0 -> ""
                1 -> "tun9"
                2 -> DECOY_NAMES[rnd.nextInt(DECOY_NAMES.size)]
                else -> names[rnd.nextInt(names.size)]
            }
            return Case(
                seed, index, if (decoys) Mode.DECOY else Mode.WELL_FORMED,
                built.map { it.first }, query, values[query], expectNull = false,
            )
        }

        /**
         * 指定した名前の行が壊れている場合。列の不足・数字でない列・Long に
         * 収まらない桁数——いずれも null が返らなければならない。
         */
        private fun malformed(rnd: Random, seed: Long, index: Int): Case {
            val name = REAL_NAMES[rnd.nextInt(REAL_NAMES.size)]
            val lines = mutableListOf<String>()
            if (rnd.nextInt(100) < 50) lines.addAll(HEADER_LINES)
            val line = when (rnd.nextInt(5)) {
                0 -> statLine(rnd, name, counter(rnd).toString(), counter(rnd).toString(), columns = rnd.nextInt(9))
                1 -> statLine(rnd, name, "abc", counter(rnd).toString(), columns = 16)
                2 -> statLine(rnd, name, counter(rnd).toString(), "0x10", columns = 16)
                3 -> statLine(rnd, name, "99999999999999999999", counter(rnd).toString(), columns = 16)
                else -> "$name:"
            }
            lines.add(line)
            // 同じ名前の健全な行を**後ろに**置く。書式は最初の行を採るので、
            // それでも null のままでなければならない。
            if (rnd.nextInt(100) < 40) {
                lines.add(statLine(rnd, name, "1000", "2000", columns = 16))
            }
            return Case(seed, index, Mode.MALFORMED, lines, name, expected = null, expectNull = true)
        }

        /** 読んでいる途中で切れた入力（`read` が途中までしか返さない）。 */
        private fun truncated(rnd: Random, seed: Long, index: Int): Case {
            val base = wellFormed(rnd, seed, index, decoys = rnd.nextBoolean())
            val flat = base.text
            val cut = if (flat.isEmpty()) 0 else rnd.nextInt(flat.length)
            return Case(
                seed, index, Mode.TRUNCATED,
                flat.substring(0, cut).split("\n"), base.query,
                expected = null, expectNull = false,
            )
        }

        /** 無作為なバイト列（非 ASCII・制御文字・改行だけ・空を含む）。 */
        private fun garbage(rnd: Random, seed: Long, index: Int): Case {
            val lineCount = rnd.nextInt(6)
            val lines = (0 until lineCount).map {
                val length = rnd.nextInt(40)
                buildString { repeat(length) { append(GARBAGE_CHARS[rnd.nextInt(GARBAGE_CHARS.size)]) } }
            }
            val query = when (rnd.nextInt(6)) {
                0 -> ""
                1 -> "tun0"
                2 -> ":"
                3 -> "\n"
                4 -> buildString { repeat(rnd.nextInt(12)) { append(GARBAGE_CHARS[rnd.nextInt(GARBAGE_CHARS.size)]) } }
                else -> REAL_NAMES[rnd.nextInt(REAL_NAMES.size)]
            }
            return Case(seed, index, Mode.GARBAGE, lines, query, expected = null, expectNull = false)
        }

        /**
         * `/proc/net/dev` の1行を組み立てる。受信バイト数はコロンの後の1列目、
         * 送信バイト数は9列目（書式の定義）。空白の量・種類はゆらがせる。
         */
        private fun statLine(rnd: Random, name: String, rx: String, tx: String, columns: Int): String {
            val cols = MutableList(columns) { rnd.nextLong(0, 100_000).toString() }
            if (columns >= 1) cols[0] = rx
            if (columns >= 9) cols[8] = tx
            val lead = " ".repeat(rnd.nextInt(4))
            val afterColon = if (rnd.nextBoolean()) " " else "  "
            val body = cols.joinToString(separator = if (rnd.nextInt(100) < 20) "\t" else " ")
            val trail = " ".repeat(rnd.nextInt(3))
            return "$lead$name:$afterColon$body$trail"
        }

        private fun counter(rnd: Random): Long = when (rnd.nextInt(5)) {
            0 -> 0L
            1 -> rnd.nextLong(0, 1_000)
            2 -> rnd.nextLong(0, 1_000_000_000L)
            3 -> Long.MAX_VALUE
            else -> Long.MAX_VALUE - rnd.nextLong(0, 1_000_000L)
        }

        // ---------------------------------------------------------------- 実行

        class Corpus {
            val worst = mutableMapOf<String, Violation>()
            val counts = mutableMapOf<String, Int>()
            val coverage = mutableMapOf<String, Int>()
            var cases = 0
            var elapsedMs = 0L
        }

        val corpus: Corpus by lazy { buildCorpus() }

        private fun buildCorpus(): Corpus {
            val corpus = Corpus()
            val startedAt = System.nanoTime()
            for (seed in SEEDS) {
                for (index in 0 until CASES_PER_SEED) {
                    val rnd = Random(seed * 7_919L + index)
                    val mode = MODES[index % MODES.size]
                    val case = generate(rnd, mode, seed, index)
                    corpus.cases++
                    for ((_, check) in CHECKS) {
                        val violation = check(case, corpus.coverage) ?: continue
                        corpus.counts[violation.key] = (corpus.counts[violation.key] ?: 0) + 1
                        val known = corpus.worst[violation.key]
                        if (known == null || violation.case.lines.size < known.case.lines.size) {
                            corpus.worst[violation.key] = violation
                        }
                    }
                }
            }
            corpus.elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            return corpus
        }
    }
}
