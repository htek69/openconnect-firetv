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

    @Test
    fun `予算を使い切ったら回数に達していなくても止まる（各回が予算の半分を使う）`() {
        // timeout 5000 → 予算 10000。各回 5000ms: 1回目で 5000、2回目の開始時 5000 < 10000
        // で行い 10000。3回目の開始時は 10000 >= 10000 で止まる。
        // 予算が無い実装なら5回行う。
        val (o, s) = run(listOf(ok(5_000)), timeoutMs = 5_000)
        assertTrue(o.reachable)
        assertEquals(2, s.calls)
        assertEquals(listOf(5_000L, 5_000L), o.rttMs)
    }

    @Test
    fun `予算の境界 ちょうど使い切れば止まり、1ms 残っていれば次を行う`() {
        // 厳密な「以上で止まる」。5000 x 2 = 10000（ちょうど）→ 2回で止まる。
        assertEquals(2, run(listOf(ok(5_000))).second.calls)
        // 4999 x 2 = 9998 < 10000 → 3回目を行い、そこで 14997 になって止まる。
        assertEquals(3, run(listOf(ok(4_999))).second.calls)
    }

    @Test
    fun `予算は1回目の所要時間も含む（累計の壁時計）`() {
        // 1回目が 9000ms かかった（成功）。残りは 1000ms ぶんしか無い。
        // 2回目の開始時 9000 < 10000 なので1回行い、そこで 9000 + 9000 > 10000。
        // 予算を「2回目以降の合計」で数える実装なら、続けて行うので回数が増える。
        val (o, s) = run(listOf(ok(9_000), ok(9_000)), timeoutMs = 5_000)
        assertEquals(2, s.calls)
        assertEquals(listOf(9_000L, 9_000L), o.rttMs)
    }

    @Test
    fun `予算は timeoutMs に比例する`() {
        // timeout 1000 → 予算 2000。各回 1000ms なら2回で止まる。
        // 予算を固定値で持つ実装は、timeout を変えるとこの回数が変わらずに落ちる。
        assertEquals(2, run(listOf(ok(1_000)), timeoutMs = 1_000).second.calls)
        assertEquals(2, run(listOf(ok(3_000)), timeoutMs = 3_000).second.calls)
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
