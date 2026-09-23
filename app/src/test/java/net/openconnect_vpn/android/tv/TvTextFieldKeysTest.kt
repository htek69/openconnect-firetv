package net.openconnect_vpn.android.tv

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁定68: `TvTextField` が D-pad の UP/DOWN/CENTER/ENTER を横取りする判定ロジックの
 * テスト。`Key`/`KeyEventType` は compose-ui のプレーンな値クラスで Android
 * フレームワークに依存しないため、Robolectric 無しで検証できる。
 *
 * 「フォーカスが動く」こと自体（実機でのみ確定した不具合）はここでは検証できない。
 * ここで保証できるのは「どのキーを横取り対象とみなすか」という判定だけである。
 */
class TvTextFieldKeysTest {

    @Test
    fun `UP DOWN CENTER ENTER NumPadEnter は横取り対象`() {
        listOf(
            Key.DirectionUp,
            Key.DirectionDown,
            Key.DirectionCenter,
            Key.Enter,
            Key.NumPadEnter,
        ).forEach { key ->
            assertTrue("$key は横取りされるべき", TvTextFieldKeys.isHandled(key))
        }
    }

    @Test
    fun `LEFT RIGHT や文字キーは横取りしない`() {
        listOf(Key.DirectionLeft, Key.DirectionRight, Key.A, Key.Backspace).forEach { key ->
            assertFalse("$key は横取りしないはず", TvTextFieldKeys.isHandled(key))
        }
    }

    @Test
    fun `UP はフォーカスを上へ`() {
        assertEquals(TvKeyAction.MoveFocusUp, TvTextFieldKeys.actionFor(Key.DirectionUp))
    }

    @Test
    fun `DOWN はフォーカスを下へ`() {
        assertEquals(TvKeyAction.MoveFocusDown, TvTextFieldKeys.actionFor(Key.DirectionDown))
    }

    @Test
    fun `CENTER はソフトキーボードを開く（文字を入れない）`() {
        assertEquals(TvKeyAction.OpenKeyboard, TvTextFieldKeys.actionFor(Key.DirectionCenter))
    }

    @Test
    fun `ENTER と NumPadEnter も CENTER と同じくソフトキーボードを開く`() {
        assertEquals(TvKeyAction.OpenKeyboard, TvTextFieldKeys.actionFor(Key.Enter))
        assertEquals(TvKeyAction.OpenKeyboard, TvTextFieldKeys.actionFor(Key.NumPadEnter))
    }

    @Test
    fun `横取り対象でないキーは Ignore`() {
        assertEquals(TvKeyAction.Ignore, TvTextFieldKeys.actionFor(Key.DirectionLeft))
        assertEquals(TvKeyAction.Ignore, TvTextFieldKeys.actionFor(Key.A))
    }
}
