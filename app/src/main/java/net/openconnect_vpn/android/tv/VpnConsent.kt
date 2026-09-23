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
 */
@Composable
fun rememberVpnConsentLauncher(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* 結果は次回の prepare() が null を返すかで判断する */ }

    return {
        VpnService.prepare(context)?.let { launcher.launch(it) }
    }
}
