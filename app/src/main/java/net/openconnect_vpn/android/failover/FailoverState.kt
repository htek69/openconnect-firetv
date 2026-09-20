package net.openconnect_vpn.android.failover

/**
 * フェイルオーバー状態機械の状態（仕様書 7章）。
 * [Connecting] 以降は必ず対象グループと候補インデックスを保持する。
 */
sealed interface FailoverState {

    /** 未接続。自動切替も停止している。 */
    data object Idle : FailoverState

    /** 候補 [candidateIndex] へ接続中。 */
    data class Connecting(
        val groupId: String,
        val candidateIndex: Int,
        val startedAtMs: Long,
    ) : FailoverState

    /** トンネルは張れた。猶予期間中で初回プローブを待っている。 */
    data class Verifying(
        val groupId: String,
        val candidateIndex: Int,
        val connectedAtMs: Long,
    ) : FailoverState

    /** 疎通確認済み。定期プローブで監視している。 */
    data class Healthy(
        val groupId: String,
        val candidateIndex: Int,
        val consecutiveFailures: Int,
        val lastProbeAtMs: Long,
    ) : FailoverState

    /** 障害を検知し次候補へ移ろうとしている。 */
    data class FailingOver(
        val groupId: String,
        val failedIndex: Int,
    ) : FailoverState

    /** グループ内の全候補が一巡して全滅した。バックオフ待ち。 */
    data class Exhausted(
        val groupId: String,
        val attempt: Int,
        val retryAtMs: Long,
    ) : FailoverState
}
