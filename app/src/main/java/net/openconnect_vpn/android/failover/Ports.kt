package net.openconnect_vpn.android.failover

/**
 * 状態機械がプラットフォームに触るための境界。
 * ここを interface にしておくことで FailoverController を JVM 単体テストできる。
 */

interface Clock {
    fun nowMs(): Long
}

interface HealthProbe {
    /** [target] へ疎通できたら true。例外は投げず false を返す。 */
    suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean
}

enum class ConnectResult {
    /** 接続処理を開始した。 */
    Started,

    /** VPN 許可が未取得のため自動接続できない。 */
    NeedsUserConsent,

    /** プロファイルが見つからない等で開始できなかった。 */
    Failed,
}

interface VpnController {
    /**
     * VPN の利用許可が無く、**人に許可を取り直してもらわないと接続を開始できない**か。
     *
     * [connect] が [ConnectResult.NeedsUserConsent] を返す条件そのものであり、
     * 実装はこの1か所で判定して [connect] からもここを使うこと
     * （`OpenConnectVpnController` はそうしている）。
     *
     * なぜ port に置くか（レビュー5・low）: 画面側にも同じ判定が要る。
     * 一覧の行から「初回ログイン」を実行する前に、**起動が失敗する条件なら
     * そもそも指示しない**（指示してしまうと、状態機械は `Idle` を返すだけで
     * 鍵が動かず、行が「初回ログイン中」と嘘をつき続ける:
     * `HomeRows.firstLoginAttemptProgress` の KDoc）。以前はその判定を
     * `HomeScreen` が `VpnService.prepare(context) != null` と**独立に書いていた**。
     * 同じ業務規則の写しが2つあると、片方だけが変わっても誰も気づかない。
     *
     * 判定の実体（Android API）は実装側にある。この interface 自体は Android に
     * 依存しないので、[FailoverController] を JVM 単体テストできる性質は変わらない。
     */
    fun needsUserConsent(): Boolean

    fun connect(uuid: String): ConnectResult

    /** ユーザー意図または切替による明示的な切断。 */
    fun disconnect()
}

interface NetworkGate {
    /** Wi-Fi 等の下層ネットワークが使える状態なら true。 */
    fun hasUnderlyingNetwork(): Boolean
}

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
