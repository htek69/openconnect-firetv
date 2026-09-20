package net.openconnect_vpn.android.failover

import android.content.Context
import android.content.Intent
import android.net.VpnService
import net.openconnect_vpn.android.core.OpenVpnService
import net.openconnect_vpn.android.core.ProfileManager

/**
 * 既存の OpenConnect コアへのアダプタ。
 *
 * 既存 UI は GrantPermissionsActivity を起動して接続するが、フェイルオーバー層は
 * この経路を使えない。Android 10 以降のバックグラウンド Activity 起動制限により
 * 確実に表示される保証がないためである（仕様書 5.2）。
 * ここでは GrantPermissionsActivity.onActivityResult が行う処理を直接実行する。
 *
 * 呼び出し元の FailoverService 自身がフォアグラウンドサービスなので、
 * ここからの startService はバックグラウンド起動制限に抵触しない。
 */
class OpenConnectVpnController(private val context: Context) : VpnController {

    override fun connect(uuid: String): ConnectResult {
        // 未許可なら自動接続はできない。UI 側で許可を取ってもらう。
        if (VpnService.prepare(context) != null) return ConnectResult.NeedsUserConsent

        if (ProfileManager.get(uuid) == null) return ConnectResult.Failed

        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(OpenVpnService.EXTRA_UUID, uuid)
        }
        return try {
            context.startService(intent)
            ConnectResult.Started
        } catch (e: Exception) {
            ConnectResult.Failed
        }
    }

    override fun disconnect() {
        // OpenVpnService は START_SERVICE アクションで bind されている前提ではなく、
        // 停止要求として stopService を使う。サービス側の onDestroy が stopVPN を呼ぶ。
        context.stopService(Intent(context, OpenVpnService::class.java))
    }
}
