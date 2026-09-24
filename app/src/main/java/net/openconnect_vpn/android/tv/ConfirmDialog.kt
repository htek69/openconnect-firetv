package net.openconnect_vpn.android.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 削除などの取り消せない操作の前に1枚挟む。
 * 初期フォーカスは必ず「キャンセル」側に置く（誤爆防止）。
 *
 * 裁定81（実機で発見）: 呼び出し元（`HomeScreen` の接続先の長押し削除）で
 * `onLongClick` が発火するのは ACTION_DOWN の最中であり、そこでこのダイアログを
 * 呼ぶと、指を離した ACTION_UP は既に開いているこのダイアログの Window に届く。
 * `androidx.compose.ui.window.Dialog` は独立した別の Window を作るため、
 * `TvMainActivity.dispatchKeyEvent` の [LongPressKeyUpFilter] はこの Window の
 * イベントを一切見られず、素通しされた UP が初期フォーカスの「キャンセル」を
 * 押して即座にダイアログを閉じていた。この Window は長押しの ACTION_DOWN を
 * そもそも見ていないため、この Window の中で「対応する DOWN を見ていない UP」は
 * 確実に他所から来た孤児であり、[DialogOrphanUpFilter] で安全に捨てられる
 * （詳細な守備範囲の違いは [DialogOrphanUpFilter] の KDoc 参照）。呼び出し側
 * （`HomeScreen` 等）を変える必要はなく、このコンポーザブルの内部だけで完結する。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConfirmDialog(
    message: String,
    confirmLabel: String = "削除する",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    val orphanUpFilter = remember {
        DialogOrphanUpFilter(
            trackedKeyCodes = setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
            ),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp)
                .fillMaxWidth()
                // 裁定81: この Window の中だけで完結する孤児 UP 判定。対象キー
                // コード以外（BACK 等）は DialogOrphanUpFilter.shouldConsumeUp が
                // 常に false を返すため必ず素通しし、裁定61 の「サブ画面で BACK は
                // Home へ」やダイアログの BACK による取り消しを壊さない。
                .onPreviewKeyEvent { event ->
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            orphanUpFilter.onKeyDown(event.nativeKeyEvent.keyCode)
                            false
                        }

                        KeyEventType.KeyUp ->
                            orphanUpFilter.shouldConsumeUp(event.nativeKeyEvent.keyCode)

                        else -> false
                    }
                },
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
