package net.openconnect_vpn.android.tv

import android.content.Context
import net.openconnect_vpn.android.core.ProfileManager

data class ProfileSummary(
    val uuid: String,
    val name: String,
    val serverAddress: String,
)

/**
 * 既存の ProfileManager への薄いアダプタ。
 *
 * プロファイル作成時に batch_mode を "empty_only" にするのが最も重要な役目である。
 * これが無いと再接続ごとに認証ダイアログが出て自動フェイルオーバーが止まる（仕様書 9.2）。
 */
class ProfileRepository(private val context: Context) {

    fun list(): List<ProfileSummary> {
        ProfileManager.init(context)
        return ProfileManager.getProfiles()
            .map { profile ->
                ProfileSummary(
                    uuid = profile.getUUIDString(),
                    name = profile.name ?: profile.getUUIDString(),
                    serverAddress = profile.mPrefs.getString("server_address", "") ?: "",
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 接続先を作成して UUID を返す。
     * [serverAddress] は正規化済みの値を渡すこと。
     */
    fun create(serverAddress: String, displayName: String): String {
        ProfileManager.init(context)
        val profile = ProfileManager.create(serverAddress)
        profile.mPrefs.edit()
            .putString("server_address", serverAddress)
            .putString("batch_mode", BATCH_MODE_EMPTY_ONLY)
            .apply()
        if (displayName.isNotBlank()) {
            profile.mPrefs.edit().putString("profile_name", displayName).apply()
        }
        return profile.getUUIDString()
    }

    fun rename(uuid: String, displayName: String) {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return
        profile.mPrefs.edit().putString("profile_name", displayName).apply()
    }

    /** 既存プロファイルに batch_mode が無い場合に補う（フォーク元で作った既存分の救済）。 */
    fun ensureBatchMode(uuid: String) {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return
        if (profile.mPrefs.getString("batch_mode", null) != BATCH_MODE_EMPTY_ONLY) {
            profile.mPrefs.edit().putString("batch_mode", BATCH_MODE_EMPTY_ONLY).apply()
        }
    }

    fun delete(uuid: String) {
        ProfileManager.init(context)
        ProfileManager.delete(uuid)
    }

    private companion object {
        /**
         * "enabled" ではなく "empty_only" を使う。"enabled" は保存済み資格情報が
         * 無くてもダイアログを出さずに空欄を送信するため、初回ログインが不可能になる。
         * "empty_only" は全項目が埋まっているときだけダイアログを省く（仕様書 9.2）。
         */
        const val BATCH_MODE_EMPTY_ONLY = "empty_only"
    }
}
