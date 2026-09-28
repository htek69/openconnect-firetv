package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcNetDevTest {

    private val sample =
        "Inter-|   Receive                                                |  Transmit\n" +
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
        "  tun0: 47719469   39329    0    0    0     0          0         0  4699017   29801    0    0    0     0       0          0\n" +
        " wlan0: 59014309   60183    0    0    0     0          0         0 15105423   61624  107    0    0     0       0          0\n"

    @Test
    fun `tun0 の受信と送信のバイト数を取り出す`() {
        val v = parseProcNetDev(sample, "tun0")
        assertEquals(47719469L, v!!.rxBytes)
        assertEquals(4699017L, v.txBytes)
    }

    @Test
    fun `別のインターフェースも取り出せる`() {
        val v = parseProcNetDev(sample, "wlan0")
        assertEquals(59014309L, v!!.rxBytes)
        assertEquals(15105423L, v.txBytes)
    }

    @Test
    fun `存在しないインターフェースは null`() {
        assertNull(parseProcNetDev(sample, "tun9"))
    }

    @Test
    fun `インターフェース名の部分一致で誤って拾わない`() {
        assertNull(parseProcNetDev(sample, "tun"))
    }

    @Test
    fun `空文字列は null`() {
        assertNull(parseProcNetDev("", "tun0"))
    }

    @Test
    fun `列が足りない行は null`() {
        assertNull(parseProcNetDev("  tun0: 123 456\n", "tun0"))
    }
}
