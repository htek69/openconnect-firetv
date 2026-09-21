package net.openconnect_vpn.android.failover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import net.openconnect_vpn.android.core.OpenVpnService

/**
 * 既存コアの ACTION_VPN_STATUS ブロードキャストを FailoverEvent に変換して流す。
 *
 * Ruling 13: ブロードキャストには EXTRA_UUID が必ず載っている（OpenVpnService 側で
 * mUUID を putExtra 済み）。これを読み取って VpnStateChanged に渡さないと、切替前の
 * 古い候補の切断通知が新しい候補の障害と誤認され、一度も試していない候補が安全策 S1
 * により誤って除外される。FailoverController 側は uuid が現在の候補と一致しない
 * イベントを無視することでこれを防いでいるが、それはこの橋渡しが uuid を正しく
 * 渡すことが前提になっている。
 */
class VpnStatusBridge(
    private val context: Context,
    private val onEvent: (FailoverEvent) -> Unit,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(OpenVpnService.EXTRA_CONNECTION_STATE, -1) ?: -1
            val uuid = intent?.getStringExtra(OpenVpnService.EXTRA_UUID)
            VpnCoreState.fromCoreInt(raw)?.let { onEvent(FailoverEvent.VpnStateChanged(it, uuid)) }
        }
    }

    fun register() {
        val filter = IntentFilter(OpenVpnService.ACTION_VPN_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 裁定40（M7・多層防御）: 本質的な守りは送信側の signature permission
            // （OpenVpnService.PERMISSION_VPN_STATUS_SUFFIX）だが、API 33+ では
            // 追加でこちらも RECEIVER_NOT_EXPORTED にする。正当な送信者は同一アプリの
            // OpenVpnService だけであり、同一アプリからのブロードキャストは
            // NOT_EXPORTED の受信機にも届くのでこれで壊れない。
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
