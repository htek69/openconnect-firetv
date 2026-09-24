package net.openconnect_vpn.android.tv

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 初回の VPN 許可を取得する。
 *
 * 許可が済んでいれば VpnService.prepare が null を返すようになり、
 * 以降フェイルオーバー層が Activity を経由せず無人で接続できる（仕様書 5.2）。
 *
 * 裁定86（L4）: 戻り値を `Unit` から `Boolean` に変えた。以前は許可済みのときに
 * `?.let` が空振りするだけで、呼び出し側は「何も起きなかった」ことを知る手段が
 * 無かった。設定画面の「VPN の許可を取得する」を押した利用者には**完全な無反応**
 * に見え、壊れていると解釈される（許可済みならそれが正常なのに、それを伝える
 * 手段が無かった）。
 *
 * @return 許可画面を実際に出したら true。既に許可済みで何もしなかったら false
 *         （呼び出し側はその旨を画面に出すこと）。
 */
@Composable
fun rememberVpnConsentLauncher(): () -> Boolean {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* 結果は次回の prepare() が null を返すかで判断する */ }

    return {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            launcher.launch(intent)
            true
        } else {
            false
        }
    }
}
