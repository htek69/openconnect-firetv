package net.openconnect_vpn.android.tv

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * 裁定86（M5）: 対象の [FocusRequester] へフォーカスを移す。成功するか
 * [maxAttempts] フレーム試みて諦めるまで繰り返す。
 *
 * 元は `HomeScreen.kt` の private 関数（裁定84 fix8 / 裁定62）だった。
 * `ConfirmDialog` と `ConsentRequiredScreen` は1回きりの
 * `runCatching { requestFocus() }` のままだったので、共有ヘルパへ格上げして
 * 3か所を同じ扱いにする。
 *
 * なぜリトライが要るか: `requestFocus()` は対象のノードがまだ Compose の
 * ツリーにアタッチされていないと例外になる（だから `runCatching` で包む）。
 * 呼び出し箇所はいずれも「いま作られたばかりの要素へフォーカスを置く」場面で
 * あり、同じフレームではまだアタッチされていないことがある。1回きりの要求が
 * そこで失敗すると、**その画面のどこにもフォーカスが無い状態**が残る。
 *
 * 裁定83 以降、`ConfirmDialog` 表示中は背後の画面が
 * `focusProperties { canFocus = false }` になり、フォーカス可能なのは
 * ダイアログ内の2ボタンだけである。したがってダイアログの初期フォーカス要求が
 * 1回失敗すると Window 内にフォーカス対象が1つも無くなり、D-pad が完全に
 * 死ぬ（脱出できるのは BACK だけ）。仕様書 §9.4 が最も警戒している状態その
 * ものなので、ここだけ1回きりにしておく理由が無い。
 *
 * `withFrameNanos` で次のフレームまで待つ（`delay` のような実時間ではなく
 * コンポジションの進行に合わせる）。最後の試行のあとは待たない。
 */
internal suspend fun FocusRequester.requestFocusRetrying(maxAttempts: Int = 5) {
    repeat(maxAttempts) { attempt ->
        if (runCatching { requestFocus() }.isSuccess) return
        if (attempt < maxAttempts - 1) withFrameNanos { }
    }
}
