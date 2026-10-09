package net.openconnect_vpn.android.failover

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class HealthProbeTimedTest {

    /** 既定実装の検証用。`probe` だけを実装した古い形の HealthProbe。 */
    private class OldStyleProbe(private val reachable: Boolean) : HealthProbe {
        var calls = 0
        override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
            calls++
            return reachable
        }
    }

    /** 呼び出しごとに結果を決められる。結果の列が尽きたら最後の値を繰り返す。 */
    private class ScriptedProbe(private val results: List<Boolean>) : HealthProbe {
        var calls = 0
        override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
            val r = results[minOf(calls, results.size - 1)]
            calls++
            return r
        }
    }

    /** 呼び出しごとに待ち時間を変える。所要時間の並びが呼び出し順と一致するかを見るため。 */
    private class DelayedProbe(private val delaysMs: List<Long>) : HealthProbe {
        var calls = 0
        override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
            delay(delaysMs[calls])
            calls++
            return true
        }
    }

    /** 渡された引数を記録する。probeTimed が target と timeoutMs を素通しするかを見る。 */
    private class RecordingProbe : HealthProbe {
        val targets = mutableListOf<ProbeTarget>()
        val timeouts = mutableListOf<Int>()
        override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
            targets.add(target)
            timeouts.add(timeoutMs)
            return true
        }
    }

    @Test
    fun `probe だけを実装した HealthProbe でも probeTimed が動く`() = runBlocking {
        val p = OldStyleProbe(reachable = true)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertTrue(o.reachable)
        assertEquals(3, p.calls)
        assertEquals(3, o.rttMs.size)
    }

    @Test
    fun `到達できなければ reachable は false`() = runBlocking {
        val p = OldStyleProbe(reachable = false)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertFalse(o.reachable)
        assertEquals(3, o.rttMs.size)
    }

    @Test
    fun `attempts が 1 未満でも 1 回は試す`() = runBlocking {
        val p = OldStyleProbe(reachable = true)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 0)
        assertEquals(1, p.calls)
        assertEquals(1, o.rttMs.size)
    }

    @Test
    fun `attempts が負の値でも 1 回は試す`() = runBlocking {
        val p = OldStyleProbe(reachable = true)
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = -5)
        assertEquals(1, p.calls)
        assertEquals(1, o.rttMs.size)
    }

    @Test
    fun `1回でも成功すれば reachable は true（失敗が混じっていても）`() = runBlocking {
        val p = ScriptedProbe(listOf(false, true, false))
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertTrue(o.reachable)
        assertEquals(3, p.calls)
        assertEquals(3, o.rttMs.size)
    }

    @Test
    fun `最後の1回だけ成功しても reachable は true`() = runBlocking {
        val p = ScriptedProbe(listOf(false, false, true))
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertTrue(o.reachable)
    }

    @Test
    fun `全回失敗なら reachable は false（成功が混じらない）`() = runBlocking {
        val p = ScriptedProbe(listOf(false, false, false))
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertFalse(o.reachable)
        assertEquals(3, o.rttMs.size)
    }

    @Test
    fun `所要時間は回ごとに1つずつ、呼び出し順に並ぶ`() = runBlocking {
        // 各回の待ち時間を 0 / 60 / 120 ms にする。所要時間は少なくともその値になるはず。
        // 順序が崩れていれば（逆順や末尾だけ残す等）下限の比較で落ちる。
        val p = DelayedProbe(listOf(0L, 60L, 120L))
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 3)
        assertEquals(3, o.rttMs.size)
        assertTrue("1回目は 0ms 以上", o.rttMs[0] >= 0L)
        assertTrue("2回目は 60ms 以上（実測 ${o.rttMs[1]}）", o.rttMs[1] >= 60L)
        assertTrue("3回目は 120ms 以上（実測 ${o.rttMs[2]}）", o.rttMs[2] >= 120L)
    }

    @Test
    fun `probe には target と timeoutMs をそのまま渡す`() = runBlocking {
        val p = RecordingProbe()
        val target = ProbeTarget(host = "example.invalid", port = 8443)
        p.probeTimed(target, timeoutMs = 1_234, attempts = 2)
        assertEquals(listOf(target, target), p.targets)
        assertEquals(listOf(1_234, 1_234), p.timeouts)
    }

    @Test
    fun `即座に失敗した回は所要時間に timeoutMs を入れない（実測を記録する）`() = runBlocking {
        // 接続拒否や名前解決の失敗は数ミリ秒で返る。timeoutMs（5000）を埋めれば誤り。
        val p = ScriptedProbe(listOf(false, false))
        val o = p.probeTimed(ProbeTarget(), timeoutMs = 5_000, attempts = 2)
        assertFalse(o.reachable)
        assertTrue("即失敗の所要時間は timeoutMs より十分小さいはず（実測 ${o.rttMs}）", o.rttMs.all { it < 5_000L })
    }

    // ---- TcpHealthProbe（実際のソケット。ループバックのみ） ----

    /** IP の文字列リテラルを書かずに済ませるため、ループバックのアドレスを実行時に取る。 */
    private fun loopback(): String = checkNotNull(InetAddress.getLoopbackAddress().hostAddress)

    @Test
    fun `TcpHealthProbe の probeTimed では、待ち受けポートは到達可能で、回数ぶんの所要時間を返す`() = runBlocking {
        ServerSocket(0).use { server ->
            val o = TcpHealthProbe().probeTimed(
                target = ProbeTarget(loopback(), server.localPort),
                timeoutMs = 2_000,
                attempts = 3,
            )
            assertTrue(o.reachable)
            assertEquals(3, o.rttMs.size)
        }
    }

    @Test
    fun `TcpHealthProbe の probeTimed では、閉じたポートは到達不能で、拒否は即座に返り timeoutMs を記録しない`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val o = TcpHealthProbe().probeTimed(
            target = ProbeTarget(loopback(), closedPort),
            timeoutMs = 5_000,
            attempts = 3,
        )
        assertFalse(o.reachable)
        assertEquals(3, o.rttMs.size)
        assertTrue("接続拒否は timeoutMs より十分速く返るはず（実測 ${o.rttMs}）", o.rttMs.all { it < 5_000L })
    }

    @Test
    fun `TcpHealthProbe の probeTimed では、全回失敗の5回周期でも各回に実測の所要時間が入る`() = runBlocking {
        // 失敗のログは周期で1行に抑えているが（TcpHealthProbe.probeTimed の KDoc）、
        // その抑制が結果を変えないことを確かめる。ログ呼び出し自体は JVM 単体テストでは
        // 観測できない（unitTests.returnDefaultValues で Log.w は無動作）ため、
        // ログの行数は TcpHealthProbe の読みで検証する。
        val closedPort = ServerSocket(0).use { it.localPort }
        val o = TcpHealthProbe().probeTimed(
            target = ProbeTarget(loopback(), closedPort),
            timeoutMs = 5_000,
            attempts = 5,
        )
        assertFalse(o.reachable)
        assertEquals(5, o.rttMs.size)
        assertTrue("各回の所要時間は timeoutMs より十分小さいはず（実測 ${o.rttMs}）", o.rttMs.all { it < 5_000L })
    }

    @Test
    fun `TcpHealthProbe の probeTimed では、attempts が 0 以下でも 1 回だけ試す`() = runBlocking {
        ServerSocket(0).use { server ->
            val probe = TcpHealthProbe()
            val target = ProbeTarget(loopback(), server.localPort)
            assertEquals(1, probe.probeTimed(target, 2_000, attempts = 0).rttMs.size)
            assertEquals(1, probe.probeTimed(target, 2_000, attempts = -3).rttMs.size)
        }
    }
}
