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

    /**
     * 各回を [probe] に任せ、回ごとに実測の経過時間を記録する。
     *
     * 接続処理と失敗時の [Log.w] は [probe] の1か所にだけある。ここで接続を
     * 書き直すと失敗の理由が記録されなくなるので、計時版を使っても従来どおり残す。
     *
     * 失敗した回も**実測の経過時間**を入れる。接続拒否や名前解決の失敗は数ミリ秒で
     * 返るので、それを `timeoutMs` の遅延として記録するのは事実に反する。
     * 拒否は「遅い」ではなく「届かない」であり、[ProbeOutcome.reachable] と
     * 既存の `failureThreshold` が扱う。タイムアウトだけが `timeoutMs` 前後で残る。
     */
    override suspend fun probeTimed(
        target: ProbeTarget,
        timeoutMs: Int,
        attempts: Int,
    ): ProbeOutcome {
        val n = attempts.coerceAtLeast(1)
        var any = false
        val times = ArrayList<Long>(n)
        for (i in 0 until n) {
            val startNs = System.nanoTime()
            val ok = probe(target, timeoutMs)
            times.add((System.nanoTime() - startNs) / 1_000_000L)
            if (ok) any = true
        }
        return ProbeOutcome(reachable = any, rttMs = times)
    }

    private companion object {
        const val TAG = "TcpHealthProbe"
    }
}
