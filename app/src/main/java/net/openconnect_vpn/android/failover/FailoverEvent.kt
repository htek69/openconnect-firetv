package net.openconnect_vpn.android.failover

/** 状態機械への入力。 */
sealed interface FailoverEvent {

    /**
     * ユーザーがグループへの接続を指示した。
     *
     * [fromIndex] は起動を試し始めるメンバーの位置。既定の 0（従来の意味）は
     * 「グループの先頭から順に試す」であり、既存の呼び出し元は無改変でよい。
     * 0 以外が入るのは、一覧の行から「初回ログイン」を指示された場合だけである
     * ——有人の接続は常に先頭メンバーから始まるので、初回ログインが必要な
     * 接続先が2番目以降にあると到達する手段が無い（一覧には「初回ログインが
     * 必要」と出るのに利用者にできることが無い）という穴を埋めるためのもの。
     *
     * **uuid ではなく添字を運ぶ。** グループ定義を引いて uuid を添字に直すのは
     * `FailoverService` 側の責任で（そちらは Android の `Intent` から uuid を
     * 受け取る）、[FailoverController] にグループ検索の責務を増やさない。
     *
     * 範囲外の値（メンバーが存在しない位置）の扱いは
     * `FailoverController.onUserConnect` の KDoc を参照。
     */
    data class UserConnectGroup(
        val groupId: String,
        val fromIndex: Int = 0,
    ) : FailoverEvent

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
