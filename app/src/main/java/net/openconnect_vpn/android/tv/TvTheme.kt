package net.openconnect_vpn.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.lightColorScheme

/**
 * TV UI の配色。
 *
 * 裁定90（利用者の指摘で発見）: 以前は `darkColorScheme()` を渡すだけで、
 * コンテンツを [Surface] にも `LocalContentColor` にも載せていなかった。
 * tv-material の `Text` は色を明示しないと `LocalContentColor` を使い、
 * その既定値は **[Color.Black]** である。`Card` は自前で内容色を与えるので
 * カードの中の文字だけは明るくなり、**カードの外（画面の見出し・ラベル・
 * 注意書き）が暗い地に黒文字で描かれて読めない**状態になっていた。
 * 実機のスクリーンショットでも「グループを編集」「設定」の見出しが
 * ほぼ黒く潰れている。
 *
 * 直し方は2通りあった:
 *
 * 1. 暗い配色のまま `LocalContentColor` を `onSurface`（明るい色）にする
 * 2. 明るい配色にして、黒い既定色が読める地の上に来るようにする
 *
 * 利用者の希望により **2** を採った（「バックグラウンドを白っぽい色に」）。
 * ただし黒い既定色に依存したままにはせず、`LocalContentColor` も明示して
 * いるので、どちらの配色でも文字色は配色から決まる。**将来ここを
 * `darkColorScheme()` に戻しても、黒文字のまま取り残されることはない。**
 *
 * 純白ではなく僅かに灰色を含む白地にしてある。TV は画面が大きく視聴距離が
 * あるため、純白一面は暗い部屋で眩しく、`Card` の面（`surfaceVariant`）との
 * 差も出せなくなるためである。
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        background = BackgroundLight,
        onBackground = TextPrimary,
        surface = BackgroundLight,
        onSurface = TextPrimary,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = TextPrimary,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.background),
            ) {
                content()
            }
        }
    }
}

/** 画面全体の地。純白（#FFFFFF）ではなく僅かに灰を含ませている（KDoc 参照）。 */
private val BackgroundLight = Color(0xFFF2F2F5)

/** `Card` など一段持ち上げた面。地より僅かに濃くして境界が見えるようにする。 */
private val SurfaceVariantLight = Color(0xFFE2E2E8)

/** 本文・見出しの色。純黒ではなく僅かに青みのある濃灰で、白地でのちらつきを抑える。 */
private val TextPrimary = Color(0xFF1B1B1F)
