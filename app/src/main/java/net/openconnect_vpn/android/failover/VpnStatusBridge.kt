package net.openconnect_vpn.android.failover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
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
            val core = VpnCoreState.fromCoreInt(raw) ?: return

            // この1行が Ruling 13 / 裁定31a / 裁定34 の共通の前提である。uuid を
            // 落とすと FailoverController の照合ガードが恒真になって3つの裁定が
            // 同時に無効化されるが、単体テストは uuid を明示的に渡すので1件も
            // 落ちない（最終レビュー F10-2）。そこで uuid が無いイベントは
            // 通さずに捨てる。既存コアは必ず EXTRA_UUID を載せる
            // （OpenVpnService.wakeUpActivity）ので、無いということは
            // 前提が壊れたということであり、照合できないイベントを流して
            // 候補を取り違えるより、流さずに接続タイムアウトへ委ねるほうが安全である。
            if (uuid == null) {
                Log.w(TAG, "EXTRA_UUID の無い状態通知を無視した state=$core")
                return
            }
            onEvent(FailoverEvent.VpnStateChanged(core, uuid))
        }
    }

    fun register() {
        val filter = IntentFilter(OpenVpnService.ACTION_VPN_STATUS)
        // 裁定40（M7・セキュリティ）+ 追加の強化: `ACTION_VPN_STATUS` は permission で
        // 保護されていない暗黙のアクションであり、動的登録の受信機は Android 8 以降の
        // 暗黙ブロードキャスト制限の対象外なので、権限を持たない第三者アプリでも
        // 偽の状態遷移を送り込める。ブリーフは送信側（`OpenVpnService.sendBroadcast`）
        // に signature permission を付ける修正を挙げているが、`sendBroadcast(intent,
        // receiverPermission)` の引数は「受信側がその permission を保持していないと
        // 配送されない」という意味であり、受信側の資格を絞るものであって、
        // 送信者の資格を絞るものではない（攻撃者が自分の sendBroadcast を独自に
        // 呼ぶ経路はこれでは防げない）。実際に送信者を絞るのは受信側の
        // `registerReceiver` に渡す broadcastPermission であり、こちらは
        // OS がブロードキャスト配送前に「送信者がこの permission を保持しているか」を
        // 強制するため、API レベルに関わらず（実機の API 25 を含めて）有効である。
        // ブリーフの指示（送信側の permission 差し替え、API 33+ での
        // RECEIVER_NOT_EXPORTED）はそのまま実施したうえで、ここでも同じ
        // signature permission を要求することで、API 25 でも実際に第三者からの
        // 偽装を防ぐ。同一アプリの送信者（OpenVpnService）はこの permission を
        // 自動的に保持するので、正当な通知は届き続ける。既存 UI の受信機
        // （VPNConnector、別の registerReceiver 呼び出し）はここを変更していないので
        // 影響を受けない。
        val permission = context.packageName + OpenVpnService.PERMISSION_VPN_STATUS_SUFFIX
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, permission, null)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val TAG = "VpnStatusBridge"
    }
}
