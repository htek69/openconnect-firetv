package net.openconnect_vpn.android.failover

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * [Backoff] の**性質テスト**（手書きシナリオではない）。
 *
 * 既存の `BackoffTest` の4件は代表的な試行回数を手で並べたもの。ここでは
 * 試行回数を機械生成して、どの値でも破れてはならない性質を検査する。
 * 依存は増やさない（[Random] のシードは固定配列 [SEEDS]）。
 *
 * 試行回数は `FailoverController` が数えるので実際には 0 から1つずつ増えるが、
 * 桁あふれの縁（シフト量の頭打ち 30、Int の上限）も含めて検査する——製品が
 * `BASE_MS shl shift` を使っており、そこが崩れると**待ち時間が負や 0 になって
 * 枯渇後の再試行が暴走する**ため。
 */
class BackoffPropertyTest {

    /** 上限（仕様書 7.2 の10分）。製品の private const と同じ値を性質の側でも持つ。 */
    private val maxMs = 600_000L

    /** 初回（仕様書 7.2 の30秒）。 */
    private val baseMs = 30_000L

    @Test
    fun `B1 試行回数に対して単調非減少`() {
        val sorted = attempts().sorted()
        var previousAttempt = sorted.first()
        var previous = Backoff.delayMsForAttempt(previousAttempt)
        for (attempt in sorted.drop(1)) {
            val delay = Backoff.delayMsForAttempt(attempt)
            if (delay < previous) {
                fail(
                    "単調非減少が破れた。**製品コードを直してはならない。性質を緩めてもならない。**\n" +
                        "  最小の入力: delayMsForAttempt($previousAttempt)=$previous → " +
                        "delayMsForAttempt($attempt)=$delay\n" +
                        "  検査した試行回数=${sorted.size} 件（シード=$SEEDS）",
                )
            }
            previousAttempt = attempt
            previous = delay
        }
    }

    @Test
    fun `B2 上限を超えない`() {
        val bad = attempts().filter { Backoff.delayMsForAttempt(it) > maxMs }
        assertTrue(
            "上限 ${maxMs}ms を超えた試行回数がある（最小の入力=${bad.minOrNull()}、" +
                "その値=${bad.minOrNull()?.let { Backoff.delayMsForAttempt(it) }}）。" +
                "**製品コードを直してはならない。性質を緩めてもならない。**\n  全部=${bad.take(20)}",
            bad.isEmpty(),
        )
    }

    @Test
    fun `B3 負にならない`() {
        val bad = attempts().filter { Backoff.delayMsForAttempt(it) < 0L }
        assertTrue(
            "待ち時間が負になる試行回数がある（最小の入力=${bad.minOrNull()}、" +
                "その値=${bad.minOrNull()?.let { Backoff.delayMsForAttempt(it) }}）。" +
                "**製品コードを直してはならない。性質を緩めてもならない。**\n  全部=${bad.take(20)}",
            bad.isEmpty(),
        )
    }

    @Test
    fun `B4 0 以下の試行回数は初回と同じ`() {
        val bad = attempts().filter { it <= 0 && Backoff.delayMsForAttempt(it) != baseMs }
        assertTrue(
            "0 以下の試行回数で初回（${baseMs}ms）以外を返す値がある（${bad.take(20)}）。" +
                "**製品コードを直してはならない。性質を緩めてもならない。**",
            bad.isEmpty(),
        )
    }

    @Test
    fun `生成器は桁あふれの縁に到達している`() {
        val all = attempts()
        val edges = listOf(0, 1, 30, 31, 32, 62, 63, 64, Int.MAX_VALUE, Int.MIN_VALUE)
        val missing = edges.filter { it !in all }
        assertTrue("検査していない縁がある: $missing", missing.isEmpty())
        // 頭打ちに届いていること（届いていなければ B2 は試されていない）。
        assertTrue(
            "上限に達する試行回数を1件も検査していない（B2 が空回りしている）",
            all.any { Backoff.delayMsForAttempt(it) == maxMs },
        )
    }

    /**
     * 検査する試行回数。網羅（-8..200）＋桁あふれの縁＋固定シードの無作為値。
     * シードを変えない限り同じ集合になるので、破れたら同じ値で再現する。
     */
    private fun attempts(): List<Int> {
        val out = linkedSetOf<Int>()
        for (i in -8..200) out.add(i)
        out.addAll(listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 30, 31, 32, 62, 63, 64, Int.MAX_VALUE, Int.MAX_VALUE - 1))
        for (seed in SEEDS) {
            val rnd = Random(seed)
            repeat(CASES_PER_SEED) { out.add(rnd.nextInt()) }
            repeat(CASES_PER_SEED) { out.add(rnd.nextInt(-64, 96)) }
        }
        return out.toList()
    }

    private companion object {
        private val SEEDS = listOf(1L, 2L, 3L, 5L, 8L)
        private const val CASES_PER_SEED = 200
    }
}
