package net.openconnect_vpn.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressKeyUpFilterTest {

    // 実機の Fire TV では KEYCODE_DPAD_CENTER に加え、決定キーが
    // KEY_KPENTER(96) 経由で KEYCODE_NUMPAD_ENTER として届く場合があるため、
    // KEYCODE_ENTER 系も含めて3つを対象にする（裁定78 から変わらず）。
    private val centerKeyCode = 23 // KeyEvent.KEYCODE_DPAD_CENTER
    private val enterKeyCode = 66 // KeyEvent.KEYCODE_ENTER
    private val numpadEnterKeyCode = 160 // KeyEvent.KEYCODE_NUMPAD_ENTER
    private val backKeyCode = 4 // KeyEvent.KEYCODE_BACK

    private fun newFilter() = LongPressKeyUpFilter(
        trackedKeyCodes = setOf(centerKeyCode, enterKeyCode, numpadEnterKeyCode),
    )

    @Test
    fun `DOWN と UP だけ（長押しなし）は UP を消費しない`() {
        val filter = newFilter()
        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `DOWN の後に長押しとして消費されると UP を消費する`() {
        val filter = newFilter()
        filter.onKeyDown(centerKeyCode)
        filter.onLongPressConsumed()
        assertTrue(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `UPを消費した後は印が落ちており次のDOWN→UPは消費しない`() {
        val filter = newFilter()

        filter.onKeyDown(centerKeyCode)
        filter.onLongPressConsumed()
        assertTrue(filter.shouldConsumeUp(centerKeyCode))

        // 印は shouldConsumeUp の時点で落ちているので、続く通常の DOWN → UP は影響を受けない
        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `押下中でないときに onLongPressConsumed が呼ばれても次の無関係なUPは消費しない`() {
        val filter = newFilter()

        // どのキーも押されていない状態で呼ばれる（例: 長押しがキー入力以外の経路から
        // 発火した場合）。印を付ける対象が無いので何も起こらないはず。
        filter.onLongPressConsumed()

        filter.onKeyDown(centerKeyCode)
        assertFalse(filter.shouldConsumeUp(centerKeyCode))
    }

    @Test
    fun `対象外のキーコードは常に消費しない`() {
        val filter = newFilter()

        assertFalse(filter.shouldConsumeUp(backKeyCode))

        filter.onKeyDown(backKeyCode)
        filter.onLongPressConsumed()
        assertFalse(filter.shouldConsumeUp(backKeyCode))
    }

    @Test
    fun `対象キーが2種類同時に押された状態で長押し消費されると両方のUPを消費する`() {
        val filter = newFilter()

        // center と enter が同時に押されている状態を模す。このクラスは
        // onLongClick がどちらのキーで起きたか区別できないため、現在押されている
        // 対象キーをすべて消費済みにする（安全側に倒す仕様。KDoc 参照）。
        filter.onKeyDown(centerKeyCode)
        filter.onKeyDown(enterKeyCode)
        filter.onLongPressConsumed()

        assertTrue(filter.shouldConsumeUp(centerKeyCode))
        assertTrue(filter.shouldConsumeUp(enterKeyCode))
    }

    @Test
    fun `ENTER と NUMPAD_ENTER もそれぞれ独立して対象になる`() {
        val filter = newFilter()

        filter.onKeyDown(enterKeyCode)
        assertFalse(filter.shouldConsumeUp(enterKeyCode))

        filter.onKeyDown(numpadEnterKeyCode)
        filter.onLongPressConsumed()
        assertTrue(filter.shouldConsumeUp(numpadEnterKeyCode))
    }

    @Test
    fun `キーコードごとに独立して押下中状態を追跡する`() {
        val filter = newFilter()

        filter.onKeyDown(centerKeyCode)
        filter.onLongPressConsumed()

        // center だけが押下中で消費済みの印が付いている。enter は押下中ですらないので
        // 印が付きようがなく、UP は素通しされる。
        assertFalse(filter.shouldConsumeUp(enterKeyCode))
        // center 自身は消費済みの印どおり捨てられる。
        assertTrue(filter.shouldConsumeUp(centerKeyCode))
    }
}
