package net.openconnect_vpn.android.tv

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val TAG = "TvFieldRow"

/**
 * `showSoftInput` の呼び直しを何回まで試すか。実機の dumpsys では単発呼び出しが
 * `mShowInputRequested=false` のまま（＝要求が通っていない）ことが確認された
 * ため、フォーカス確定との競合を見越して間隔を空けて複数回試す。無限リトライは
 * しない（フォーカス移動先が既におかしい等の異常時に走り続けるのを避けるため）。
 * 5回 x 100ms = 最大500ms 待ってから諦め、`toggleSoftInput(SHOW_FORCED, 0)` に
 * フォールバックする。
 */
private const val SHOW_SOFT_INPUT_MAX_ATTEMPTS = 5
private const val SHOW_SOFT_INPUT_RETRY_DELAY_MS = 100L

/**
 * TV 向けの1行テキスト入力。ラベルと現在値を表示する「ボタンのように振る舞う行」と、
 * 実際に文字を打つ入力欄を、編集中かどうかで出し分ける。
 *
 * 裁定70（実機で発見、[TvFieldRow] 導入のきっかけ）: フォーカスが乗った
 * `BasicTextField` はソフトキーボードを要求し、Fire TV ではそのソフトキーボードが
 * 別ウィンドウ（`com.amazon.tv.ime/.FireTVIME`）としてアプリの Window の上に乗って
 * D-pad を独占する。よってテキスト欄そのものに初期フォーカスを持たせてはいけない。
 * 対策として、既定ではラベルと現在値を表示するだけの、フォーカス可能な `Card`
 * （＝ボタンのように振る舞う行）を描画し、D-pad の CENTER でこの行を選んだとき
 * （[isEditing] を true にする）だけ内部の入力欄へフォーカスを移す設計にした。
 * ここまでは実機で確認済み（D-pad のナビゲーション・CENTER での編集開始・BACK での
 * 離脱・保存への到達は正しく動く）。
 *
 * 裁定71（実機で発見、`BasicTextField` を捨てて `EditText` に切り替えた理由）:
 * 上記の行方式に切り替えた後も、実際にはソフトキーボードが**描画されない**ことが
 * `dumpsys` で判明した。原因は Compose の `BasicTextField` が既定で
 * `EditorInfo.imeOptions` に `IME_FLAG_NO_FULLSCREEN` と `IME_FLAG_NO_EXTRACT_UI`
 * を立てること（スマホでは IME がアプリの上に浮くので正しい）。ところが
 * **Fire TV の IME にはフローティング表示が無く、フルスクリーンの extract
 * エディタしか描画手段を持たない。** 両方のフラグで「どちらも使うな」と言われた
 * 結果、`mInputShown=true`（IME 自身は「表示した」と報告する）のに画面には
 * 何も描かれない、という状態になっていた。Compose にはこの2フラグを外す
 * 公開 API が無いため、`BasicTextField` の内側から直す手段が無い。
 *
 * 一方、アプリの旧 UI（ics-openconnect 由来）のパスワード入力は素の `EditText`
 * を使っており、同じ実機で実際にキーボードが出ることが確認されている
 * （`EditText` は既定でこの2フラグを立てない）。そこで入力欄の実体を
 * `AndroidView` 経由で素の `EditText` に置き換えた。`EditText` へは
 * [isEditing] が true になったときだけ `View.requestFocus()` +
 * `InputMethodManager.showSoftInput()` で明示的にフォーカスとキーボード表示を
 * 要求し、編集を終えるとき（IME の「完了」／物理 BACK）は
 * `InputMethodManager.hideSoftInputFromWindow()` + `View.clearFocus()` で
 * 明示的に閉じてから行の表示に戻す。「行が単にフォーカスされただけでは
 * `EditText` へフォーカスを奪わない」という性質（＝画面を開いた瞬間に罠に
 * ならない性質）は、`EditText` に置き換えた後もこの手動フォーカス制御で
 * そのまま保たれている。
 *
 * 裁定71（続き・実機で発見）: `EditText` に切り替えた直後の版は単発の
 * `showSoftInput()` しか呼んでおらず、実機の dumpsys では
 * `mShowInputRequested=false`（要求そのものが通っていない）のままだった。
 * フォーカス確定のタイミングと競合している可能性が高いため、
 * `InputMethodManager.isActive(editText)` が true になるまで
 * [SHOW_SOFT_INPUT_MAX_ATTEMPTS] 回を上限に間隔を空けて呼び直し、それでも
 * 効かなければ `toggleSoftInput(SHOW_FORCED, 0)` に1回だけフォールバックする
 * （詳細は編集中分岐内の `LaunchedEffect` のコメントを参照）。
 *
 * フィールドごとのキーボード種別の使い分け（サーバ URL は ASCII/URI 系、表示名は
 * 既定）は `KeyboardType` ではなく `EditText.inputType`（[inputType] パラメータ）
 * で行う。呼び出し側の指定はそのまま `InputType.TYPE_CLASS_TEXT or
 * InputType.TYPE_TEXT_VARIATION_URI` のような値を渡す。
 *
 * Task 6 の `ProfileEditScreen` と、同種の自由入力欄が要る Task 7 の
 * `GroupEditScreen` の両方から使う想定で、特定の画面専用にはしていない。
 *
 * @param inputType `android.text.InputType` のフラグ。既定は `TYPE_CLASS_TEXT`
 *   （プレーンテキスト、既定の IME）。URL 欄は呼び出し側で
 *   `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI` を渡すこと。
 *   `IME_FLAG_NO_FULLSCREEN` / `IME_FLAG_NO_EXTRACT_UI` に相当する指定は
 *   一切行わない（裁定71の原因そのものなので、ここでは意図的に触らない）。
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
    modifier: Modifier = Modifier,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    placeholder: String = "未設定",
    requestInitialFocus: Boolean = false,
) {
    var isEditing by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val editTextRef = remember { mutableStateOf<EditText?>(null) }

    fun hideKeyboardAndClearFocus() {
        val editText = editTextRef.value ?: return
        val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        imm?.hideSoftInputFromWindow(editText.windowToken, 0)
        editText.clearFocus()
    }

    fun exitEditing() {
        hideKeyboardAndClearFocus()
        isEditing = false
    }

    LaunchedEffect(Unit) {
        if (requestInitialFocus) {
            runCatching { rowFocus.requestFocus() }
        }
    }

    // isEditing が false に戻ったとき（初回コンポジションを除く）に行自身へ
    // フォーカスを戻す。true になったときの EditText 側のフォーカス制御は
    // 下の AndroidView 分岐内の LaunchedEffect(Unit) が個別に行う
    // （Compose の FocusRequester ではなく View.requestFocus() +
    // InputMethodManager を直接使うため、ここでは扱わない。裁定71参照）。
    var hasHandledFirstComposition by remember { mutableStateOf(false) }
    LaunchedEffect(isEditing) {
        if (!hasHandledFirstComposition) {
            hasHandledFirstComposition = true
            return@LaunchedEffect
        }
        if (!isEditing) {
            runCatching { rowFocus.requestFocus() }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)

        if (isEditing) {
            val colors = MaterialTheme.colorScheme
            val textColorArgb = colors.onSurface.toArgb()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 2.dp, color = colors.primary, shape = RoundedCornerShape(4.dp))
                    .background(colors.surfaceVariant, shape = RoundedCornerShape(4.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        EditText(context).apply {
                            setSingleLine(true)
                            this.inputType = inputType
                            // 裁定71: NO_FULLSCREEN / NO_EXTRACT_UI に相当する指定はしない。
                            // EditText は既定でこれらを立てないので、そのままにするのが
                            // Fire TV の IME を描画させる条件そのもの。
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            background = null
                            setPadding(0, 0, 0, 0)
                            setTextColor(textColorArgb)
                            setText(value)
                            setSelection(value.length)
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    exitEditing()
                                    true
                                } else {
                                    false
                                }
                            }
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(
                                    s: CharSequence?,
                                    start: Int,
                                    count: Int,
                                    after: Int,
                                ) = Unit

                                override fun onTextChanged(
                                    s: CharSequence?,
                                    start: Int,
                                    before: Int,
                                    count: Int,
                                ) = Unit

                                override fun afterTextChanged(s: Editable?) {
                                    onValueChange(s?.toString().orEmpty())
                                }
                            })
                            editTextRef.value = this
                        }
                    },
                    update = { editText ->
                        // value が外から変わった（例: 検証エラーでこの欄をクリアする等）
                        // 場合だけ setText する。毎回呼ぶと入力中のカーソル位置が
                        // 打鍵のたびに先頭へ飛ぶ。
                        if (editText.text?.toString() != value) {
                            editText.setText(value)
                            editText.setSelection(value.length)
                        }
                    },
                )
            }

            // isEditing が true になった直後（＝この分岐が新たにコンポジションに
            // 入ったとき）に一度だけ EditText へフォーカスを移し、明示的に
            // ソフトキーボードを要求する。
            //
            // 裁定71（続き・実機で発見）: 単発の `showSoftInput` は「呼んだ」だけで
            // 「効いた」とは限らない。実機の dumpsys では
            // `mShowInputRequested=false` のまま（＝要求そのものが通っていない）
            // ことが確認された。フォーカス確定のタイミングと競合している可能性が
            // 高いため、`InputMethodManager.isActive(editText)` が true になるまで
            // 短い間隔を空けて何度か呼び直す。無限に粘るとフォーカス移動先の
            // View が既に無い等の異常時に延々と走り続けかねないので、
            // 試行回数に上限（[SHOW_SOFT_INPUT_MAX_ATTEMPTS]）を設ける。
            // それでも `isActive` にならなければ最後の手段として
            // `toggleSoftInput(SHOW_FORCED, 0)` を1回だけ呼ぶ。
            //
            // このリトライは `LaunchedEffect` のコルーチンとして実装しているため、
            // 編集終了（`isEditing = false`）でこの分岐がコンポジションから
            // 外れれば構造化された形で自動的にキャンセルされる（すでに無くなった
            // `EditText` に向けて `postDelayed` が後から発火する、といった
            // 心配をする必要が無い）。
            LaunchedEffect(Unit) {
                val editText = editTextRef.value ?: return@LaunchedEffect
                editText.requestFocus()
                val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager ?: return@LaunchedEffect

                var activated = false
                for (attempt in 1..SHOW_SOFT_INPUT_MAX_ATTEMPTS) {
                    imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    delay(SHOW_SOFT_INPUT_RETRY_DELAY_MS)
                    if (imm.isActive(editText)) {
                        activated = true
                        break
                    }
                }
                if (!activated) {
                    Log.d(
                        TAG,
                        "showSoftInput did not activate after $SHOW_SOFT_INPUT_MAX_ATTEMPTS " +
                            "attempts (${SHOW_SOFT_INPUT_MAX_ATTEMPTS * SHOW_SOFT_INPUT_RETRY_DELAY_MS}ms); " +
                            "falling back to toggleSoftInput(SHOW_FORCED)",
                    )
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                }
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
