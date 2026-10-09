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
        attemptOnce(target, timeoutMs, logFailure = true)

    /**
     * 各回の接続を [attemptOnce] で行い、回ごとに実測の経過時間を記録する。
     * 周期の進め方（**到達可否は1回目の結果そのもの**・1回目が失敗したら残りは行わない・
     * 成功したら `timeoutMs * 2` の予算の範囲で続ける）は [HealthProbe.probeTimed] と
     * [runTimedCycle] に書いてある。
     *
     * **1周期の失敗ログは最大1行である。** 失敗の理由を残すための [Log.w] は
     * 周期内で最初に起きた失敗の1回だけ出す。全回失敗しても、ログは5行ではなく1行になる。
     * 理由: 周期内の全回で [Log.w] を出すと、失敗しているリンクで logcat の行数が
     * 約5倍になる。このプロジェクトは「ログを足さない」制約を置いており、
     * logcat が溢れて自分たちの行が流れて消えた経験がある。その再発を防ぐのが
     * この分岐の目的である。1回目が失敗した周期は残りを行わないので、失敗している
     * リンクのログ行数は、複数回化の前（1回の試行で1行）と変わらない。
     *
     * 1回目に成功して2回目以降に失敗した場合も、最初の失敗は1行残す。
     * 「最初の1回だけ記録する」にすると、その失敗の理由が失われてしまうため。
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
    ): ProbeOutcome =
        runTimedCycle(timeoutMs, attempts, System::nanoTime) { logFailure ->
            attemptOnce(target, timeoutMs, logFailure)
        }

    /**
     * 1回の TCP 接続を試す。成功なら true。失敗なら false を返し、例外は投げない。
     *
     * [logFailure] が true のときだけ失敗を [Log.w] に残す。周期ごとの
     * 上限は呼び出し側（[probeTimed]）が決める。
     */
    private suspend fun attemptOnce(
        target: ProbeTarget,
        timeoutMs: Int,
        logFailure: Boolean,
    ): Boolean =
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
                if (logFailure) {
                    Log.w(TAG, "プローブ失敗 ${target.host}:${target.port} timeout=${timeoutMs}ms ${e.javaClass.simpleName}: ${e.message}")
                }
                false
            }
        }

    private companion object {
        const val TAG = "TcpHealthProbe"
    }
}
