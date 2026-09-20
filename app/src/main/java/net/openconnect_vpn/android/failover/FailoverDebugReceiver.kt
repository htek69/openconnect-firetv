package net.openconnect_vpn.android.failover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Task 13 検証ハーネス。**一時的なコンポーネントであり、このブランチの完了条件として
 * 削除すること。**
 *
 * [FailoverService] は `android:exported="false"`（意図的かつ正しい設定であり、変更しない）
 * のため adb（shell, uid 2000）から直接 `am startservice` で叩けない。実機（Fire TV Stick 4K,
 * API 25）は `am start-foreground-service` サブコマンド自体が存在しないため、それを前提にした
 * 元計画の検証手順は成立しない。この受信機は adb から到達できる唯一の入口として、
 * [FailoverService] の既存コンパニオンメソッド（[FailoverService.connectGroup] /
 * [FailoverService.disconnect] / [FailoverService.setProbeTarget]）へそのまま転送するだけで、
 * FailoverController には一切触れない。状態機械はこれまで通り FailoverService だけが駆動する。
 *
 * 起動方法（API 25 では `am start-foreground-service` が無いため `am broadcast` を使う。
 * パッケージ名は `applicationId`=`net.openconnect_vpn.android.firetv`）:
 *
 * ```
 * # グループ接続
 * adb shell am broadcast \
 *   -a net.openconnect_vpn.android.failover.debug.CONNECT \
 *   -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.failover.FailoverDebugReceiver \
 *   --es group_id g1
 *
 * # 切断
 * adb shell am broadcast \
 *   -a net.openconnect_vpn.android.failover.debug.DISCONNECT \
 *   -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.failover.FailoverDebugReceiver
 *
 * # プローブ宛先の変更（例: 到達不能アドレスに向けて「トンネルは張れたが疎通は無い」を再現）
 * adb shell am broadcast \
 *   -a net.openconnect_vpn.android.failover.debug.SET_PROBE_TARGET \
 *   -n net.openconnect_vpn.android.firetv/net.openconnect_vpn.android.failover.FailoverDebugReceiver \
 *   --es host 10.255.255.1 --ei port 443
 * ```
 *
 * 状態の確認は `adb logcat -s FailoverTask13Harness:D FailoverDebugReceiver:D` で行う。
 * 各コマンドの受信時と、FailoverService が状態機械をディスパッチするたびに、
 * 現在の state・excludedUuids・probeTarget を distinctive tag でログに出す。
 */
class FailoverDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "received action=${intent?.action} extras=${intent?.extras}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
                if (groupId == null) {
                    Log.w(TAG, "CONNECT missing extra $EXTRA_GROUP_ID")
                    return
                }
                FailoverService.connectGroup(context, groupId)
            }

            ACTION_DISCONNECT -> FailoverService.disconnect(context)

            ACTION_SET_PROBE_TARGET -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (host == null || port !in 1..65535) {
                    Log.w(TAG, "SET_PROBE_TARGET invalid host=$host port=$port")
                    return
                }
                FailoverService.setProbeTarget(context, host, port)
            }

            else -> Log.w(TAG, "unknown action=${intent?.action}")
        }
    }

    companion object {
        private const val TAG = "FailoverDebugReceiver"

        const val ACTION_CONNECT = "net.openconnect_vpn.android.failover.debug.CONNECT"
        const val ACTION_DISCONNECT = "net.openconnect_vpn.android.failover.debug.DISCONNECT"
        const val ACTION_SET_PROBE_TARGET = "net.openconnect_vpn.android.failover.debug.SET_PROBE_TARGET"

        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }
}
