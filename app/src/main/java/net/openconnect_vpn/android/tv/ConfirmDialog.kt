package net.openconnect_vpn.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 削除などの取り消せない操作の前に1枚挟む。
 * 初期フォーカスは必ず「キャンセル」側に置く（誤爆防止）。
 */
@Composable
fun ConfirmDialog(
    message: String,
    confirmLabel: String = "削除する",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                ) {
                    Text("キャンセル")
                }
                Button(onClick = onConfirm) {
                    Text(confirmLabel)
                }
            }
        }
    }

    // 裁定62: Dialog のコンテンツは別ウィンドウに組まれるため、このウィンドウの
    // ノードツリーがまだアタッチされていないタイミングでこの効果が走りうる。
    // 他の呼び出し箇所（HomeScreen の各 FocusRequester）と同じく保護する。
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}
