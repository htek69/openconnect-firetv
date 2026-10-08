package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「いまの VPN のインターフェース名」を候補列から決める規則のテスト。
 *
 * この規則が必要になった経緯: 以前は `tun0` を固定で測っていた。Android は VPN を
 * 張り直すたびに新しい `tun` 番号を割り当て、古いものは `DOWN` のまま
 * `/proc/net/dev` に残るので、再接続を重ねると死んだ `tun0` を永久に測り続け、
 * 速度低下による切替が二度と起きなくなっていた（`docs/DECISIONS.md` 項目15）。
 */
class VpnInterfaceNameTest {

    @Test
    fun `候補が無ければ null`() {
        assertNull(selectVpnInterfaceName(emptyList()))
    }

    @Test
    fun `候補が1つならそれを選ぶ`() {
        assertEquals("tun4", selectVpnInterfaceName(listOf("tun4")))
    }

    @Test
    fun `null は候補に数えない`() {
        assertEquals("tun4", selectVpnInterfaceName(listOf(null, "tun4", null)))
    }

    @Test
    fun `空文字と空白だけの名前は候補に数えない`() {
        assertEquals("tun4", selectVpnInterfaceName(listOf("", "   ", "tun4")))
    }

    @Test
    fun `同じ名前が重複して来ても1つとして扱う`() {
        assertEquals("tun4", selectVpnInterfaceName(listOf("tun4", "tun4")))
    }

    /**
     * **曖昧なら測らない。** 複数の VPN が同時に生きている状況では、どちらの
     * トンネルの速度を見るべきか決められない。ここで片方を選ぶと「利用者が見ている
     * のとは別のトンネル」を根拠に切り替えうるので、null（＝測れない）を返す。
     * 呼び出し側は null を「遅い」と解釈してはならない（`sampleThroughput` の約束）。
     */
    @Test
    fun `名前が2つ以上あるときは曖昧なので null`() {
        assertNull(selectVpnInterfaceName(listOf("tun4", "tun5")))
    }

    @Test
    fun `名前の前後の空白は取り除いて比べる`() {
        assertEquals("tun4", selectVpnInterfaceName(listOf(" tun4", "tun4 ")))
    }

    @Test
    fun `tun 以外の名前でも通す`() {
        // インターフェース名の付け方は OS の都合であり、こちらで決めつけない。
        assertEquals("ppp0", selectVpnInterfaceName(listOf("ppp0")))
    }
}
