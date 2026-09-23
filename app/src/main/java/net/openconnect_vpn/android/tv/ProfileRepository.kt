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
        val summaries = ProfileManager.getProfiles().map { profile ->
            ProfileSummary(
                uuid = profile.getUUIDString(),
                name = profile.name ?: profile.getUUIDString(),
                serverAddress = profile.mPrefs.getString("server_address", "") ?: "",
            )
        }
        return sortSummaries(summaries)
    }

    /**
     * 接続先を作成して UUID を返す。
     * [serverAddress] は正規化済みの値を渡すこと。
     *
     * 最後の書き込みに `apply()` ではなく `commit()` を使う。理由:
     * `ProfileManager.create()` とその内部で呼ばれる `VpnProfile` の3引数コンストラクタは
     * どちらも `apply()`（非同期）でディスクへの書き出しを予約するだけで、呼び出し時点では
     * `profile-<uuid>.xml` がまだ存在しない可能性がある。`SharedPreferencesImpl` は
     * 編集単位の差分ではなく共有の in-memory map 全体をコミットのたびにディスクへ
     * 書き出すため（AOSP `frameworks/base` の `SharedPreferencesImpl#commitToMemory` /
     * `#writeToFile` で確認）、同じ `SharedPreferences` インスタンス上でこちらが
     * `commit()` を呼べば、先に `apply()` で積まれていた分もまとめて同期的に
     * 確定する。これにより `create()` から戻った時点でファイルが必ず存在し、
     * 直後の `rename()` / `ensureBatchMode()` / `delete()` が `ProfileManager.init()`
     * のディレクトリ再走査で見失う競合を防ぐ。
     */
    fun create(serverAddress: String, displayName: String): String {
        ProfileManager.init(context)
        val profile = ProfileManager.create(serverAddress)
        val editor = profile.mPrefs.edit()
            .putString("server_address", serverAddress)
            .putString("batch_mode", BATCH_MODE_EMPTY_ONLY)
        profileNameOrNull(displayName)?.let { name -> editor.putString("profile_name", name) }
        editor.commit()
        return profile.getUUIDString()
    }

    /**
     * @return 対象プロファイルが見つかり、名前を変更できたら true。
     *         見つからなければ false（存在しない UUID、または削除済みのプロファイル）。
     */
    fun rename(uuid: String, displayName: String): Boolean {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return false
        profile.mPrefs.edit().putString("profile_name", displayName).apply()
        return true
    }

    /**
     * 既存プロファイルに batch_mode が無い場合に補う（フォーク元で作った既存分の救済）。
     * @return 対象プロファイルが見つかれば true（既に empty_only であっても true）、
     *         見つからなければ false。
     */
    fun ensureBatchMode(uuid: String): Boolean {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return false
        if (needsBatchModeUpdate(profile.mPrefs.getString("batch_mode", null))) {
            profile.mPrefs.edit().putString("batch_mode", BATCH_MODE_EMPTY_ONLY).apply()
        }
        return true
    }

    /** @return `ProfileManager.delete()` の結果をそのまま返す。false は対象が見つからなかったことを示す。 */
    fun delete(uuid: String): Boolean {
        ProfileManager.init(context)
        return ProfileManager.delete(uuid)
    }

    companion object {
        /**
         * "enabled" ではなく "empty_only" を使う。"enabled" は保存済み資格情報が
         * 無くてもダイアログを出さずに空欄を送信するため、初回ログインが不可能になる。
         * "empty_only" は全項目が埋まっているときだけダイアログを省く（仕様書 9.2）。
         */
        private const val BATCH_MODE_EMPTY_ONLY = "empty_only"

        /**
         * 一覧の並び順（名前の小文字化による昇順）。`SharedPreferences` に触れない純関数として
         * 切り出してあり、Robolectric 無しでユニットテストできる。
         */
        fun sortSummaries(summaries: List<ProfileSummary>): List<ProfileSummary> =
            summaries.sortedBy { it.name.lowercase() }

        /**
         * `displayName` が空白のみなら `profile_name` を書き込まない、という判断だけを
         * 切り出した純関数。
         */
        fun profileNameOrNull(displayName: String): String? =
            displayName.takeIf { it.isNotBlank() }

        /**
         * 既存の `batch_mode` が `empty_only` と異なる場合だけ書き込む、という判断だけを
         * 切り出した純関数。
         */
        fun needsBatchModeUpdate(current: String?): Boolean =
            current != BATCH_MODE_EMPTY_ONLY
    }
}
