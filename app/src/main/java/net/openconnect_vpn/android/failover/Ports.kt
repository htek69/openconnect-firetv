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

    /**
     * 仕様書 §2「測り方」: 最大 [attempts] 回続けて疎通確認を行い、**実際に行った各回の
     * 所要時間**を返す。[attempts] が 1 未満なら 1 回として扱う。
     *
     * **到達可否は1回目の試行の結果そのものである。** 「1回でも成功すれば可」ではない。
     * [probe] は接続を1回しか行わないので、従来の `reachable` は「1回の試行の結果」だった。
     * それを「5回のどれか」に置き換えると、失敗が5回とも続かないかぎり成功と数えられ、
     * 死活判定が**はるかに寛容**になる（接続の8割が失敗する経路でも、3周期連続の
     * 失敗は約0.51から約0.035へ落ちる）。`failureThreshold` に届かなくなり、
     * 既存の死活による切替が最も要る状況で働かなくなる。1回目で決めれば、
     * 失敗の数え方も周期の意味も従来と厳密に同じである。残りの試行は
     * 応答時間を測るためだけに走らせる。
     *
     * **1回目が失敗したら、残りは行わない。** 到達可否はもう決まっており、切替へ
     * 向かう経路の応答時間を測る意味が無い。サンプルを取り逃すのは判定を偽にする
     * 方向なので、誤った切替は生まない。1周期の最悪の所要時間も [timeoutMs] 1回ぶんに
     * 戻る（間隔の最小は10秒）。
     *
     * **1回目が成功したら、残りを合計 `timeoutMs * 2` の壁時計予算の範囲で行う。**
     * 予算は**周期全体の上限**であり、超えない。2回目以降は「**この回を最後まで
     * 待てるだけの予算が残っているときだけ**」開始する（経過時間 + `timeoutMs` が予算以内）。
     * 経過時間だけを見て開始すると、予算の直前に始めた回が `timeoutMs` まで待って
     * 予算を超える（最悪 3 倍）。間隔の最小 10 秒（既定の `timeoutMs` 5 秒の 2 倍）に
     * 収まらなくなり、収まらないあいだ tick ループの採取も他のイベントも止まる。
     *
     * **回の途中で `timeoutMs` を切り詰めない。** 残りに合わせて打ち切ると、5 秒以上
     * かかる経路が 2 秒の応答時間として記録され、測定が歪む。回は満額の `timeoutMs` を
     * 得て走るか、走らないかのどちらかである。
     *
     * 予算の都合で回数が減る（既定で遅い経路は最大3回）のは、サンプルが減って判定が
     * 偽に寄る方向なので、**誤った切替は生まない**。成功は数ミリ秒で返るので、
     * まともな経路では全回が入る。
     *
     * **既定実装は [probe] を呼んで時間を測るだけ**である。
     * これにより**既存の実装とテストの Fake は無改変で通る**（裁定72/93 と同じ
     * 既定値の作法）。実測の精度が要る本番実装（[TcpHealthProbe]）はこれを
     * 上書きする。
     *
     * **失敗した回も所要時間を数える。ただし数えるのは実測した経過時間である。**
     * 失敗した回を `timeoutMs` で埋めてはならない。接続拒否や名前解決の失敗は
     * 数ミリ秒で返り、それを timeoutMs（例: 5000ms）の遅延として記録するのは
     * 事実に反する。拒否は「遅い」ではなく「届かない」であり、それは [reachable] と
     * 既存の `failureThreshold` が扱う。間欠的なタイムアウトだけが timeoutMs 前後の
     * 大きな値として残り、劣化の証拠になる（[ProbeOutcome] の KDoc）。
     */
    suspend fun probeTimed(target: ProbeTarget, timeoutMs: Int, attempts: Int): ProbeOutcome =
        runTimedCycle(timeoutMs, attempts, System::nanoTime) { probe(target, timeoutMs) }
}

/**
 * [HealthProbe.probeTimed] の周期の進め方（1回目で到達可否を決める・失敗したら止める・
 * 成功したら予算の範囲で続ける）。既定実装と [TcpHealthProbe] が同じ規則を共有し、
 * 時計を引数で受け取るので**待たずに**試験できる。
 *
 * [attempt] へ渡す `logFailure` は、その回の失敗を記録してよいか。**1周期の失敗ログは
 * 最大1行**（[TcpHealthProbe.probeTimed] の KDoc）で、最初に失敗した回だけが true を受け取る。
 * 1回目が失敗すれば残りを行わないので、失敗しているリンクのログ行数は従来の
 * 「1回の試行で1行」と同じである。
 */
internal suspend fun runTimedCycle(
    timeoutMs: Int,
    attempts: Int,
    nowNs: () -> Long,
    attempt: suspend (logFailure: Boolean) -> Boolean,
): ProbeOutcome {
    val n = attempts.coerceAtLeast(1)
    val budgetNs = timeoutMs.coerceAtLeast(0) * 2L * 1_000_000L
    val cycleStartNs = nowNs()
    val times = ArrayList<Long>(n)

    val firstStartNs = nowNs()
    val reachable = attempt(true)
    times.add((nowNs() - firstStartNs) / 1_000_000L)
    // 到達可否は1回目で決まる。失敗なら残りは行わない。
    if (!reachable) return ProbeOutcome(reachable = false, rttMs = times)

    var loggedThisCycle = false
    val timeoutNs = timeoutMs.coerceAtLeast(0) * 1_000_000L
    for (i in 1 until n) {
        // この回を満額の timeoutMs まで待てるだけ残っているときだけ始める。
        // 経過時間だけを見ると、予算の直前に始めた回が予算を超える。
        if (nowNs() - cycleStartNs + timeoutNs > budgetNs) break
        val startNs = nowNs()
        val ok = attempt(!loggedThisCycle)
        times.add((nowNs() - startNs) / 1_000_000L)
        if (!ok) loggedThisCycle = true
    }
    return ProbeOutcome(reachable = true, rttMs = times)
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

/**
 * いま生きている VPN トンネルのインターフェース名。決められなければ null。
 *
 * **固定名で測ってはいけない**ので port にしてある。Android は VPN を張り直す
 * たびに新しい `tun` 番号を割り当て、古いものは `DOWN` のまま残る
 * （`docs/DECISIONS.md` 項目15、[selectVpnInterfaceName] の KDoc）。
 *
 * `suspend` ではない。実装（[ConnectivityVpnInterfaceName]）は
 * `ConnectivityManager` への問い合わせだけで、ファイルを読まない。
 * それでも binder 越しなので、呼び出し側（`FailoverService.sampleThroughput`）は
 * 裁定R21 に合わせて `Dispatchers.IO` の中で呼ぶ。
 *
 * **null は「測れない」であり「遅い」ではない。** 呼び出し側はそのときサンプルを
 * 渡さず、窓を捨てる（裁定R20）。
 */
fun interface VpnInterfaceNameSource {
    fun currentName(): String?
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
