package net.openconnect_vpn.android.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 裁定93: ダイアログ描画先の計数器。 */
class DialogHostTrackerTest {

    @Test
    fun `初期状態では描画先が無い`() {
        assertFalse(DialogHostTracker().attached)
    }

    @Test
    fun `attach で true になり detach で false に戻る`() {
        val tracker = DialogHostTracker()
        tracker.onHostAttached()
        assertTrue(tracker.attached)
        tracker.onHostDetached()
        assertFalse(tracker.attached)
    }

    @Test
    fun `余分な detach で負にならず その後の attach が効く`() {
        // 負に落ちると、以後 attach しても attached が false のままになり、
        // 裁定93 の保護（人が答えている間は候補を除外しない）が黙って死ぬ。
        val tracker = DialogHostTracker()
        tracker.onHostDetached()
        tracker.onHostDetached()
        assertFalse(tracker.attached)

        tracker.onHostAttached()
        assertTrue(tracker.attached)
    }

    @Test
    fun `画面遷移で重なっても最後の detach まで true を保つ`() {
        // Activity の遷移（新しい画面の onResume が古い画面の onPause より先に
        // 走る形）で申告が一時的に2重になっても、途中で false に落ちない。
        val tracker = DialogHostTracker()
        tracker.onHostAttached()
        tracker.onHostAttached()
        tracker.onHostDetached()
        assertTrue(tracker.attached)
        tracker.onHostDetached()
        assertFalse(tracker.attached)
    }
}
