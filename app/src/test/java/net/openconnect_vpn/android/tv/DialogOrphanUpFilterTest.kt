package net.openconnect_vpn.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogOrphanUpFilterTest {

    // LongPressKeyUpFilterTest と同じ対象キーコード（裁定78 から変わらず）。
    private val centerKeyCode = 23 // KeyEvent.KEYCODE_DPAD_CENTER
    private val enterKeyCode = 66 // KeyEvent.KEYCODE_ENTER
    private val numpadEnterKeyCode = 160 // KeyEvent.KEYCODE_NUMPAD_ENTER
    private val backKeyCode = 4 // KeyEvent.KEYCODE_BACK

    private fun newFilter() = DialogOrphanUpFilter(
        trackedKeyCodes = setOf(centerKeyCode, enterKeyCode, numpadEnterKeyCode),
    )

    @Test
    fun `KeyDown から KeyUp の対は消費しない`() {
        val filter = newFilter()
        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `この Window で KeyDown を見ていない KeyUp は消費する`() {
        val filter = newFilter()
        // ダイアログが開く直前の長押しの DOWN は、この Window ではなく元の
        // Window が受け取っている。この Window にはそのキーの DOWN が一度も
        // 届いていないので、最初に届く UP は孤児として消費されるべき。
        assertTrue(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `孤児UPを消費した後の次のDOWN→UPは消費しない`() {
        val filter = newFilter()

        // 1回目: 孤児 UP を消費する。
        assertTrue(filter.shouldConsumeUp(centerKeyCode))

        // 2回目: 消費済みの印はその場で落ちているので、続く通常の DOWN → UP は
        // 影響を受けず、ダイアログ内で押して離した決定はちゃんと効く。
        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `対象外のキーコードは常に消費しない`() {
        val filter = newFilter()

        // DOWN を一度も見ていない状態でも、対象外キーは孤児判定の対象にならない
        // （BACK によるダイアログの取り消しを壊さないため）。
        assertFalse(filter.shouldConsumeUp(backKeyCode))

        filter.onKeyDown(backKeyCode)
        assertFalse(filter.shouldConsumeUp(backKeyCode))
    }

    @Test
    fun `ENTER と NUMPAD_ENTER もそれぞれ独立して対象になる`() {
        val filter = newFilter()

        assertTrue(filter.shouldConsumeUp(enterKeyCode))

        filter.onKeyDown(numpadEnterKeyCode)
        assertFalse(filter.shouldConsumeUp(numpadEnterKeyCode))
    }

    @Test
    fun `キーコードごとに独立してDOWNの有無を追跡する`() {
        val filter = newFilter()

        filter.onKeyDown(centerKeyCode)

        // center は DOWN 済みなので消費しない。enter は DOWN を見ていないので
        // （center の DOWN とは無関係に）孤児として消費される。
        assertTrue(filter.shouldConsumeUp(enterKeyCode))
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }
}
