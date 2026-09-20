package net.openconnect_vpn.android.failover

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.openconnect_vpn.android.core.ProfileManager

/**
 * フェイルオーバー状態機械を駆動するフォアグラウンドサービス。
 *
 * 状態機械そのもの（FailoverController）は Android 非依存で、ここが
 * ブロードキャスト・タイマー・プローブ実行という副作用だけを担う。
 */
class FailoverService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var loop: Job? = null

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

    override fun onCreate() {
        super.onCreate()
        FailoverNotifications.ensureChannel(this)
        startForegroundCompat("待機中")

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

        bridge = VpnStatusBridge(this) { event -> dispatch(event) }
        bridge.register()

        loop = scope.launch {
            var lastNetworkAvailable = networkGate.hasUnderlyingNetwork()
            while (true) {
                val available = networkGate.hasUnderlyingNetwork()
                if (available != lastNetworkAvailable) {
                    lastNetworkAvailable = available
                    dispatch(FailoverEvent.UnderlyingNetworkChanged(available))
                }
                if (controller.shouldProbeNow()) {
                    val reachable = probe.probe(probeTarget, currentProbeTimeoutMs())
                    dispatch(FailoverEvent.ProbeResult(reachable))
                }
                dispatch(FailoverEvent.Tick)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_GROUP -> intent.getStringExtra(EXTRA_GROUP_ID)?.let { groupId ->
                dispatch(FailoverEvent.UserConnectGroup(groupId))
            }

            ACTION_DISCONNECT -> dispatch(FailoverEvent.UserDisconnect)
        }
        return START_STICKY
    }

    private fun dispatch(event: FailoverEvent) {
        controller.handle(event)
        updateForegroundText()
        if (controller.needsUserConsent) {
            FailoverNotifications.alert(this, "VPN の許可が必要です。アプリを開いて許可してください。")
        }
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
        startForegroundCompat(text)
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
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT_GROUP = "net.openconnect_vpn.android.failover.CONNECT_GROUP"
        const val ACTION_DISCONNECT = "net.openconnect_vpn.android.failover.DISCONNECT"
        const val EXTRA_GROUP_ID = "net.openconnect_vpn.android.failover.GROUP_ID"

        private const val TICK_INTERVAL_MS = 5_000L

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
