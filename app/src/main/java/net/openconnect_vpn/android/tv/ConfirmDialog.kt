package net.openconnect_vpn.android.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 削除などの取り消せない操作の前に1枚挟む。
 * 初期フォーカスは必ず「キャンセル」側に置く（誤爆防止）。
 *
 * 裁定83（実機で確定。裁定81 は効かなかった）: 以前は
 * `androidx.compose.ui.window.Dialog` を使って別 Window に描いていた。しかし実機の
 * 同一スナップショットで「ダイアログの Window が入力フォーカスを持つため
 * `TvMainActivity.dispatchKeyEvent` はそちらの ACTION_UP を一切見られない」ことと、
 * 「それでも長押しの孤児 UP はフォーカス中のボタンを確実に押してしまう」ことの
 * 両方が確認された（旧 `DialogOrphanUpFilter` による `onPreviewKeyEvent` 対策は
 * 実機で一度も発火しなかった）。原因を特定できない仕組みに賭けるのをやめ、
 * 別 Window をやめて **Activity と同じ Window の中に描く画面内オーバーレイ**に
 * 変更した。同じ Window に入ることで、このダイアログは実機で効くことが既に
 * 確認済みの [LongPressKeyUpFilter]（`TvMainActivity` 側）の守備範囲に自然に入る
 * （このコンポーザブル専用の孤児 UP 判定はもう要らない）。
 *
 * `Dialog` が自動でやっていた「BACK で取り消し」は [BackHandler] で明示的に
 * 肩代わりする。`TvMainActivity` の `BackHandler`（裁定61: サブ画面 → Home）より
 * 後でコンポーズされるため、`OnBackPressedDispatcher` のスタック上ではこちらが
 * 上に乗り、ダイアログ表示中の BACK はこちらが先に消費する。
 *
 * 呼び出し側（`HomeScreen` 等）のシグネチャは変えていない。背後の画面の要素へ
 * フォーカスが漏れない仕組みは呼び出し側（`HomeScreen` の背後の `Column` に
 * 掛けた `Modifier.focusProperties { canFocus = ... }`）で確保している。
 */
@Composable
fun ConfirmDialog(
    message: String,
    confirmLabel: String = "削除する",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 暗幕。TV なのでポインタは無く、タップ不可でよい（クリック処理は
            // 付けない）。
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp)
                .fillMaxWidth(0.6f),
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

    // 裁定62: このコンポーザブルのノードツリーがまだアタッチされていない
    // タイミングでこの効果が走りうる。他の呼び出し箇所と同じく保護する。
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}
