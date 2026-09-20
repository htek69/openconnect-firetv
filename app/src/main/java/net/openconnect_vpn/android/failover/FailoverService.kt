package net.openconnect_vpn.android.failover

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
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

    /** Ruling 27b: 外部イベントでループを早起こしするための合図。 */
    private val wakeLoop = Channel<Unit>(Channel.CONFLATED)

    private lateinit var controller: FailoverController
    private lateinit var probe: HealthProbe
    private lateinit var groupStore: GroupStore
    private lateinit var bridge: VpnStatusBridge
    private lateinit var networkGate: NetworkGate
    private var probeTarget: ProbeTarget = ProbeTarget()

    /**
     * onCreate で読み込んだグループ一覧。Ruling 5 のプローブタイムアウト解決のために、
     * 現在の状態が指す groupId からここを引いて config を取り出す。
     * FailoverController には新しい公開APIを足さない。
     */
    private var groups: List<FailoverGroup> = emptyList()

    /** Minor fix: 直前に実際に反映した通知文言。同じ文言では再投稿しない。 */
    private var lastForegroundText: String? = null

    /** Ruling 17: 画面が点いているか。消灯中はティック間隔を延ばす。 */
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
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (controller.state is FailoverState.Healthy) {
                        probeOnScreenWake = true
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        FailoverNotifications.ensureChannel(this)
        startForegroundCompat("待機中")
        lastForegroundText = "待機中"

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        groupStore = GroupStore(PrefsKeyValueStore(prefs))
        probe = TcpHealthProbe()
        networkGate = ConnectivityNetworkGate(this)
        probeTarget = groupStore.loadProbeTarget()

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
            groups = groups,
            clock = SystemClock(),
            vpn = OpenConnectVpnController(this),
            network = networkGate,
        )

        bridge = VpnStatusBridge(this) { event -> dispatchExternal(event) }
        bridge.register()
        registerScreenReceiver()

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

            // Task 13 検証ハーネス用。FailoverDebugReceiver 削除時にこの分岐も削除すること。
            ACTION_SET_PROBE_TARGET -> {
                val host = intent.getStringExtra(EXTRA_PROBE_HOST)
                val port = intent.getIntExtra(EXTRA_PROBE_PORT, -1)
                if (host != null && port in 1..65535) {
                    val target = ProbeTarget(host = host, port = port)
                    groupStore.saveProbeTarget(target)
                    probeTarget = target
                    Log.d(HARNESS_TAG, "probeTarget set to $target")
                } else {
                    Log.w(HARNESS_TAG, "ignored invalid SET_PROBE_TARGET host=$host port=$port")
                }
                logHarnessState()
            }
        }
        return START_STICKY
    }

    /**
     * Task 13 検証ハーネス用。コントローラの状態とプローブ宛先を distinctive tag で
     * ログに出す。FailoverDebugReceiver 削除時にこのメソッドと呼び出し箇所も削除すること。
     */
    private fun logHarnessState() {
        Log.d(
            HARNESS_TAG,
            "state=${controller.state} excludedUuids=${controller.excludedUuids} " +
                "needsUserConsent=${controller.needsUserConsent} probeTarget=$probeTarget",
        )
    }

    /**
     * Ruling 18: プロセス kill からの復帰。記録されたグループ ID があり、かつ
     * VPN 許可がまだ有効なら自動的に再接続を試みる。許可が失われていた場合は
     * 黙って失敗させず、既存の「許可が必要」通知を出す。ID が記録されていなければ
     * 何もしない（Idle から再起動したサービスは Idle のまま）。
     */
    private fun restoreActiveGroupIfAny() {
        val activeGroupId = groupStore.loadActiveGroupId() ?: return
        if (VpnService.prepare(this) == null) {
            dispatch(FailoverEvent.UserConnectGroup(activeGroupId))
        } else {
            FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
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
        updateForegroundText()
        if (controller.needsUserConsent) {
            FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
        }
        // Task 13 検証ハーネス用。FailoverDebugReceiver 削除時にこの行も削除すること。
        logHarnessState()
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
            is FailoverState.Exhausted -> "全候補が応答しません。再試行を待機中"
        }
        // Minor fix: 文言が変わらない限り通知を再構築・再投稿しない
        // （5秒ごとの Tick だけで無駄な repost を繰り返さないため）。
        if (text != lastForegroundText) {
            lastForegroundText = text
            startForegroundCompat(text)
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
        bridge.unregister()
        runCatching { unregisterReceiver(screenReceiver) }
        loop?.cancel()
        scope.cancel()
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

        // Task 13 検証ハーネス用の一時的なアクション。FailoverDebugReceiver とセットで
        // ブランチ完了前に削除すること。
        const val ACTION_SET_PROBE_TARGET = "net.openconnect_vpn.android.failover.SET_PROBE_TARGET"
        const val EXTRA_PROBE_HOST = "net.openconnect_vpn.android.failover.PROBE_HOST"
        const val EXTRA_PROBE_PORT = "net.openconnect_vpn.android.failover.PROBE_PORT"
        private const val HARNESS_TAG = "FailoverTask13Harness"

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

        /**
         * Task 13 検証ハーネス用。FailoverDebugReceiver からのみ呼ばれる想定。
         * FailoverDebugReceiver 削除時にこのメソッドとその呼び出し元も削除すること。
         */
        fun setProbeTarget(context: Context, host: String, port: Int) {
            val intent = Intent(context, FailoverService::class.java).apply {
                action = ACTION_SET_PROBE_TARGET
                putExtra(EXTRA_PROBE_HOST, host)
                putExtra(EXTRA_PROBE_PORT, port)
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
