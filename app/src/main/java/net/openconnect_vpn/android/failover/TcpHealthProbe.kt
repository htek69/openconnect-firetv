package net.openconnect_vpn.android.failover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP connect による疎通確認。
 *
 * VpnService 配下では全トラフィックがトンネルに入るため、通常の Socket で
 * 宛先に繋がれば「VPN 越しに通信できている」と判定できる。
 * ICMP は Android で raw socket が使えないため採用しない（仕様書 8章）。
 */
class TcpHealthProbe : HealthProbe {

    override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                    true
                }
            } catch (e: Exception) {
                // 名前解決失敗・接続拒否・タイムアウトはすべて「到達不能」
                false
            }
        }
}
