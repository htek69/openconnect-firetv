package net.openconnect_vpn.android.tv

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** TV は暗い部屋で遠くから見るため、暗色固定にする。 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
