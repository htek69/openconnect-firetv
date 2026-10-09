package net.openconnect_vpn.android.failover

/**
 * プローブ1周期の結果。
 *
 * 仕様書 §2「測り方」: 1周期で複数回接続し、各回の所要時間を記録する。
 * 30秒間隔のプローブでは60秒の窓にサンプルが2個しか入らず、ばらつきを見るには
 * 足りないため、周期内で複数回取る。
 *
 * **[reachable] の意味は従来と同じ**（1回でも成功すれば到達可能）。死活判定の
 * 強さを変えないための約束で、安全策 S2 や裁定33 の期限の前後関係を動かさない。
 *
 * **失敗した接続も所要時間に数える**（呼び出し側がタイムアウト値を入れる）。
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
