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
}
