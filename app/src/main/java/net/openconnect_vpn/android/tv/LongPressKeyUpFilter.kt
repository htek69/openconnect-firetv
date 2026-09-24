package net.openconnect_vpn.android.tv

/**
 * 長押しで消費された ACTION_UP を判定する（裁定80、裁定78 の判定基準の差し替え）。
 *
 * 裁定78 は「対応する ACTION_DOWN をこの Activity が見ていない UP」を孤児と呼び、
 * それを捨てれば長押し遷移後の誤爆が直ると考えたが、実機で直っていないことが
 * 確認された。**このアプリは単一 Activity で、画面遷移は Compose の
 * コンポーザブルが入れ替わるだけである。** 長押しの DOWN も UP も同じ
 * `TvMainActivity` が受け取るため、Activity 単位で見る限り DOWN と UP は常に
 * 正しい対であり、裁定78 の判定基準では検出しようがなかった。変わっているのは
 * Activity ではなくどのコンポーザブルがフォーカスを持っているかなので、
 * Activity 単位の対応関係では原理的に検出できない。
 *
 * 差し替えた判定基準: 孤児かどうかではなく、**「その押下は既に長押しとして
 * 消費されたか」** で判定する。`Modifier.combinedClickable` の `onLongClick` は
 * ACTION_DOWN が一定時間続いた時点（キーがまだ押されている最中）に発火し、
 * 遷移や削除確認ダイアログを呼ぶ。その後に届く ACTION_UP は、遷移後の新しい
 * コンポーザブル（`TvFieldRow` の編集モードや `ConfirmDialog` の既定選択）に
 * 対して素通しされると誤爆する。そこで、長押しハンドラの先頭で必ず
 * [onLongPressConsumed] を呼んでもらい、「いま押されている対象キーは長押しとして
 * 使われた」と印を付ける。続く ACTION_UP はその印を見て、印が付いていれば
 * 消費（捨てる）、付いていなければ素通しする。
 *
 * 呼び出し側の責務は3つ:
 * - [onKeyDown]: ACTION_DOWN のたびに呼ぶ
 * - [onLongPressConsumed]: TV UI の `onLongClick` ハンドラの先頭で必ず呼ぶ
 *   （呼び出し側の徹底については `HomeScreen` の `onLongPressConsumed` パラメータと
 *   `withLongPressConsumed` ヘルパーの KDoc を参照）
 * - [shouldConsumeUp]: ACTION_UP のたびに呼び、true なら呼び出し元はその UP を
 *   `super.dispatchKeyEvent` に渡さず自分で消費する
 *
 * Android の `KeyEvent` には依存しない純粋なクラスなので JVM 単体テストで検証できる。
 * `TvMainActivity.dispatchKeyEvent` から呼び出して使う。
 */
class LongPressKeyUpFilter(private val trackedKeyCodes: Set<Int>) {

    /** 対象キーコードのうち、現在 ACTION_DOWN を見ていて ACTION_UP をまだ見ていないもの。 */
    private val downSeen = mutableSetOf<Int>()

    /** 対象キーコードのうち、長押しとして消費済みと印が付いているもの。 */
    private val consumedByLongPress = mutableSetOf<Int>()

    /** 対象キーコードの ACTION_DOWN を記録する。対象外のキーコードは無視する。 */
    fun onKeyDown(keyCode: Int) {
        if (keyCode in trackedKeyCodes) {
            downSeen.add(keyCode)
        }
    }

    /**
     * いま押されている（[onKeyDown] を見て、まだ [shouldConsumeUp] で
     * 対応する UP を見ていない）対象キーの押下を「長押しとして消費済み」と印付ける。
     *
     * 押されている対象キーが無いとき（例: 長押しがキー入力以外の経路——
     * タッチやアクセシビリティ操作——から発火した場合）に呼ばれても、
     * 印を付ける対象が無いので何も起こらない。以降の無関係な DOWN → UP には影響しない。
     *
     * 対象キーが2種類同時に押されている場合は、どちらが長押しを起こしたのか
     * このクラスからは区別できない（Compose の `onLongClick` はキーコードを渡さない）
     * ため、**現在押されている対象キーをすべて消費済みにする**。片方だけを
     * 素通しすると、そちらの UP も遷移後の新しい画面に対して発火し誤爆しうるため、
     * 安全側（両方消費）に倒す。
     */
    fun onLongPressConsumed() {
        consumedByLongPress.addAll(downSeen)
    }

    /**
     * この ACTION_UP を消費すべきかを判定する。
     *
     * 対象外のキーコードは常に false（素通し）。対象キーコードの場合、
     * このキーの押下に長押し消費の印が付いていれば true（捨てる）、
     * 付いていなければ false（素通し）を返す。どちらの場合も、この呼び出しで
     * 該当キーコードの押下中フラグと消費済みの印を両方落とす
     * （次の DOWN → UP の対に影響を残さないため）。
     */
    fun shouldConsumeUp(keyCode: Int): Boolean {
        if (keyCode !in trackedKeyCodes) return false
        downSeen.remove(keyCode)
        return consumedByLongPress.remove(keyCode)
    }
}
