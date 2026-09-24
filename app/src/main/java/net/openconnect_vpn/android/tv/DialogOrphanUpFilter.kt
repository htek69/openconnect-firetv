package net.openconnect_vpn.android.tv

/**
 * ダイアログ専用の Window の中だけで完結する「孤児の ACTION_UP」判定（裁定81）。
 *
 * **[LongPressKeyUpFilter] との守備範囲の違い**（両方の KDoc に明記する）:
 * - [LongPressKeyUpFilter] は `TvMainActivity` という単一の Window の生涯全体を見る。
 *   このアプリは単一 Activity で画面遷移が Compose のコンポーザブルの入れ替えに
 *   すぎないため、長押しの DOWN と UP は常に同じ Window（同じ
 *   `LongPressKeyUpFilter` インスタンス）に届く。そこでは「孤児かどうか」
 *   （対応する DOWN をこの Window が見ていない UP かどうか）では長押し後の
 *   誤爆を検出できず、「既に長押しとして消費されたか」という別の基準に
 *   差し替えた（[LongPressKeyUpFilter] の KDoc 参照）。
 * - こちら [DialogOrphanUpFilter] は `ConfirmDialog` が使う
 *   `androidx.compose.ui.window.Dialog` のような、**新しく作られる別の Window**
 *   の中だけで使う。この種の Window は、長押しの ACTION_DOWN が起きた時点では
 *   まだ存在しておらず、その ACTION_DOWN を一切見ていない。つまりこの Window に
 *   最初に届く ACTION_UP は、対応する ACTION_DOWN をこの Window の中で
 *   一度も観測していない、確実な「よそから来た余り」である。裁定78が
 *   Activity 単位では誤りだったのは、Activity が長押しの DOWN と UP の
 *   両方を見てしまうからであり、**新しい Window の内側限定であれば「孤児の
 *   ACTION_UP を捨てる」という当初の発想（裁定78）はそのまま正しく機能する。**
 *
 * 判定基準（Android の `KeyEvent` に依存しない純粋クラスなので JVM 単体テストで
 * 固定できる）:
 * 1. `KeyDown → KeyUp` の対は消費しない（この Window の中で押して離した決定は効く）。
 * 2. この Window の中で対応する `KeyDown` を一度も見ていない `KeyUp` は消費する
 *    （＝ダイアログが開く直前の長押しの余り UP を、開いた瞬間のダイアログに
 *    対して発火させない）。
 * 3. 2 で消費した後、続く `KeyDown → KeyUp` の対は通常どおり消費しない
 *    （一度捨てた印はその場で落ち、以降の操作に影響を残さない）。
 * 4. [trackedKeyCodes] に含まれないキーコード（`BACK` 等）は常に素通しする。
 *
 * `TvMainActivity.dispatchKeyEvent` からは呼ばない。ダイアログの Window の中で
 * 完結させるため、`ConfirmDialog` のコンテンツから `Modifier.onPreviewKeyEvent`
 * 経由で使う。
 */
class DialogOrphanUpFilter(private val trackedKeyCodes: Set<Int>) {

    /** 対象キーコードのうち、この Window の中で ACTION_DOWN を見ているもの。 */
    private val downSeen = mutableSetOf<Int>()

    /** 対象キーコードの ACTION_DOWN を記録する。対象外のキーコードは無視する。 */
    fun onKeyDown(keyCode: Int) {
        if (keyCode in trackedKeyCodes) {
            downSeen.add(keyCode)
        }
    }

    /**
     * この ACTION_UP を消費すべきかを判定する。
     *
     * 対象外のキーコードは常に false（素通し）。対象キーコードの場合、
     * この Window の中で対応する ACTION_DOWN を見ていれば false（素通し、
     * 印は落とす）、見ていなければ true（孤児として消費）を返す。
     */
    fun shouldConsumeUp(keyCode: Int): Boolean {
        if (keyCode !in trackedKeyCodes) return false
        return !downSeen.remove(keyCode)
    }
}
