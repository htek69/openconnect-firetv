package net.openconnect_vpn.android.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * TV 向けの1行テキスト入力。ラベルと現在値を表示する「ボタンのように振る舞う行」と、
 * 実際に文字を打つ `BasicTextField` を、編集中かどうかで出し分ける。
 *
 * 裁定70（実機で発見した阻害欠陥。裁定68/69 のキー横取りでは直らなかった）:
 * `dumpsys` で確認したところ、Fire TV のソフトキーボードは別ウィンドウ
 * （`com.amazon.tv.ime/.FireTVIME`）として開き、アプリの Window の上に乗って
 * D-pad を独占する。フォーカスが乗った `BasicTextField` はソフトキーボードを
 * 要求するため、**画面を開いた瞬間に**（利用者が何も押さなくても）その
 * IME ウィンドウが上がり、以後の D-pad はアプリの Window に一切届かなくなる。
 * `Modifier.onPreviewKeyEvent` によるアプリ内でのキー横取り（裁定68）は、
 * そもそもイベントがアプリの Window に来ない以上、原理的に効かなかった。
 *
 * 対策は「テキスト欄そのものに初期フォーカスを持たせないこと」。既定では
 * ラベルと現在値を表示するだけの、フォーカス可能な `Card`（＝ボタンのように
 * 振る舞う行）を描画する。これなら選ばれてもソフトキーボードは要求されない。
 * D-pad の CENTER でこの行を選んだとき（[isEditing] を true にする）だけ内部の
 * `BasicTextField` へフォーカスを移し、そこで初めてソフトキーボードが開く。
 *
 * 編集を終える経路は2つ:
 * - ソフトキーボードの「完了」（`ImeAction.Done` / `KeyboardActions.onDone`）
 * - 物理 BACK（[BackHandler]。編集中だけ有効にし、より外側にある画面遷移用の
 *   BackHandler（`TvMainActivity` の Home への遷移）より優先して消費させる）
 *
 * どちらも [LocalFocusManager.clearFocus] と [LocalSoftwareKeyboardController.hide] で
 * テキスト欄からフォーカスを外し（＝ソフトキーボードを閉じる）、[isEditing] を
 * false に戻して行の表示に戻す。行が再び現れたら [LaunchedEffect] で行自身へ
 * フォーカスを戻すので、以後は IME ウィンドウが無くなり、再び D-pad の UP/DOWN で
 * この画面内を移動できる。
 *
 * Task 6 の `ProfileEditScreen` と、同種の自由入力欄が要る Task 7 の
 * `GroupEditScreen` の両方から使う想定で、特定の画面専用にはしていない。
 *
 * @param requestInitialFocus 画面を開いた直後にこの行へ初期フォーカスを置くかどうか。
 *   画面全体でちょうど1項目だけ true にすること
 *   （TV では「フォーカスがどこにも無い」状態を作ってはいけない一方、
 *   複数の行が同時に初期フォーカスを取り合うと結果が不定になる）。
 * @param placeholder [value] が空のときに行に表示する文言。
 */
@Composable
fun TvFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    placeholder: String = "未設定",
    requestInitialFocus: Boolean = false,
) {
    var isEditing by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // isEditing の変化に応じたフォーカス移動を担う LaunchedEffect(isEditing) は
    // 初回コンポジション時にも一度呼ばれてしまう。そこは下の
    // LaunchedEffect(Unit)（requestInitialFocus 経由）が別途担当するので、
    // ここでは何もしない。そうしないと requestInitialFocus=false の行まで
    // 初期フォーカスを要求し、どの行が実際にフォーカスを得るか不定になる。
    var hasHandledFirstComposition by remember { mutableStateOf(false) }

    fun exitEditing() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        isEditing = false
    }

    LaunchedEffect(Unit) {
        if (requestInitialFocus) {
            runCatching { rowFocus.requestFocus() }
        }
    }

    LaunchedEffect(isEditing) {
        if (!hasHandledFirstComposition) {
            hasHandledFirstComposition = true
            return@LaunchedEffect
        }
        if (isEditing) {
            runCatching { fieldFocus.requestFocus() }
        } else {
            runCatching { rowFocus.requestFocus() }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)

        if (isEditing) {
            val colors = MaterialTheme.colorScheme
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 2.dp, color = colors.primary, shape = RoundedCornerShape(4.dp))
                    .background(colors.surfaceVariant, shape = RoundedCornerShape(4.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { exitEditing() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus),
                )
            }
            // 編集中だけ有効。TvMainActivity 側の「Home へ戻る」BackHandler より
            // 内側（後）で登録されるため、編集中の BACK はまずこちらが消費し、
            // 画面ごと離脱するのではなく行の表示に戻るだけになる。
            BackHandler(enabled = true) { exitEditing() }
        } else {
            Card(
                onClick = { isEditing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(rowFocus),
            ) {
                Text(
                    value.ifBlank { placeholder },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
