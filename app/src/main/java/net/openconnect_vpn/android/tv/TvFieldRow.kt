package net.openconnect_vpn.android.tv

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

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
 * 裁定71-fix(F3): `EditText` に切り替えた直後の版は、実機の dumpsys で
 * `mShowInputRequested=false`（要求そのものが通っていない）と出たことを受けて、
 * `InputMethodManager.isActive(editText)` が true になるまで複数回
 * `showSoftInput` を呼び直し、それでも効かなければ
 * `toggleSoftInput(SHOW_FORCED, 0)` にフォールバックする実装を一度試みた。
 * これはレビューで誤りと判明した: `isActive` は `requestFocus()` が入力接続を
 * 確立した瞬間に true になるだけで、キーボードが実際に描画されたかどうかとは
 * 無関係なため、ループは初回でほぼ必ず成功したことにされてしまい、リトライも
 * フォールバックも実質死んでいた。おまけに `toggleSoftInput` は「切り替え」
 * であり、まれにその分岐が本当に実行された場合、既に表示されている
 * キーボードを閉じてしまう向きにも倒れうる（無害ではなく有害）。
 *
 * 裁定75（実機で発見、裁定71の記述を訂正）: 裁定71-fix(F3) 後の実装は
 * `showSoftInput(editText, SHOW_IMPLICIT)` を1回呼ぶだけにし、「キーボードが
 * 実際に描画されるかは端末（Fire TV の IME 実装）側の問題であり、コード側で
 * 推測しようとしない」と書いていた。**この「端末側の問題」という診断が
 * 誤りだった。** 実機の dumpsys（`IMMS`/IME 双方のダンプ）で追ったところ、
 * `InputMethodService.onShowInputRequested(flags, configChange)` が false を
 * 返して IME 自身が表示を拒否しており、`InputMethod` ウィンドウは
 * `mViewVisibility=0x8`（GONE）・サーフェス無しのまま何も描画していなかった。
 * この拒否には2経路しかなく、この端末（`keyboard=NOKEYS` かつ
 * `hardKeyboardHidden=YES`）では `!onEvaluateInputViewShown()` は成立しない。
 * 成立していたのはもう一方——`(flags & SHOW_EXPLICIT) == 0 && !configChange &&
 * onEvaluateFullscreenMode()`——で、AOSP の言う「利用者が明示的に求めたのでな
 * ければ邪魔なフルスクリーン IME は出さない」という拒否である。`EditText` は
 * `IME_FLAG_NO_FULLSCREEN` を立てないので `onEvaluateFullscreenMode()` は
 * true になり、こちらが渡していた `SHOW_IMPLICIT` フラグ（＝
 * `mShowExplicitlyRequested=false`）と組み合わさって、IME 側に
 * 「暗黙要求＋フルスクリーン」という拒否条件がそのまま揃っていた。
 *
 * 必要だったのは次の2条件を**同時に**満たすことで、直前の版は毎回どちらか
 * 片方しか満たしていなかった:
 *
 * | 版 | `IME_FLAG_NO_FULLSCREEN` | 表示要求 | `onEvaluateFullscreenMode()` | 結果 |
 * |---|---|---|---|---|
 * | `BasicTextField`（裁定70時点） | 立つ（Compose が必ず立てる） | 明示 | false → フローティング表示を要求 | Fire TV にフローティング表示が無く描かれない |
 * | `EditText` ＋ `SHOW_IMPLICIT`（裁定71-fix(F3)まで） | 立たない | 暗黙 | true | 暗黙＋フルスクリーンなので IME が拒否する |
 * | `EditText` ＋ `flags = 0`（裁定75・現在） | 立たない | 明示 | true | フルスクリーンの extract エディタが出る（期待どおり） |
 *
 * 直し方は `showSoftInput` のフラグを `SHOW_IMPLICIT` から `0`
 * （明示要求）に変えるだけの1語の修正である。`SHOW_FORCED` は使わない
 * （裁定71-fix(F3) の判定どおり、利用者が閉じても居座り続ける有害な挙動を招く
 * ため）。`flags = 0` を渡すと `IMMS` 側で `mShowExplicitlyRequested = true`
 * が立ち、`InputMethod.SHOW_EXPLICIT` 付きで IME に届くので、上の拒否条件
 * `(flags & SHOW_EXPLICIT) == 0` が成立しなくなる。
 *
 * **将来ここを `SHOW_IMPLICIT` に戻すと、コンパイルは通ったまま無言で
 * 壊れる**（IME が表示要求を静かに拒否するだけで、例外もログもクラッシュも
 * 出ない）。変更する場合は必ず実機の dumpsys で
 * `mShowExplicitlyRequested` と IME 側の `mWindowVisible` を確認すること。
 *
 * 現在の実装は `EditText` がアタッチされた時点で `showSoftInput(editText, 0)`
 * を1回呼ぶだけで、成否を判定するコードは持たない。裁定71-fix(F3) の結論
 * ——`isActive` 等での成否判定やリトライ・フォールバックは足さない——は
 * そのまま維持する。原因は上記の通り特定済みであり、フラグを正しく渡す
 * こと自体が対策であって、実行時に成否を推測する仕組みは不要だからである
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
 * @param downTarget 裁定77: この行から `DPAD_DOWN` で移動する先を名指しで固定したい
 *   場合に渡す。`null`（既定）なら既定の2次元フォーカス探索に任せる。
 *   この行の実体（フォーカス対象）は呼び出し側が渡す [modifier] が付く外側の
 *   `Column` ではなく**内部の `Card`**（下の非編集時分岐）なので、
 *   `focusProperties { down = ... }` は呼び出し側の `modifier` に頼らず、
 *   この `Card` 自身に直接適用する。呼び出し側の `Modifier.focusProperties`
 *   が外側の `Column` に付いていても、`down` は「現在フォーカスを持つノード」
 *   から見て最も近い `FocusProperties` 修飾子が優先されるため、`Column` に
 *   付けても内部の `Card`（実際にフォーカスされるノード）には届かない
 *   （＝ `enter` が2次元探索の経路で参照されないのと同種の、実体とフォーカス
 *   対象のずれの問題）。`Card` に直接付けることで、この行が実際に
 *   フォーカスされた状態から `DOWN` を押したときに必ず参照される。
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
    downTarget: FocusRequester? = null,
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
            // 裁定71-fix(F3): 以前はここで `InputMethodManager.isActive(editText)`
            // を成功判定にして複数回呼び直し、失敗時は `toggleSoftInput` に
            // フォールバックしていたが、`isActive` は「このビューが現在の入力先か」
            // を返すだけでキーボードの描画とは無関係であり、`requestFocus()` の
            // 直後にほぼ常に true になる。結果としてループは実質1回で終わり、
            // フォールバックは動かない死んだコードだった。しかもフォールバック
            // 自体が `toggleSoftInput`（トグル）であるため、狙って動かせたと
            // しても、その瞬間キーボードが既に出ていれば逆に閉じてしまう。
            // 判定を試みるのをやめ、アタッチ後に `showSoftInput` を1回呼ぶだけに
            // した。
            //
            // 裁定75（実機の dumpsys で特定、裁定71-fix(F3) 時点の記述を訂正）:
            // 上の「1回呼ぶだけ」の実装は当初 `SHOW_IMPLICIT` フラグを渡していたが、
            // それ自体が拒否の原因だった。`EditText` は `IME_FLAG_NO_FULLSCREEN` を
            // 立てないため `InputMethodService.onEvaluateFullscreenMode()` は true
            // になり、AOSP の `onShowInputRequested` は「暗黙要求 かつ
            // フルスクリーン」の組み合わせを利用者の邪魔になるとみなして表示を
            // 拒否する（`IME` 側 `mViewVisibility=0x8` でサーフェス無し、
            // `mInputShown=true` なのに何も描かれない状態が実機で確認された）。
            // `flags = 0`（明示要求。`SHOW_EXPLICIT` 相当）に変えると
            // `IMMS` が `mShowExplicitlyRequested = true` を立て、拒否条件が
            // 成立しなくなる。`SHOW_FORCED` は使わない（居座り続ける有害な
            // 挙動のため、裁定71-fix(F3) の判定どおり）。
            // 成否を実行時に判定してリトライ・フォールバックする、という
            // 裁定71-fix(F3) の結論（＝しない）はそのまま維持している。原因は
            // 上記の通り特定済みで、正しいフラグを渡すこと自体が対策だからである。
            // **ここを `SHOW_IMPLICIT` に戻すと、コンパイルは通ったまま実機でのみ
            // 無言で壊れる。** 変更する場合は必ず実機の dumpsys で
            // `mShowExplicitlyRequested` を確認すること。
            LaunchedEffect(Unit) {
                val editText = editTextRef.value ?: return@LaunchedEffect
                editText.requestFocus()
                val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager ?: return@LaunchedEffect
                imm.showSoftInput(editText, 0) // 明示要求。SHOW_IMPLICIT では IME に拒否される（裁定75）
            }

            // 編集中だけ有効。TvMainActivity 側の「Home へ戻る」BackHandler より
            // 内側（後）で登録されるため、編集中の BACK はまずこちらが消費し、
            // 画面ごと離脱するのではなく行の表示に戻るだけになる。
            BackHandler(enabled = true) { exitEditing() }
        } else {
            Card(
                onClick = { isEditing = true },
                // 裁定77: downTarget が指定されていれば、この Card（＝この行の
                // 実際のフォーカス対象）自身に focusProperties { down = ... } を
                // 適用する。上の @param downTarget のコメント参照。
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(rowFocus)
                    .then(
                        if (downTarget != null) {
                            Modifier.focusProperties { down = downTarget }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    value.ifBlank { placeholder },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
