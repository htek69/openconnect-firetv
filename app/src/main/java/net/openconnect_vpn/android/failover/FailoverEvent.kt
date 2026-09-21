package net.openconnect_vpn.android.failover

/** 状態機械への入力。 */
sealed interface FailoverEvent {

    /** ユーザーがグループへの接続を指示した。 */
    data class UserConnectGroup(val groupId: String) : FailoverEvent

    /**
     * 裁定36: 人の操作ではない接続開始（Ruling 18 のプロセス kill からの自動復帰）。
     * [UserConnectGroup] と違い候補を無人（unattended）として開始するので、
     * 裁定30 の「認証ダイアログで止まったら除外して次へ」が働く。
     * 除外集合もクリアしない（無人で除外を落とすとアカウントロックの危険がある）。
     */
    data class AutoConnectGroup(val groupId: String) : FailoverEvent

    /** ユーザーが切断を指示した。 */
    data object UserDisconnect : FailoverEvent

    /**
     * 既存コアの接続状態が変化した。
     * [uuid] はそのイベントがどの接続試行に属するかを示す。
     * 現在の候補と一致しないイベントは、切替前の古い通知なので無視する。
     * null は照合しないことを意味し、テスト専用である（本番の橋渡しは常に値を入れる）。
     */
    data class VpnStateChanged(
        val state: VpnCoreState,
        val uuid: String? = null,
    ) : FailoverEvent

    /** 定期プローブの結果。 */
    data class ProbeResult(val reachable: Boolean) : FailoverEvent

    /** 時刻が進んだ（タイマー駆動）。 */
    data object Tick : FailoverEvent

    /** 下層ネットワークの可用性が変化した。 */
    data class UnderlyingNetworkChanged(val available: Boolean) : FailoverEvent
}

/**
 * 既存コアの状態を状態機械側の語彙に写したもの。
 * OpenConnectManagementThread の STATE_* と 1:1 で対応する。
 */
enum class VpnCoreState {
    Authenticating,  // STATE_AUTHENTICATING = 1
    UserPrompt,      // STATE_USER_PROMPT = 2
    Authenticated,   // STATE_AUTHENTICATED = 3
    Connecting,      // STATE_CONNECTING = 4
    Connected,       // STATE_CONNECTED = 5
    Disconnected,    // STATE_DISCONNECTED = 6
    ;

    companion object {
        /** 既存コアの int 値から変換する。未知の値は null。 */
        fun fromCoreInt(value: Int): VpnCoreState? = when (value) {
            1 -> Authenticating
            2 -> UserPrompt
            3 -> Authenticated
            4 -> Connecting
            5 -> Connected
            6 -> Disconnected
            else -> null
        }
    }
}
