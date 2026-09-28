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

/**
 * トンネルの累計バイト数を読む。読めなければ null を返し、例外は投げない。
 *
 * 裁定R21: [HealthProbe.probe] と同じく `suspend` である。呼び出し元
 * （[FailoverService] のティックループ）はメインスレッドに固定されているので
 * （理由は `FailoverService` の `scope` の KDoc）、**実装側が内部で
 * `Dispatchers.IO` へ逃げる責任を持つ**。呼び出し元は suspend するだけで、
 * メインスレッドがファイル読み取りでブロックしない。
 */
interface ThroughputSource {
    suspend fun read(): IfaceBytes?
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
     * なぜ port に置くか（レビュー5・low）: 状態機械の外にも同じ判定が要る。
     * 一覧の行から「初回ログイン」を実行する前に、**起動が失敗する条件なら
     * そもそも指示しない**（指示してしまうと、状態機械は `Idle` を返すだけで
     * 鍵が動かず、行が「初回ログイン中」と嘘をつき続ける:
     * `HomeRows.firstLoginAttemptProgress` の KDoc）。
     *
     * この判定は Kotlin 側に**4つの写し**として散っていた。ここに集約した:
     *
     * - `OpenConnectVpnController.connect`（[ConnectResult.NeedsUserConsent] を返す条件）
     * - `HomeScreen`（許可画面の出し分けと、「初回ログイン」の事前確認）
     * - `FailoverService.onScreenOn`（点灯時の繋ぎ直し。許可が無ければ通知して戻る）
     * - `FailoverService.restoreActiveGroupIfAny`（プロセス復帰時。向きが逆で、
     *   許可が**ある**ときだけ繋ぎ直す）
     *
     * 同じ業務規則の写しが散っていると、片方だけが変わっても誰も気づかない。
     * 新しく「許可があるか」を知りたい場所ができたときも、`VpnService.prepare` を
     * 書かずにこの関数を使うこと。
     *
     * （許可**画面を起動する**側は別物である: `VpnConsent.kt` が要るのは真偽値では
     * なく `VpnService.prepare` が返す `Intent` 自身なので、あちらは対象外。
     * 既存 Java の `GrantPermissionsActivity` / `ExternalOpenVPNService` も
     * このリファクタの対象外である——既存 Java は変更しない。）
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
