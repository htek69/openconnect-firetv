package net.openconnect_vpn.android.failover

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class TcpHealthProbeTest {

    @Test
    fun `待ち受けているポートには到達できる`() = runTest {
        ServerSocket(0).use { server ->
            val probe = TcpHealthProbe()
            val reachable = probe.probe(
                target = ProbeTarget("127.0.0.1", server.localPort),
                timeoutMs = 2_000,
            )
            assertTrue(reachable)
        }
    }

    @Test
    fun `閉じているポートには到達できない`() = runTest {
        val closedPort = ServerSocket(0).use { it.localPort }  // 閉じた直後のポート

        val probe = TcpHealthProbe()
        val reachable = probe.probe(ProbeTarget("127.0.0.1", closedPort), timeoutMs = 2_000)

        assertFalse(reachable)
    }

    @Test
    fun `解決できないホストでも例外を投げずに false を返す`() = runTest {
        val probe = TcpHealthProbe()
        val reachable = probe.probe(
            target = ProbeTarget("this-host-does-not-exist.invalid", 443),
            timeoutMs = 2_000,
        )
        assertFalse(reachable)
    }
}
