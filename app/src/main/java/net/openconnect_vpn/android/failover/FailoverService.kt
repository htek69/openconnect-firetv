package net.openconnect_vpn.android.failover

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.preference.PreferenceManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.openconnect_vpn.android.core.ProfileManager

/**
 * フェイルオーバー状態機械を駆動するフォアグラウンドサービス。
 *
 * 状態機械そのもの（FailoverController）は Android 非依存で、ここが
 * ブロードキャスト・タイマー・プローブ実行という副作用だけを担う。
 */
class FailoverService : Service() {

    /**
     * Ruling 16: FailoverController は一切同期化されていない（state・除外集合・
     * needsUserConsent などをロック無しで保持する）。dispatch() の呼び出し元は
     * ブロードキャスト受信（VpnStatusBridge・本サービスの ScreenReceiver。どちらも
     * デフォルトでメインスレッド配送）、onStartCommand（メインスレッド）、そして
     * このティックループの3経路あり、ディスパッチャを指定しないと
     * ティックループは Dispatchers.Default の任意のプールスレッドで走る。
     * 3経路すべてを同じスレッド（メイン）に固定することで、事実上のロックとして
     * 機能させ、状態機械へのデータ競合を無くす。
     *
     * これは実測プローブをメインスレッドでブロックすることを意味しない。
     * TcpHealthProbe.probe は内部で withContext(Dispatchers.IO) しているため、
     * ここから呼んでも実際のソケット処理は IO ディスパッチャに逃げ、
     * このコルーチンは suspend するだけである（delay も同様）。
     *
     * これを Dispatchers.Default に「最適化」で戻すと、この協調が失われて
     * 元のデータ競合が復活するので変更しないこと。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    /**
     * 裁定43（欠陥17）: 監視中に CPU を起こしておくための部分 wake lock。
     * 詳細は [syncWakeLock]。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Ruling 27b: 外部イベントでループを早起こしするための合図。 */
    private val wakeLoop = Channel<Unit>(Channel.CONFLATED)

    private lateinit var controller: FailoverController
    private lateinit var probe: HealthProbe
    private lateinit var groupStore: GroupStore
    private lateinit var bridge: VpnStatusBridge
    private lateinit var networkGate: NetworkGate
    private lateinit var throughputSource: ThroughputSource
    private var probeTarget: ProbeTarget = ProbeTarget()

    /**
     * 仕様書 4: [SlowLinkDetector.onSample] へ渡す時刻と [controller] が使う時刻を
     * 同じ1つの時計から取るために、ここで持って両方へ渡す。
     *
     * [SlowLinkDetector] は前回サンプルからの経過が 0 以下のとき速度を計算せずに
     * 基準を取り直す。それが安全なのは**この時計が巻き戻らない**からである。
     * [SystemClock] は `android.os.SystemClock.elapsedRealtime()`（起動からの経過
     * 時間）で、端末時刻の変更や NTP 補正では戻らない。壁時計
     * （`System.currentTimeMillis()`）へ差し替えると、補正で時刻が戻った瞬間に
     * 判定器が基準を取り直すだけでは済まず、窓の数え方そのものが壊れる。
     */
    private val clock: Clock = SystemClock()

    /**
     * 仕様書 5: スループット低下による切替の設定。[onCreate] で読み込み、以後は
     * [reloadSlowLinkSettings] が SharedPreferences の変更を検知するたびに
     * 読み直す。[FailoverController] へは `slowLinkProvider` という供給関数の
     * 形で渡してあるので、このフィールドを差し替えるだけで次回参照から新しい
     * 値が見える（コントローラは作り直さない。裁定72 と同じ理由）。
     */
    private var slowLinkSettings: SlowLinkSettings = SlowLinkSettings()

    /**
     * 仕様書 4-2 の判定器。閾値（[SlowLinkThresholds]）はコンストラクタ引数なので、
     * 閾値が変わったときは**作り直す**（[reloadSlowLinkSettings]）。そのため
     * `var` であり、`slowLinkProvider` のラムダはインスタンスを捕獲せずこの
     * フィールドを毎回読む。
     *
     * 差し替えとサンプル採取・[reset] はすべてメインスレッド上で起きる
     * （prefs リスナ・ティックループ・ブロードキャスト受信の3経路すべてが
     * メイン。理由は [scope] の KDoc）ので、同期化は要らない。
     */
    private var slowLinkDetector: SlowLinkDetector = SlowLinkDetector(SlowLinkThresholds())

    /**
     * 直前に [slowLinkDetector] を向けていた候補の識別子（[slowLinkCandidateKey]）。
     * 変わったら測り直す。詳細は [syncSlowLinkCandidate]。
     */
    private var lastSlowLinkCandidateKey: String? = null

    /**
     * 裁定48/72: TV UI が保存したグループ・プローブ宛先の変更を検知するための
     * リスナ。onCreate で登録し onDestroy で解除する。[FailoverController] には
     * `{ groups }` という供給関数を渡してあるので、このサービスが持つ [groups]
     * を差し替えるだけでコントローラ側の次回参照から新しい値が見える。
     * ここでは [groups]/[probeTarget] を更新して [wakeLoop] を早起こしする
     * だけでよい（コントローラを作り直さない。作り直すと接続中の状態・
     * 除外集合・世代カウンタを失う）。
     */
    private lateinit var prefs: SharedPreferences
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!groupStore.isReloadTriggerKey(key)) return@OnSharedPreferenceChangeListener
            reloadGroupsAndProbeTarget()
        }

    /**
     * 裁定72-fix(F4): onCreate で最初に読み込み、以後は [reloadGroupsAndProbeTarget]
     * が SharedPreferences の変更を検知するたびに読み直す、生きているグループ一覧
     * （もう「onCreate 時点で固定」ではない）。Ruling 5 のプローブタイムアウト解決
     * のために、現在の状態が指す groupId からここを引いて config を取り出す。
     * FailoverController にはこのフィールド専用の新しい公開APIを足さない
     * （`groupsProvider = { groups }` としてコンストラクタに渡すだけでよい）。
     */
    private var groups: List<FailoverGroup> = emptyList()

    /** Minor fix: 直前に実際に反映した通知文言。同じ文言では再投稿しない。 */
    private var lastForegroundText: String? = null

    /**
     * 裁定41（M5）: 直前に反映した [FailoverController.needsUserConsent] の値。
     * `dispatch` は Tick のたびに呼ばれる（画面点灯時5秒ごと）ため、この値を
     * 見ずに毎回 `alert` すると同一 ID の通知が永久に再投稿され、ユーザーが
     * 消しても5秒後に復活して実質消せなくなる。`false → true` の立ち上がり
     * エッジでだけ投稿し、`true → false` になったら取り下げる。
     */
    private var lastNeedsUserConsent = false

    /**
     * Ruling 17: 画面が点いているか。消灯中はティック間隔を延ばす。
     * 裁定44: 消灯中は接続しないので、この値は onCreate で実機の状態から初期化する
     * （`true` 固定だと、消灯中に起動したサービスが繋ぎ始めてしまう）。
     */
    private var screenOn = true

    /**
     * Ruling 17: 画面点灯時に「次のティックで無条件にプローブする」ことを示すフラグ。
     * Healthy のときだけ立てる（Verifying 中に立てて S4 の猶予期間を迂回しないため）。
     * 一度使ったら消費する。
     */
    private var probeOnScreenWake = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    onScreenOff()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (controller.state is FailoverState.Healthy) {
                        probeOnScreenWake = true
                    }
                    onScreenOn()
                }
            }
        }
    }

    /**
     * 裁定44: 画面消灯時は VPN を切断する（利用者の設計判断）。
     *
     * 理由は欠陥17 の測定結果である。消灯中に接続を維持しようとすると、端末が
     * 深く眠った時点で状態機械が止まる。部分 wake lock（裁定43）は6分の消灯では
     * 30秒間隔の tick を維持できたが、27分の消灯では tick がゼロになった。
     * 維持を追求するなら `AlarmManager.setExactAndAllowWhileIdle()` が必要で、
     * それでも Doze 中の起床は OS 側で9〜15分に1回程度に絞られるため、
     * 「消灯中も監視できている」とは言い切れない状態が残る。
     *
     * TV が消灯している間に VPN が必要な場面は無いので、消灯したら切り、
     * 点灯したら繋ぎ直す。これにより「消灯中は常に `Idle`」となり、
     * 裁定43 の wake lock も自動的に解放される（`Idle` では保持しない）ので、
     * 消灯中に電力を使い続けることも無い。Doze への対処そのものが不要になる。
     *
     * `activeGroupId` は**消去しない**。点灯時に同じグループへ繋ぎ直すためである
     * （`ACTION_DISCONNECT` 経由の明示的な切断とはここが違う）。
     */
    private fun onScreenOff() {
        if (controller.state is FailoverState.Idle) return
        Log.d(LOG_TAG, "screen off -> disconnect")
        dispatchExternal(FailoverEvent.UserDisconnect)
    }

    /**
     * 裁定44: 点灯時に、記録されているグループへ繋ぎ直す。
     *
     * [FailoverEvent.AutoConnectGroup]（無人扱い）を使い、
     * [FailoverEvent.UserConnectGroup] は使わない。点灯は「利用者が明示的に接続を
     * 指示した」操作ではないので、裁定35 の除外集合クリアを起こしてはならない。
     * 毎回クリアすると、認証情報が誤っている候補を TV の電源操作のたびに
     * 再試行することになり、S1 が防いでいるサーバ側のアカウントロックを招く。
     * 除外をやり直したい場合は、利用者が UI から明示的に接続を指示する。
     */
    private fun onScreenOn() {
        if (controller.state !is FailoverState.Idle) return
        val activeGroupId = groupStore.loadActiveGroupId() ?: return
        if (VpnService.prepare(this) != null) {
            FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
            return
        }
        Log.d(LOG_TAG, "screen on -> reconnect $activeGroupId")
        dispatchExternal(FailoverEvent.AutoConnectGroup(activeGroupId))
    }

    override fun onCreate() {
        super.onCreate()
        FailoverNotifications.ensureChannel(this)
        startForegroundCompat("待機中")
        lastForegroundText = "待機中"

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        groupStore = GroupStore(PrefsKeyValueStore(prefs))
        probe = TcpHealthProbe()
        networkGate = ConnectivityNetworkGate(this)
        probeTarget = groupStore.loadProbeTarget()

        // 仕様書 4: 採取元と判定器。判定器の閾値は保存された設定から作る。
        throughputSource = ProcNetDevThroughputSource()
        slowLinkSettings = groupStore.loadSlowLinkSettings()
        slowLinkDetector = SlowLinkDetector(
            SlowLinkThresholds(slowRxKbps = slowLinkSettings.slowRxKbps),
        )

        ProfileManager.init(this)
        // VpnProfile の実際のアクセサは getUUIDString()（Task 12 Step 6 で確認）。
        // "UUID" のように連続する大文字2文字で始まる getter は Kotlin の
        // プロパティ合成規則（java.beans.Introspector.decapitalize 相当）で
        // 先頭を小文字化しないため、`it.uuidString` は解決できず、正しい合成名は
        // `it.UUIDString` になる。曖昧さを避けるため、ここでは Java の getter を
        // そのまま呼ぶ。
        val knownUuids = ProfileManager.getProfiles().map { it.getUUIDString() }.toSet()

        groups = groupStore.loadGroups(knownUuids)
        controller = FailoverController(
            groupsProvider = { groups },
            clock = clock,
            vpn = OpenConnectVpnController(this),
            network = networkGate,
            // 裁定93: 「人が認証ダイアログに答えられるか」を供給する。Android API を
            // 参照するのはこちら側の責任で、FailoverController は真偽値だけを見る。
            // 値の実体は OpenVpnService.mActivityConnections > 0 に相当する
            // （なぜ直接読まないかは DialogHostTracker の KDoc 参照）。
            dialogHostAttachedProvider = { DialogHostRegistry.tracker.attached },
            // 仕様書 4-3: 「生きてはいるが遅い」を供給する。`/proc/net/dev` の
            // 読み取りと設定の有効・無効はこちら側の責任で、
            // FailoverController は真偽値だけを見る。機能が無効なら判定器を
            // 一切参照せず必ず false になる（無効時に切替が起きない保証）。
            // [slowLinkDetector] は差し替わりうるのでフィールドを毎回読む。
            slowLinkProvider = { slowLinkSettings.enabled && slowLinkDetector.isSlow() },
        )

        bridge = VpnStatusBridge(this) { event -> dispatchExternal(event) }
        bridge.register()
        registerScreenReceiver()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenConnectTV:failover")
            ?.apply { setReferenceCounted(false) }
        // 裁定44: 実機の画面状態から初期化する。isInteractive は API 20 以降。
        screenOn = powerManager?.isInteractive ?: true

        restoreActiveGroupIfAny()

        loop = scope.launch {
            var lastNetworkAvailable = networkGate.hasUnderlyingNetwork()
            while (true) {
                val available = networkGate.hasUnderlyingNetwork()
                if (available != lastNetworkAvailable) {
                    lastNetworkAvailable = available
                    dispatch(FailoverEvent.UnderlyingNetworkChanged(available))
                }

                val wake = probeOnScreenWake
                if (wake) probeOnScreenWake = false
                val forcedByWake = wake &&
                    controller.state is FailoverState.Healthy &&
                    networkGate.hasUnderlyingNetwork()

                if (controller.shouldProbeNow() || forcedByWake) {
                    val reachable = probe.probe(probeTarget, currentProbeTimeoutMs())
                    dispatch(FailoverEvent.ProbeResult(reachable))
                }
                // 仕様書 4-2: Tick を投げる前に採取する。速度の判定を読むのは
                // この直後の Tick 処理（onSlowLinkSwitch）なので、同じ tick の
                // 中で「採取 → 判定」の順になる。
                sampleThroughput()
                dispatch(FailoverEvent.Tick)
                val interval = when {
                    // Ruling 25/27a: 切替中は切断完了の確認待ちなので細かく tick する。
                    // ただし S2 で保留されている（下層ネットが無い）間は待ちが
                    // 進まないので、細かく起きる意味がない。待機間隔に戻す。
                    controller.state is FailoverState.FailingOver &&
                        networkGate.hasUnderlyingNetwork() -> TICK_INTERVAL_SWITCHING_MS

                    screenOn -> TICK_INTERVAL_MS
                    else -> TICK_INTERVAL_STANDBY_MS
                }
                // Ruling 27b: 外部イベントが来たら待たずに起きる。
                withTimeoutOrNull(interval) { wakeLoop.receive() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_GROUP -> intent.getStringExtra(EXTRA_GROUP_ID)?.let { groupId ->
                // Ruling 18: 次回 onCreate（プロセス kill 後の復帰）で同じグループへ
                // 自動的に再接続できるよう、要求された時点で記録する。
                groupStore.saveActiveGroupId(groupId)
                dispatchExternal(FailoverEvent.UserConnectGroup(groupId))
            }

            ACTION_DISCONNECT -> {
                groupStore.saveActiveGroupId(null)
                dispatchExternal(FailoverEvent.UserDisconnect)
            }
        }
        return START_STICKY
    }

    /**
     * Ruling 18: プロセス kill からの復帰。記録されたグループ ID があり、かつ
     * VPN 許可がまだ有効なら自動的に再接続を試みる。許可が失われていた場合は
     * 黙って失敗させず、既存の「許可が必要」通知を出す。ID が記録されていなければ
     * 何もしない（Idle から再起動したサービスは Idle のまま）。
     *
     * 裁定36（M2）: これは Ruling 18 が想定する「TV の前に人がいない」場面の
     * 典型（低メモリで殺されたサービスが夜中に復帰する、等）である。
     * [FailoverEvent.UserConnectGroup] を投げると候補が「有人」扱いになり、
     * 裁定30 の「認証ダイアログで止まったら除外して次へ」が働かないまま
     * 45秒タイムアウトを無期限に繰り返してアカウントロックへ向かう
     * （実機で確認された欠陥の再現条件）。[FailoverEvent.AutoConnectGroup] を
     * 使い、無人として開始する。
     */
    private fun restoreActiveGroupIfAny() {
        val activeGroupId = groupStore.loadActiveGroupId() ?: return
        // 裁定44: 画面が消えている間は接続しない。プロセス kill からの復帰や
        // 起動時に、消灯中の端末で勝手に繋ぎ始めないようにする。点灯時に
        // onScreenOn() が繋ぎ直す。
        if (!screenOn) {
            Log.d(LOG_TAG, "restore skipped: screen is off")
            return
        }
        if (VpnService.prepare(this) == null) {
            // ループ起動前に一度だけ走るため、dispatchExternal の合図（早起こし）は
            // 不要（Ruling 27b 参照）。
            dispatch(FailoverEvent.AutoConnectGroup(activeGroupId))
        } else {
            FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
        }
    }

    /**
     * 裁定48/72: TV UI が保存したグループ一覧またはプローブ宛先が変わったときに
     * [prefsListener] から呼ばれる。[FailoverController] を作り直さず、
     * コンストラクタに渡した供給関数 `{ groups }` が次に参照したときに新しい
     * 値を返せるよう、この [groups] フィールドを差し替えるだけでよい。
     * [probeTarget] も同様に読み直す。最後に [wakeLoop] を早起こしし、次の
     * Tick 分の遅延を待たずに新しい設定（特に R6 の自動切替 ON/OFF）を
     * 反映させる。
     *
     * `ProfileManager.getProfiles()` は [onCreate] と同じ理由（既に削除された
     * プロファイルの UUID をメンバーから除去する）で毎回引き直す。
     */
    private fun reloadGroupsAndProbeTarget() {
        val knownUuids = ProfileManager.getProfiles().map { it.getUUIDString() }.toSet()
        groups = groupStore.loadGroups(knownUuids)
        probeTarget = groupStore.loadProbeTarget()
        reloadSlowLinkSettings()
        wakeLoop.trySend(Unit)
    }

    /**
     * 仕様書 5: スループット低下による切替の設定を読み直す。
     * [reloadGroupsAndProbeTarget] から呼ばれる（`KEY_SLOW_LINK` は Task 3 で
     * [GroupStore.isReloadTriggerKey] に含めてあるので、既存の再読込経路に
     * そのまま乗る。新しいシグナリング経路は作らない）。
     *
     * 実際に値が変わっていなければ何もしない。このリスナはグループ・プローブ
     * 宛先・プローブ間隔・プロファイル世代の変更でも呼ばれるため、無条件に
     * 下の2つを行うと、無関係な保存（グループ名の変更など）のたびに
     * 判定中の窓が消え、仕様書 4-5 の「一周したら止める」も解除されてしまう。
     *
     * 値が変わっていたときにやることは2つある。
     *
     * 1. **判定器を作り直す。** [SlowLinkThresholds] は
     *    [SlowLinkDetector] のコンストラクタ引数なので、一度作った判定器は
     *    閾値を変えられない。作り直さないと、利用者が閾値を変えてもアプリを
     *    再起動するまで何も起きない（Task 3 で `KEY_SLOW_LINK` を再読込
     *    トリガに含めた意味が無くなる）。新しい判定器は全フィールドが初期値
     *    なので、古いサンプルを引き継がずに窓を数え直す——閾値が変わった
     *    のだから、古い閾値で「遅い」と数えた時間に意味は無い。
     * 2. **[FailoverController.onSlowLinkSettingsChanged] を呼ぶ。**
     *    仕様書 4-5 が定める「設定変更は新しい指示なので一周の記録を解除する」
     *    ためである。呼ばないと、一周して止まったあとに閾値を上げた利用者から
     *    見て機能が壊れているように見える（一周の記録は UI に出ない内部状態
     *    なので、なぜ動かないのか分からない）。時間による再武装ではないので
     *    仕様書 4-5 の禁止には触れない。
     */
    private fun reloadSlowLinkSettings() {
        val loaded = groupStore.loadSlowLinkSettings()
        if (loaded == slowLinkSettings) return
        slowLinkSettings = loaded
        slowLinkDetector = SlowLinkDetector(SlowLinkThresholds(slowRxKbps = loaded.slowRxKbps))
        controller.onSlowLinkSettingsChanged()
    }

    /**
     * 仕様書 4-2: トンネルの累計バイト数を1回採取して判定器へ渡す。
     *
     * 機能が無効なら**何もしない**（Fix 1）。既定は無効であり、Fire TV Stick は
     * 非力な機器なので、使っていない機能のために毎ティック `/proc/net/dev` を
     * 読んで解析する費用を全利用者に払わせる理由が無い。ここで返る分の判定は
     * `slowLinkProvider`（`enabled` を先に見る）と二重になるが、そちらは
     * 「無効なら切り替えない」、こちらは「無効なら測らない」であり別の目的である。
     *
     * 無効の期間をまたいで古い判定が生き残らないことは、判定器を**作り直す**
     * ことで担保している（[reloadSlowLinkSettings]）。`enabled` が変われば
     * [SlowLinkSettings] の値が変わるので、無効化した瞬間にも有効化した瞬間にも
     * 新しい判定器に差し替わる。したがって
     *
     * - 無効化した時点で、それまでに数えた「遅い」の連続は消える
     * - 有効化した時点の判定器は空で、`isSlow()` が真になるには有効化後に
     *   改めて 60 秒ぶんの連続が必要になる（古い窓の再開にはならない）
     *
     * `reset()` を足すのではなく差し替えで済ませたのは、新しいインスタンスは
     * 定義上あらゆる内部状態が初期値であり、「消し忘れた項目」が原理的に
     * 存在しないためである（R12 の作り直しが同時にこの保証も与えている）。
     *
     * **読めなかったときは [SlowLinkDetector.onSample] を呼ばない。**
     * 「測れない」を「遅い」と解釈してはならない。`?.let` がその唯一の関門で、
     * このサービスの中で `onSample` を呼ぶ場所はここだけである。
     * [ProcNetDevThroughputSource] は、ファイルが読めない場合と `tun0` の行が
     * 無い場合（＝切断中の通常の状態）の両方で null を返す。
     *
     * 機能が有効なら接続していない間も呼ぶが、それで誤判定は起きない。tun が
     * 無ければ null で何も渡らず、万一 tun が残っていてもカウンタは進まないので
     * 送信速度が「使おうとしている」閾値を超えず、`SlowLinkDetector` の else 側
     * （条件が破れたら数え直す）に入って連続が 0 に戻る。
     * さらに候補が変われば [syncSlowLinkCandidate] が測り直させる。
     */
    private fun sampleThroughput() {
        if (!slowLinkSettings.enabled) return
        throughputSource.read()?.let { slowLinkDetector.onSample(clock.nowMs(), it) }
    }

    /**
     * 仕様書 4-2: 測っている対象が変わったら [SlowLinkDetector] を測り直させる。
     * 前の候補の測定値を次の候補へ持ち越さないためである（持ち越すと、遅かった
     * 候補から切り替えた直後に、新しい候補が自分の速度を1つも記録しないうちに
     * 「遅い」と判定されうる）。
     *
     * `dispatch` から、状態を [FailoverStateHolder] へ publish するのと同じ
     * 場所で呼ぶ。状態機械を動かすのは `controller.handle` の1か所だけで、
     * それを呼ぶのは `dispatch` だけなので、**状態が変わる経路は必ずここを通る。**
     * 専用のフックは増やさない。
     *
     * [slowLinkCandidateKey] が null を返す状態（`Idle`・`Connecting`・
     * `FailingOver`・`Exhausted`）はどれも「測る対象のトンネルがまだ／もう
     * 無い」ことを意味するので、切断（利用者の切断・画面消灯による切断・
     * 障害による切替・全滅）はすべてここで測り直しになる。
     * `Verifying` から `Healthy` への昇格ではグループと候補番号が変わらないので
     * 測り直さない（同じトンネルだから）。
     */
    private fun syncSlowLinkCandidate() {
        val key = slowLinkCandidateKey()
        if (key == lastSlowLinkCandidateKey) return
        lastSlowLinkCandidateKey = key
        slowLinkDetector.reset()
    }

    /**
     * いま [SlowLinkDetector] が測っている対象の識別子。トンネルを持たない状態は
     * null。詳細は [syncSlowLinkCandidate]。
     *
     * Fix 2: `Connecting` は null である。`Connecting` はまだトンネルが張れて
     * いない段階（コアの `Connected` 通知を待っている）なので、そこで採取した
     * バイト数は**このトンネルの速度ではない**（前のインターフェースの残骸、
     * あるいは別の候補が残した値でありうる）。null にしておくと、コアが
     * `Connected` を通知して状態が `Verifying` に進んだ瞬間——`Verifying` へ
     * 入る経路は `FailoverController` の
     * `core == VpnCoreState.Connected && s is Connecting` だけであり、`Healthy`
     * へ入る経路は `Verifying` からのプローブ成功だけである——にキーが
     * null から `"<groupId>#<candidateIndex>"` へ変わり、[syncSlowLinkCandidate]
     * が判定器を空にする。
     *
     * これにより「トンネルが張れる前の採取が、張れた後に下される判定の窓へ
     * 混じらない」ことが**キーの形で明示的に保証される**。判定器の
     * 「累計値が巻き戻ったら数え直す」という性質（インターフェース再作成時に
     * 偶然そう振る舞う）に依存しない。`reset()` は `lastAtMs`/`lastBytes` も
     * 消すので、`Verifying` 以後の最初の採取は差分の計算に使われず基準に
     * なるだけであり、最初に計算される速度は完全にトンネル確立後の区間から出る。
     */
    private fun slowLinkCandidateKey(): String? = when (val s = controller.state) {
        FailoverState.Idle -> null
        is FailoverState.Connecting -> null
        is FailoverState.Verifying -> "${s.groupId}#${s.candidateIndex}"
        is FailoverState.Healthy -> "${s.groupId}#${s.candidateIndex}"
        is FailoverState.FailingOver -> null
        is FailoverState.Exhausted -> null
    }

    /**
     * 裁定43（欠陥17）: 状態機械が動いているあいだ CPU を起こしておく。
     *
     * 実機で観測した欠陥17: 画面消灯後、**68分間 tick が1度も走らなかった**
     * （当時は Task 13 の検証ハーネスが dispatch のたびに状態をログへ出していた
     * ので、ログ0行＝tick 0回と読めた。ハーネスは裁定86（L2）で削除済み）。
     * コルーチンの `delay` は端末が深いスリープに入ると発火しない。つまり
     * 画面消灯中はフェイルオーバー機能が丸ごと停止し、トンネルが落ちても
     * 復旧しない。Fire TV はほとんどの時間眠っている機器なので、実運用時間の
     * 大半でエンジンが動いていないことになる。
     *
     * Fire TV Stick は電源に常時接続された据置機であり、電池を気にする必要が
     * 無い。したがって「接続を維持しているあいだ CPU を起こしておく」という
     * 素直な手段が取れる。`Idle` のときは保持しない（何も監視していないので
     * 起きている必要が無い）。
     *
     * これで足りるかどうかは実機で測る。足りなければ
     * `AlarmManager.setExactAndAllowWhileIdle()` による起床へ設計変更する。
     *
     * 裁定88（Low 1）: 保持の条件を `state !is Idle` から
     * [FailoverController.requiresCpuAwake] に移した。裁定86（H2）以降、
     * 「前進できないと分かっている `FailingOver`（下層ネットが無い）」に
     * 留まったまま wake lock を握り続ける状態が日常操作から作れるように
     * なったためである（理由と S2 を壊していない根拠は
     * [FailoverController.requiresCpuAwake] の KDoc を参照）。判断そのものを
     * 状態機械側に置くのは、ここが「状態機械が動いているあいだ」という
     * 意味をそのまま尋ねる場所であり、条件を Android 側に写し取ると
     * 両者がずれるから。
     */
    private fun syncWakeLock() {
        val shouldHold = controller.requiresCpuAwake()
        val lock = wakeLock ?: return
        if (shouldHold && !lock.isHeld) {
            runCatching { lock.acquire() }
            Log.d(LOG_TAG, "wakeLock acquired")
        } else if (!shouldHold && lock.isHeld) {
            runCatching { lock.release() }
            Log.d(LOG_TAG, "wakeLock released")
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun dispatch(event: FailoverEvent) {
        controller.handle(event)
        // 裁定58: Fire TV に通知シェードは無く、フォアグラウンド通知だけでは
        // UI に接続状態が届かない。同一プロセス内の読み取り専用投影へ、
        // 状態機械（真実）が変わるたびに publish する。
        // 裁定65（指摘8）: 現在対象のグループも、このサービスが実際に使っている
        // 一覧（`groups`。裁定72-fix(F4): onCreate 時点で固定ではなく、
        // reloadGroupsAndProbeTarget が読み直すたびに更新される）から一緒に
        // publish する。UI 側で GroupStore から読み直した一覧と世代がずれても、
        // 名前解決をこの一覧と照合できるようにするため。
        FailoverStateHolder.publish(controller.state, currentGroup())
        // 仕様書 4-2: 測っている候補が変わった・トンネルが無くなったなら測り直す。
        syncSlowLinkCandidate()
        syncWakeLock()
        updateForegroundText()
        // 裁定41（M5）: lastNeedsUserConsent の説明を参照。
        val needsConsent = controller.needsUserConsent
        if (needsConsent != lastNeedsUserConsent) {
            lastNeedsUserConsent = needsConsent
            if (needsConsent) {
                FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
            } else {
                FailoverNotifications.cancelAlert(this)
            }
        }
    }

    /**
     * Ruling 27b: ブロードキャストや onStartCommand のような、ループの外から来る
     * イベント用。状態が変わったことをループに知らせ、`delay` を待たずに
     * 次の判定を走らせる。ループ自身からは呼んではならない（ビジーループになる）。
     */
    private fun dispatchExternal(event: FailoverEvent) {
        dispatch(event)
        wakeLoop.trySend(Unit)
    }

    private fun updateForegroundText() {
        val text = when (val s = controller.state) {
            FailoverState.Idle -> "待機中"
            is FailoverState.Connecting -> "接続中 (候補 ${s.candidateIndex + 1})"
            is FailoverState.Verifying -> "疎通確認中"
            is FailoverState.Healthy -> "接続済み (候補 ${s.candidateIndex + 1})"
            is FailoverState.FailingOver -> "切り替え中"
            is FailoverState.Exhausted -> exhaustedText(s)
        }
        // Minor fix: 文言が変わらない限り通知を再構築・再投稿しない
        // （5秒ごとの Tick だけで無駄な repost を繰り返さないため）。
        if (text != lastForegroundText) {
            lastForegroundText = text
            startForegroundCompat(text)
        }
    }

    /**
     * 裁定35c（M1）: `Exhausted` の文言を、全メンバーが除外済みかどうかで
     * 分岐させる。除外済みなら裁定35b により状態機械は二度と自動で再試行しない
     * ので、「再試行を待機中」と表示し続けるのは嘘になる（再試行は行われない）。
     * その場合はユーザーに認証情報の確認を促す文言にする。
     */
    private fun exhaustedText(s: FailoverState.Exhausted): String {
        val group = groups.firstOrNull { it.id == s.groupId }
        val allExcluded = group != null &&
            group.memberUuids.isNotEmpty() &&
            group.memberUuids.all { it in controller.excludedUuids }
        return if (allExcluded) {
            "認証に失敗しました。アプリを開いて認証情報を確認してください"
        } else {
            "全候補が応答しません。再試行を待機中"
        }
    }

    /**
     * Ruling 5: プローブタイムアウトは定数ではなく、現在の状態が属するグループの
     * FailoverConfig.probeTimeoutMs から取る。該当グループが見つからない場合
     * （groupId が無い Idle 状態や、既に削除済みのグループを指している場合）は
     * FailoverConfig() のデフォルト値にフォールバックする。
     */
    private fun currentProbeTimeoutMs(): Int {
        val groupId = currentGroupId() ?: return FailoverConfig().probeTimeoutMs
        val group = groups.firstOrNull { it.id == groupId } ?: return FailoverConfig().probeTimeoutMs
        return group.config.probeTimeoutMs
    }

    private fun currentGroupId(): String? = when (val s = controller.state) {
        FailoverState.Idle -> null
        is FailoverState.Connecting -> s.groupId
        is FailoverState.Verifying -> s.groupId
        is FailoverState.Healthy -> s.groupId
        is FailoverState.FailingOver -> s.groupId
        is FailoverState.Exhausted -> s.groupId
    }

    /**
     * 裁定65（指摘8）: [FailoverStateHolder] へ publish する、現在対象のグループ。
     * このサービスが実際に [controller] へ供給関数経由で渡している [groups]
     * （裁定72-fix(F4): onCreate 時点で固定ではなく、
     * [reloadGroupsAndProbeTarget] が読み直すたびに更新される）から引く。
     * `dispatch` 内で `controller.handle` の直後・`publish` の直前に呼ばれ、
     * `groups` の再代入はメインスレッドの `prefsListener` からしか起きないので、
     * この呼び出しの間に差し替わることはない。UI 側が `GroupStore` から独自に
     * 読み直した一覧と世代がずれることはあっても、それはこちらではなく UI 側の
     * 問題なので、参照元として信頼できるのはこちらである。
     */
    private fun currentGroup(): FailoverGroup? =
        currentGroupId()?.let { id -> groups.firstOrNull { it.id == id } }

    private fun startForegroundCompat(text: String) {
        val notification = FailoverNotifications.foreground(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FailoverNotifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FailoverNotifications.FOREGROUND_ID, notification)
        }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        bridge.unregister()
        runCatching { unregisterReceiver(screenReceiver) }
        loop?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        // 裁定58: サービスが止まったら投影も Idle に戻す。そうしないと、
        // 停止済みサービスの代わりに UI が存在しない接続を表示し続けてしまう。
        FailoverStateHolder.reset()
        // wakeLoop は明示的に close しない。CONFLATED チャネルは close を必要とせず
        // サービスと一緒に回収される。一方 close すると「閉じたチャネルへ receive が
        // 再入すると ClosedReceiveChannelException が SupervisorJob 配下の launch から
        // 未処理で飛ぶ」という経路を作る。現在の単一 main スレッド構成では到達不能だが、
        // 到達不能性が coroutines 内部のキャンセル伝達順序という契約外の性質に
        // 依存しているため、危険そのものを持たない形にしておく。
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT_GROUP = "net.openconnect_vpn.android.failover.CONNECT_GROUP"
        const val ACTION_DISCONNECT = "net.openconnect_vpn.android.failover.DISCONNECT"
        const val EXTRA_GROUP_ID = "net.openconnect_vpn.android.failover.GROUP_ID"

        /**
         * 裁定86（L2）: Task 13 の検証ハーネス（`FailoverDebugReceiver`・
         * `ACTION_SET_PROBE_TARGET`・`logHarnessState`・`setProbeTarget`）は
         * 削除した。ハーネス自身の KDoc が「TV UI が `connectGroup` /
         * `disconnect` を呼べるようになったら削除する」と約束しており、その条件が
         * このブランチで満たされたため。
         *
         * このタグは残す。ハーネスと同時に導入したが、ハーネス専用ではなく
         * 画面の消灯・点灯、プロセス kill からの復帰、wake lock の取得・解放
         * （裁定43・裁定44 で実機の挙動を追うのに使った）を記録しているため。
         * ハーネスを指す名前のままでは誤読を招くので、名前と値を実体に合わせた。
         */
        private const val LOG_TAG = "FailoverService"

        private const val TICK_INTERVAL_MS = 5_000L

        /** Ruling 17: 画面消灯中のティック間隔。省電力のため通常の6倍に延ばす。 */
        private const val TICK_INTERVAL_STANDBY_MS = 30_000L

        /** Ruling 25: 切替中（FailingOver）の tick 間隔。 */
        private const val TICK_INTERVAL_SWITCHING_MS = 1_000L

        /** 計画2 の TV UI から呼ぶ入口。 */
        fun connectGroup(context: Context, groupId: String) {
            val intent = Intent(context, FailoverService::class.java).apply {
                action = ACTION_CONNECT_GROUP
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            startCompat(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, FailoverService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
