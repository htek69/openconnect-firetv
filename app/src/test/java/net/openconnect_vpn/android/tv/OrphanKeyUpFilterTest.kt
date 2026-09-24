package net.openconnect_vpn.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanKeyUpFilterTest {

    // 実機の Fire TV では KEYCODE_DPAD_CENTER に加え、決定キーが
    // KEY_KPENTER(96) 経由で KEYCODE_NUMPAD_ENTER として届く場合があるため、
    // KEYCODE_ENTER 系も含めて3つを対象にする（裁定78）。
    private val centerKeyCode = 23 // KeyEvent.KEYCODE_DPAD_CENTER
    private val enterKeyCode = 66 // KeyEvent.KEYCODE_ENTER
    private val numpadEnterKeyCode = 160 // KeyEvent.KEYCODE_NUMPAD_ENTER
    private val backKeyCode = 4 // KeyEvent.KEYCODE_BACK

    private fun newFilter() = OrphanKeyUpFilter(
        trackedKeyCodes = setOf(centerKeyCode, enterKeyCode, numpadEnterKeyCode),
    )

    @Test
    fun `DOWN と UP の対は UP を消費しない`() {
        val filter = newFilter()
        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `DOWN を見ていない UP は消費する`() {
        val filter = newFilter()
        assertTrue(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `一度消費したあと次の DOWN と UP の対は通常どおり通る`() {
        val filter = newFilter()

        // 孤児 UP（対応する DOWN なし）を消費する
        assertTrue(filter.shouldConsumeUp(centerKeyCode))

        // 続く正常な DOWN → UP の対は影響を受けない
        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `対象外のキーコードは常に消費しない`() {
        val filter = newFilter()

        // DOWN を見ていなくても、対象外キーコードなら消費しない
        assertFalse(filter.shouldConsumeUp(backKeyCode))

        // DOWN を見た後でも同様
        filter.onKeyDown(backKeyCode)
        assertFalse(filter.shouldConsumeUp(backKeyCode))
    }

    @Test
    fun `ENTER と NUMPAD_ENTER もそれぞれ独立して対象になる`() {
        val filter = newFilter()

        filter.onKeyDown(enterKeyCode)
        assertFalse(filter.shouldConsumeUp(enterKeyCode))
        assertTrue(filter.shouldConsumeUp(numpadEnterKeyCode))

        filter.onKeyDown(numpadEnterKeyCode)
        assertFalse(filter.shouldConsumeUp(numpadEnterKeyCode))
    }

    @Test
    fun `キーコードごとに独立してDOWNを記録する`() {
        val filter = newFilter()

        filter.onKeyDown(centerKeyCode)
        // center の DOWN は enter の UP 判定に影響しない
        assertTrue(filter.shouldConsumeUp(enterKeyCode))
        // center 自身は正常な対として通る
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }
}
