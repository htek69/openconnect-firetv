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

    /**
     * トンネルは張れた。猶予期間中で初回プローブを待っている。
     * [consecutiveFailures] は Ruling 22: 猶予期間経過後に届いた連続プローブ失敗数。
     * `failureThreshold` に達すると [Healthy] へ進まずに切り替える。
     */
    data class Verifying(
        val groupId: String,
        val candidateIndex: Int,
        val connectedAtMs: Long,
        val consecutiveFailures: Int = 0,
    ) : FailoverState

    /** 疎通確認済み。定期プローブで監視している。 */
    data class Healthy(
        val groupId: String,
        val candidateIndex: Int,
        val consecutiveFailures: Int,
        val lastProbeAtMs: Long,
    ) : FailoverState

    /**
     * Ruling 25: 障害を検知し、現在の候補の切断完了を待っている。
     * [awaitingUuid] は切断を待っている候補の UUID。null は照合しないことを意味し、
     * テスト専用である。[startedAtMs] から `DISCONNECT_WAIT_MS` を過ぎたら
     * 確認を諦めて次候補へ進む。
     */
    data class FailingOver(
        val groupId: String,
        val failedIndex: Int,
        val awaitingUuid: String?,
        val startedAtMs: Long,
        /**
         * この切替の理由がスループット低下（仕様書 4-3）か。死活起因の切替では
         * false。UI が切替の理由を表示するために状態だけから読める必要があるため
         * ここに置く（`HomeRows` は `FailoverState` しか受け取らない純粋関数で
         * 組み立てるので、別経路では届かない）。既定値があるので既存の構築箇所は
         * 無改変でそのまま従来の意味（死活起因）になる。
         */
        val bySlowLink: Boolean = false,
    ) : FailoverState

    /** グループ内の全候補が一巡して全滅した。バックオフ待ち。 */
    data class Exhausted(
        val groupId: String,
        val attempt: Int,
        val retryAtMs: Long,
    ) : FailoverState
}
