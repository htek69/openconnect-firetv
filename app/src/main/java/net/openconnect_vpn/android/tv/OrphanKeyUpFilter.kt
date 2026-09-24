package net.openconnect_vpn.android.tv

/**
 * 孤児の ACTION_UP（対応する ACTION_DOWN をこの Activity が見ていない UP）を判定する。
 *
 * 裁定78: 長押しで画面遷移する操作（`Modifier.combinedClickable` の `onLongClick` など）は
 * キーを押している最中（DOWN）に発火する。その後に届く ACTION_UP は行き場を失い、
 * 遷移先の画面の初期フォーカス要素（`TvFieldRow` の編集モードや `ConfirmDialog` の
 * 既定選択）を誤って押してしまう。対象キーコードについて DOWN を見たかどうかを記録し、
 * 対応する DOWN を見ていない UP を「消費すべき（呼び出し元に渡さない）」と判定する。
 *
 * Android の `KeyEvent` には依存しない純粋なクラスなので JVM 単体テストで検証できる。
 * `TvMainActivity.dispatchKeyEvent` から `onKeyDown` / `shouldConsumeUp` を呼び出して使う。
 */
class OrphanKeyUpFilter(private val trackedKeyCodes: Set<Int>) {

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
     * 対象キーコードで、対応する DOWN を見ていなければ true（孤児 UP、消費する）。
     * 対応する DOWN を見ていれば、そのフラグを降ろして false を返す（通常の UP、素通しする）。
     * これにより次の DOWN → UP の対は影響を受けない。
     */
    fun shouldConsumeUp(keyCode: Int): Boolean {
        if (keyCode !in trackedKeyCodes) return false
        return !downSeen.remove(keyCode)
    }
}
