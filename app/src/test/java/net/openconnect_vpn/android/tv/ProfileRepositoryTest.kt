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

    // --- 裁定86（L3）: 表示名を空にしたときに戻す既定名の導出 ---

    @Test
    fun `既定名は実機で確認した通り test_example_com から Example になる`() {
        // 進捗記録（裁定80 の節）で ProfileManager.create が実機でこの名前を
        // 付けたことを確認済み。移植した defaultNameFor が同じ値を返すことが
        // L3 の前提なので、これを基準点として固定する。
        assertEquals("Example", ProfileRepository.defaultNameFor("test.example.com"))
    }

    @Test
    fun `4文字以下のラベルは略語とみなして全部大文字になる`() {
        // ProfileManager.capitalize の規則（length <= 4 なら uppercase）。
        assertEquals("ACME", ProfileRepository.defaultNameFor("vpn.acme.com"))
    }

    @Test
    fun `国別コードの手前が co や2文字なら1つ内側をドメインとみなす`() {
        assertEquals("Example", ProfileRepository.defaultNameFor("vpn.example.co.jp"))
        assertEquals("Example", ProfileRepository.defaultNameFor("vpn.example.com.au"))
    }

    @Test
    fun `ドットの無いホスト名はそれ自体を大文字化の規則にかける`() {
        // 5文字以上なので先頭だけ大文字。
        assertEquals("Firewall", ProfileRepository.defaultNameFor("firewall"))
        // 4文字以下なので全部大文字。
        assertEquals("HOME", ProfileRepository.defaultNameFor("home"))
    }

    @Test
    fun `IPアドレスは既定名にしない`() {
        assertEquals("192.168.1.1", ProfileRepository.defaultNameFor("192.168.1.1"))
        assertEquals("2001:db8::1", ProfileRepository.defaultNameFor("2001:db8::1"))
    }

    @Test
    fun `パスやポートが付いていてもホスト部から導出する`() {
        assertEquals("Example", ProfileRepository.defaultNameFor("vpn.example.com/portal"))
        assertEquals("Example", ProfileRepository.defaultNameFor("https://vpn.example.com/portal"))
        assertEquals("Example", ProfileRepository.defaultNameFor("vpn.example.com:8443"))
    }

    @Test
    fun `空のアドレスからは既定名を導出しない`() {
        // null を返すと rename は名前を変えない（無効な profile_name を
        // 書き込まない）。
        assertNull(ProfileRepository.defaultNameFor(""))
        assertNull(ProfileRepository.defaultNameFor("   "))
    }

    @Test
    fun `接頭辞を含むだけで先頭一致でないキーは判定されない`() {
        // startsWith であって contains ではないことを確認する。
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("prefix-FORMDATA-x"))
        assertEquals(false, ProfileRepository.isCredentialOrCertKey("prefix-ACCEPTED-CERT-x"))
    }

    @Test
    fun `キーが1つも無ければ認証情報は保存されていない`() {
        assertEquals(false, ProfileRepository.hasSavedFormData(emptySet()))
    }

    @Test
    fun `証明書の承認だけでは認証情報が保存された証拠にならない`() {
        // ACCEPTED-CERT- は証明書のハッシュを承認した記録に過ぎず、
        // 認証フォームに答えたことを意味しない（初回ログインは未完了）。
        assertEquals(
            false,
            ProfileRepository.hasSavedFormData(
                setOf("server_address", "batch_mode", "profile_name", "ACCEPTED-CERT-abcdef"),
            ),
        )
    }

    @Test
    fun `FORMDATA のキーが1つでもあれば認証情報は保存されている`() {
        assertEquals(
            true,
            ProfileRepository.hasSavedFormData(
                setOf("server_address", "ACCEPTED-CERT-abcdef", "FORMDATA-0011-2233"),
            ),
        )
    }

    @Test
    fun `FORMDATA を含むだけで先頭一致でないキーは保存済みと見なさない`() {
        assertEquals(
            false,
            ProfileRepository.hasSavedFormData(setOf("prefix-FORMDATA-0011-2233")),
        )
    }
}
