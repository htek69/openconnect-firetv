package net.openconnect_vpn.android.failover

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
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
            Log.w(TAG, "startService(OpenVpnService) failed for uuid=$uuid", e)
            ConnectResult.Failed
        }
    }

    override fun disconnect() {
        // 裁定39（M4）: stopService は BIND_AUTO_CREATE の bind がある間サービスを
        // 破棄しない。既存 UI（MainActivity / StatusFragment / LogFragment /
        // VPNProfileList）は VPNConnector 経由で bind するため、legacy UI が
        // 前面にある間（＝ VPN 許可の付与や認証ダイアログへの応答が必要な、
        // 状態機械が最も忙しい場面と重なる）は stopService が完全な no-op になり、
        // ユーザーの「切断」が切断しない事故が起きていた。
        // 明示的な停止アクションを startService で送る（bind の有無に依存しない）。
        val intent = Intent(context, OpenVpnService::class.java).apply {
            action = OpenVpnService.ACTION_STOP_VPN
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startService(ACTION_STOP_VPN) failed", e)
        }
    }

    private companion object {
        const val TAG = "OpenConnectVpnController"
    }
}
