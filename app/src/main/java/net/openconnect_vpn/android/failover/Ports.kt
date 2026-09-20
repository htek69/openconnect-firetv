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
