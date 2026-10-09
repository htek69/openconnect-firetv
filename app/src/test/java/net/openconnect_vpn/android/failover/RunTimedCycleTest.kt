package net.openconnect_vpn.android.failover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runTimedCycle]: プローブ1周期の進め方（仕様書 §2「測り方」・§6 裁定30/33）。
 *
 * 時計は注入した偽物で、**待たない**。各回の所要時間は、試行の中で偽の時計を
 * 進めることで与える。
 *
 * 各試験は「間違った実装でも通ってしまわないか」を問うて書いてある。要点は
 * **「1回目の結果で決める」を「どれか成功すれば可」「最後の結果」「全部成功なら可」の
 * いずれとも区別すること**で、そのために 1回目だけ・2回目以降だけが違う並びを置く。
 */
class RunTimedCycleTest {

    private class FakeNs(var nowNs: Long = 0L) {
        fun advanceMs(ms: Long) {
            nowNs += ms * 1_000_000L
        }
    }

    /** 各回の（結果, 所要ms）を順に返す試行。尽きたら最後を繰り返す。呼ばれた回数と logFailure を記録する。 */
    private class Script(private val clock: FakeNs, private val steps: List<Pair<Boolean, Long>>) {
        val logFlags = mutableListOf<Boolean>()
        val calls: Int get() = logFlags.size

        suspend fun attempt(logFailure: Boolean): Boolean {
            val step = steps[minOf(logFlags.size, steps.size - 1)]
            logFlags.add(logFailure)
            clock.advanceMs(step.second)
            return step.first
        }
    }

    private fun run(
        steps: List<Pair<Boolean, Long>>,
        attempts: Int = 5,
        timeoutMs: Int = 5_000,
    ): Pair<ProbeOutcome, Script> = runBlocking {
        val clock = FakeNs()
        val script = Script(clock, steps)
        val outcome = runTimedCycle(timeoutMs, attempts, { clock.nowNs }) { script.attempt(it) }
        outcome to script
    }

    private fun ok(ms: Long = 10L) = true to ms
    private fun ng(ms: Long = 10L) = false to ms

    // ---------------------------------------------------------------- 1回目で決める

    @Test
    fun `1回目が失敗した周期は到達不能で、試行はちょうど1回`() {
        // あとの回が成功する並びを与える。「どれか成功すれば可」の実装なら true になり、
        // 5回すべてを試す。
        val (o, s) = run(listOf(ng(), ok(), ok(), ok(), ok()))
        assertFalse(o.reachable)
        assertEquals(1, s.calls)
        assertEquals(1, o.rttMs.size)
    }

    @Test
    fun `1回目が成功して以降が全部失敗しても到達可能（従来なら到達可能だった周期）`() {
        // 「全部成功なら可」「最後の結果」の実装なら false になる。
        // 失敗が続いても予算内（各10ms）なので5回すべて行われる。
        val (o, s) = run(listOf(ok(), ng(), ng(), ng(), ng()))
        assertTrue(o.reachable)
        assertEquals(5, s.calls)
        assertEquals(5, o.rttMs.size)
    }

    @Test
    fun `到達可否は2回目以降の結果に依存しない`() {
        // 1回目が成功なら、2回目以降のどの並びでも true。1回目が失敗なら false。
        val laterPatterns = listOf(
            listOf(ok(), ok(), ok(), ok()),
            listOf(ng(), ng(), ng(), ng()),
            listOf(ng(), ok(), ng(), ok()),
            listOf(ok(), ng(), ok(), ng()),
        )
        for (later in laterPatterns) {
            assertTrue("1回目成功: $later", run(listOf(ok()) + later).first.reachable)
            assertFalse("1回目失敗: $later", run(listOf(ng()) + later).first.reachable)
        }
    }

    @Test
    fun `応答時間は実際に行った各回ぶんだけ、実測のまま入る`() {
        val (o, _) = run(listOf(ok(100), ok(200), ng(300), ok(50), ok(70)))
        assertEquals(listOf(100L, 200L, 300L, 50L, 70L), o.rttMs)
        // 1回目が失敗した周期は、その1回ぶんだけ（残りを埋めない）。
        assertEquals(listOf(40L), run(listOf(ng(40), ok(1), ok(1))).first.rttMs)
    }

    // ---------------------------------------------------------------- 回数

    @Test
    fun `予算の範囲内なら指定の回数をちょうど行う`() {
        assertEquals(5, run(listOf(ok()), attempts = 5).second.calls)
        assertEquals(3, run(listOf(ok()), attempts = 3).second.calls)
    }

    @Test
    fun `attempts が 1 以下でも 1 回は行う`() {
        assertEquals(1, run(listOf(ok()), attempts = 1).second.calls)
        assertEquals(1, run(listOf(ok()), attempts = 0).second.calls)
        assertEquals(1, run(listOf(ok()), attempts = -4).second.calls)
    }

    // ---------------------------------------------------------------- 予算（timeoutMs * 2）
    //
    // 規則（裁定22）: 2回目以降は「経過時間 + timeoutMs <= 予算」のときだけ始める。
    // 回を途中で切り詰めない（満額の timeoutMs を得て走るか、走らないか）。
    // 各回の所要時間は timeoutMs 以下とする（実際の試行は timeoutMs で打ち切られる）。

    /** 偽の時計が進んだ合計（ms）。周期全体の壁時計。 */
    private fun totalMs(steps: List<Pair<Boolean, Long>>, timeoutMs: Int = 5_000, attempts: Int = 5): Long {
        val clock = FakeNs()
        val script = Script(clock, steps)
        runBlocking { runTimedCycle(timeoutMs, attempts, { clock.nowNs }) { script.attempt(it) } }
        return clock.nowNs / 1_000_000L
    }

    @Test
    fun `予算を使い切ったら回数に達していなくても止まる`() {
        // timeout 5000 → 予算 10000。各回 5000ms: 1回目で 5000。2回目は 5000+5000 <= 10000
        // で行い 10000。3回目は 10000+5000 > 10000 で止まる。予算が無い実装なら5回行う。
        val (o, s) = run(listOf(ok(5_000)), timeoutMs = 5_000)
        assertTrue(o.reachable)
        assertEquals(2, s.calls)
        assertEquals(listOf(5_000L, 5_000L), o.rttMs)
    }

    @Test
    fun `残りがちょうど timeoutMs なら次の回を始める`() {
        // 1回目 2000 + 2回目 3000 = 5000 → 残り 5000 = timeoutMs。3回目は行う。
        // 「残りが timeoutMs を超えるときだけ」（< の取り違え）の実装なら行わずに落ちる。
        val (_, s) = run(listOf(ok(2_000), ok(3_000), ok(1)))
        // 3回目(1ms)のあとは 5001+5000 = 10001 > 10000 なので止まる。
        assertEquals(3, s.calls)
    }

    @Test
    fun `残りが timeoutMs に1ms 足りなければ次の回を始めない`() {
        // 1回目 2000 + 2回目 3001 = 5001 → 残り 4999 < timeoutMs。3回目は行わない。
        // 経過時間だけで決める実装（予算内なら始める）は行ってしまい、落ちる。
        val (o, s) = run(listOf(ok(2_000), ok(3_001), ok(1)))
        assertEquals(2, s.calls)
        assertEquals(listOf(2_000L, 3_001L), o.rttMs)
    }

    @Test
    fun `予算は1回目の所要時間も含む（累計の壁時計）`() {
        // 1回目 4000、2回目 4000。2回目の開始時 4000+5000 <= 10000 で行い 8000。
        // 3回目は 8000+5000 > 10000 で止まる。予算を「2回目以降の合計」で数える実装は
        // 3回目の開始時の経過を 4000 と見て 9000 <= 10000 で行ってしまい、落ちる。
        val (o, s) = run(listOf(ok(4_000)), timeoutMs = 5_000)
        assertEquals(2, s.calls)
        assertEquals(listOf(4_000L, 4_000L), o.rttMs)
    }

    @Test
    fun `予算は timeoutMs に比例する`() {
        // timeout 1000 → 予算 2000。各回 1000ms なら2回で止まる。
        // 予算を固定値で持つ実装は、timeout を変えるとこの回数が変わらずに落ちる。
        assertEquals(2, run(listOf(ok(1_000)), timeoutMs = 1_000).second.calls)
        assertEquals(2, run(listOf(ok(3_000)), timeoutMs = 3_000).second.calls)
    }

    @Test
    fun `周期の壁時計の合計は予算 timeoutMs の2倍を超えない（合計で見る）`() {
        // 守るもの: 回数ではなく**合計**。回数の表明は、予算の定数が変わっても通ってしまう。
        // 経過時間だけで開始を決める実装は、4999ms の回を3回行って 14997 > 10000 になる。
        val timeoutMs = 5_000
        val budget = timeoutMs * 2L
        val fixed = listOf(
            listOf(ok(4_999)),
            listOf(ok(5_000)),
            listOf(ok(1), ok(4_999), ok(4_999)),
            listOf(ok(0), ng(5_000), ng(5_000), ng(5_000)),
            listOf(ok(2_500)),
        )
        for (steps in fixed) {
            val total = totalMs(steps, timeoutMs)
            assertTrue("合計 $total ms が予算 $budget ms を超えた: $steps", total <= budget)
        }
        // 1回の所要時間が 0..timeoutMs に収まるあらゆる並びで成り立つこと
        // （再現できるよう固定の種で多数生成する）。
        val rnd = java.util.Random(20261009L)
        repeat(2_000) {
            val steps = List(5) { (if (rnd.nextBoolean()) true else false) to rnd.nextInt(timeoutMs + 1).toLong() }
            val total = totalMs(listOf(ok(rnd.nextInt(timeoutMs + 1).toLong())) + steps)
            assertTrue("合計 $total ms が予算 $budget ms を超えた: $steps", total <= budget)
        }
    }

    // ---------------------------------------------------------------- 失敗ログ（周期で最大1行）

    @Test
    fun `失敗を記録してよいのは周期の中で最初に失敗した回だけ`() {
        // 1回目が成功 → 2回目が最初の失敗（true）、3回目以降の失敗は false。
        val (_, s) = run(listOf(ok(), ng(), ng(), ng(), ng()))
        assertEquals(listOf(true, true, false, false, false), s.logFlags)
    }

    @Test
    fun `1回目が失敗した周期のログは1行（残りを行わないので増えない）`() {
        // 複数回化の前は「1回の試行で1行」。失敗しているリンクの行数が増えないこと。
        val (_, s) = run(listOf(ng(), ng(), ng(), ng(), ng()))
        assertEquals(listOf(true), s.logFlags)
    }

    @Test
    fun `全回成功の周期は、どの回にも失敗の記録が要らない（フラグは立つが失敗が無い）`() {
        val (o, s) = run(listOf(ok()))
        assertTrue(o.reachable)
        // 失敗が無ければ logFailure は使われない。最初の失敗の枠が最後まで残る。
        assertTrue(s.logFlags.all { it })
    }
}
