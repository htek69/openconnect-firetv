package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ProfileRepository` 自体は実 `SharedPreferences`/`Context` に依存するため
 * Robolectric 無しではユニットテストできない（裁定52）。
 * ここでは `SharedPreferences` に触れない純粋な判断ロジックだけを検証する。
 */
class ProfileRepositoryTest {

    @Test
    fun `一覧は名前の小文字化で昇順に並ぶ`() {
        val input = listOf(
            ProfileSummary(uuid = "1", name = "Zebra", serverAddress = "a"),
            ProfileSummary(uuid = "2", name = "apple", serverAddress = "b"),
            ProfileSummary(uuid = "3", name = "Banana", serverAddress = "c"),
        )

        val sorted = ProfileRepository.sortSummaries(input)

        assertEquals(listOf("apple", "Banana", "Zebra"), sorted.map { it.name })
    }

    @Test
    fun `表示名が空でなければそのまま使う`() {
        assertEquals("居間のTV", ProfileRepository.profileNameOrNull("居間のTV"))
    }

    @Test
    fun `表示名が空文字なら null`() {
        assertNull(ProfileRepository.profileNameOrNull(""))
    }

    @Test
    fun `表示名が空白のみなら null`() {
        assertNull(ProfileRepository.profileNameOrNull("   "))
    }

    @Test
    fun `batch_mode が未設定なら更新が必要`() {
        assertEquals(true, ProfileRepository.needsBatchModeUpdate(null))
    }

    @Test
    fun `batch_mode が enabled のままなら更新が必要`() {
        assertEquals(true, ProfileRepository.needsBatchModeUpdate("enabled"))
    }

    @Test
    fun `batch_mode が既に empty_only なら更新不要`() {
        assertEquals(false, ProfileRepository.needsBatchModeUpdate("empty_only"))
    }

    // --- 裁定67: サーバアドレス変更時に消すべきキーの判定 ---

    @Test
    fun `FORMDATA- で始まるキーは資格情報キーとして判定される`() {
        // AuthFormHandler.getFormPrefix が組み立てる形。実際のダイジェストは
        // 計算できない前提なので、接頭辞だけで判定できることを確認する。
        assertEquals(
            true,
            ProfileRepository.isCredentialOrCertKey("FORMDATA-abc123-def456"),
        )
    }

    @Test
    fun `ACCEPTED-CERT- で始まるキーは証明書承認キーとして判定される`() {
        assertEquals(
            true,
            ProfileRepository.isCredentialOrCertKey("ACCEPTED-CERT-aa11bb22cc33"),
        )
    }

    @Test
    fun `server_address や batch_mode などそれ以外のキーは判定されない`() {
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("server_address"))
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("batch_mode"))
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("profile_name"))
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("profile_uuid"))
    }

    @Test
    fun `接頭辞を含むだけで先頭一致でないキーは判定されない`() {
        // startsWith であって contains ではないことを確認する。
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("prefix-FORMDATA-x"))
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("prefix-ACCEPTED-CERT-x"))
    }
}
