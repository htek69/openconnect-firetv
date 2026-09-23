package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProfileEditScreen` から切り出した純粋ロジックのテスト。
 * Compose 部分は Robolectric 無しでは検証できないため、判断ロジックだけをここで扱う。
 */
class ProfileEditMessagesTest {

    @Test
    fun `InvalidReason の全ケースにメッセージがある`() {
        // 網羅していないケースがあれば when が破綻してコンパイルが通らないはずだが、
        // 実行時にも空文字や重複が無いことを確認しておく。
        val messages = InvalidReason.entries.map { ProfileEditMessages.messageFor(it) }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(InvalidReason.entries.size, messages.toSet().size)
    }

    @Test
    fun `Empty は入力を促すメッセージ`() {
        assertEquals(
            "サーバ URL を入力してください",
            ProfileEditMessages.messageFor(InvalidReason.Empty),
        )
    }

    @Test
    fun `ContainsWhitespace は空白を含めないよう伝える`() {
        val message = ProfileEditMessages.messageFor(InvalidReason.ContainsWhitespace)
        assertTrue(message.contains("空白"))
    }

    @Test
    fun `NoHost はホスト名の不足を伝える`() {
        val message = ProfileEditMessages.messageFor(InvalidReason.NoHost)
        assertTrue(message.contains("ホスト名"))
    }

    @Test
    fun `BadScheme は https を使うよう伝える`() {
        val message = ProfileEditMessages.messageFor(InvalidReason.BadScheme)
        assertTrue(message.contains("https"))
    }

    @Test
    fun `ContainsCredentials は資格情報を含められないことを伝える`() {
        val message = ProfileEditMessages.messageFor(InvalidReason.ContainsCredentials)
        assertTrue(message.contains("ユーザー名"))
        assertTrue(message.contains("パスワード"))
    }

    @Test
    fun `rename も ensureBatchMode も成功すれば Success`() {
        val outcome = ProfileEditMessages.outcomeForEdit(renamed = true, batchModeEnsured = true)
        assertEquals(ProfileEditMessages.SaveOutcome.Success, outcome)
    }

    @Test
    fun `rename が失敗したら Error で理由が分かる`() {
        val outcome = ProfileEditMessages.outcomeForEdit(renamed = false, batchModeEnsured = false)
        assertTrue(outcome is ProfileEditMessages.SaveOutcome.Error)
        val message = (outcome as ProfileEditMessages.SaveOutcome.Error).message
        assertTrue(message.contains("見つかりませんでした"))
    }

    @Test
    fun `rename は成功したが ensureBatchMode が失敗したら Error で理由が分かる`() {
        val outcome = ProfileEditMessages.outcomeForEdit(renamed = true, batchModeEnsured = false)
        assertTrue(outcome is ProfileEditMessages.SaveOutcome.Error)
        val message = (outcome as ProfileEditMessages.SaveOutcome.Error).message
        assertTrue(message.contains("自動接続"))
    }
}
