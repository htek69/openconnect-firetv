package net.openconnect_vpn.android.failover

/**
 * プローブ1周期の結果。
 *
 * 仕様書 §2「測り方」: 1周期で複数回接続し、各回の所要時間を記録する。
 * 30秒間隔のプローブでは60秒の窓にサンプルが2個しか入らず、ばらつきを見るには
 * 足りないため、周期内で複数回取る。
 *
 * **[reachable] は1回目の試行の結果そのものである。** 「1回でも成功すれば到達可能」
 * ではない。従来のプローブは接続を**1回**しか行わなかったので、`reachable` は
 * 1回の試行の結果だった。複数回の「どれか」に置き換えると死活判定がはるかに寛容に
 * なり（接続の8割が失敗する経路で、3周期連続の失敗が約0.51から約0.035へ）、
 * `failureThreshold` に届かず既存の死活による切替が働かなくなる。1回目で決めれば
 * 失敗の数え方も周期の意味も従来と厳密に同じで、安全策 S2 や裁定33 の期限の前後関係を
 * 動かさない。残りの試行は応答時間を測るためだけにある。
 *
 * **[rttMs] は実際に行った各回の所要時間である。** 1回目が失敗した周期は残りを
 * 行わないので1個だけになる（[HealthProbe.probeTimed]）。
 *
 * **失敗した接続も実測の所要時間に数える。**
 * 間欠的にタイムアウトする経路は実際に劣化しており、その事実を捨てる理由が無い。
 */
data class ProbeOutcome(
    val reachable: Boolean,
    val rttMs: List<Long>,
) {
    /** 所要時間の中央値。サンプルが無ければ null。偶数個なら中央2つの平均（切り捨て）。 */
    val medianRttMs: Long?
        get() {
            if (rttMs.isEmpty()) return null
            val sorted = rttMs.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2
            }
        }

    /** 所要時間の最大と最小の差。サンプルが無ければ null。 */
    val spreadMs: Long?
        get() = if (rttMs.isEmpty()) null else (rttMs.max() - rttMs.min())
}
