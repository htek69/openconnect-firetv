package net.openconnect_vpn.android.failover

import android.util.Log
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
                // 名前解決失敗・接続拒否・タイムアウトはすべて「到達不能」。
                // 理由は捨てずに残す。プローブ失敗は候補の切替を引き起こす一方、
                // 「なぜ失敗したのか」が分からないと誤検知（電波が寝ているだけ、
                // 経路が一瞬消えただけ）と本当の疎通断を区別できない。実機で
                // 「原因不明のプローブ失敗が積み上がって切替が起きる」現象を
                // 追うのに、この1行が無いと手掛かりが無かった。
                Log.w(TAG, "プローブ失敗 ${target.host}:${target.port} timeout=${timeoutMs}ms ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

    private companion object {
        const val TAG = "TcpHealthProbe"
    }
}
