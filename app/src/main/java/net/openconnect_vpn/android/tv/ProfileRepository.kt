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
     * F9（レビュー）: [displayName] が空白のみなら [profileNameOrNull] により
     * `profile_name` への書き込み自体を省く。[create] は既にこのガードを
     * 通していた（`ProfileManager.create` のホスト名由来の既定名を空欄で
     * 上書きしないため）が、こちらは無条件に書き込んでいたため、編集画面で
     * 表示名欄を空にして保存すると `profile_name=""` が書き込まれていた。
     * `VpnProfile.isValid()` は `null` しか拒否しないので空文字は「有効な
     * プロファイル」として通り、名前の無い行が [sortSummaries] で先頭に
     * ソートされ、`ProfileManager.getProfileByName` や [create] の一意性判定にも
     * `""` として参加してしまう不具合があった。
     *
     * @return 対象プロファイルが見つかれば true（表示名が空白で書き込みを
     *         省いた場合も含む）。見つからなければ false（存在しない UUID、
     *         または削除済みのプロファイル）。
     */
    fun rename(uuid: String, displayName: String): Boolean {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return false
        profileNameOrNull(displayName)?.let { name ->
            profile.mPrefs.edit().putString("profile_name", name).apply()
        }
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

    /**
     * 裁定66（Task 6 の判断の差し戻し）: サーバアドレスの打ち間違いは最も起きやすい
     * 失敗であり、Fire TV では文字入力が D-pad ＋ソフトキーボードで著しく遅い。
     * 読み取り専用のままだと1文字の訂正のために削除して作り直すことになり、
     * そのプロファイルが属するグループのメンバーシップも失う
     * （[GroupStore.loadGroups] は存在しないメンバー参照を読み込み時に落とすため）。
     *
     * [serverAddress] は正規化済みの値を渡すこと（[create] と同じ）。
     *
     * `commit()` ではなく `apply()` を使う。裁定51 が `commit()` を要求したのは
     * `create()` 直後という特定の場面——`ProfileManager.create()` の永続化が
     * `apply()`（非同期）で、呼び出し元が同じ呼び出しの中で直後に
     * `rename()`/`ensureBatchMode()`/`delete()` を呼ぶと、`ProfileManager.init()` の
     * ディレクトリ再走査がまだディスクに無いファイルを見失う——という競合を
     * 避けるためだった。ここは `ProfileManager.get(uuid)` が既に見つけている
     * （＝ディレクトリ走査が既に把握している）既存プロファイルの更新であり、
     * この競合が起きる条件を満たさない。[rename] / [ensureBatchMode] と全く同じ
     * 状況なので、それらと同じく `apply()` で足りる。
     *
     * 裁定67: アドレスが実際に変わるときは、このプロファイル自身の prefs に残る
     * 資格情報・証明書承認（[isCredentialOrCertKey] が true を返すキー）も一緒に消す。
     *
     * `AuthFormHandler.getFormPrefix`（`AuthFormHandler.java:166-173`）が組み立てる
     * `FORMDATA-<フォーム構造のダイジェスト>-` 鍵は、認証フォームの**構造**
     * （各項目の type/name/label）だけから決まり、接続先サーバの identity を
     * 一切含まない。したがって同じ形の認証フォームを出す別サーバへアドレスを
     * 変更すると、次回接続時に `batch_mode=empty_only` かつ全項目が埋まっている
     * 状態でこのプロファイルの資格情報が**ダイアログを出さずに新サーバへ自動送信
     * されてしまう**（利用者のパスワードが意図しないサーバへ渡る、プライバシー上の
     * 事故）。`ACCEPTED-CERT-<SHA1>` は証明書自身のハッシュを鍵にしているため
     * 誤った承認には繋がらないが、もう指していないサーバの承認記録を残すのは
     * 紛らわしく、二度と一致しないので一緒に消す。
     *
     * `batch_mode` を含むそれ以外のキーには触れない。
     * キーは `profile.mPrefs.getAll()` から実際に存在するものだけを拾う
     * （ダイジェストは計算できないので、推測ではなく列挙で判定する）。
     *
     * `commit()` ではなく `apply()` を使う。裁定51 が `commit()` を要求したのは
     * `create()` 直後という特定の場面——`ProfileManager.create()` の永続化が
     * `apply()`（非同期）で、呼び出し元が同じ呼び出しの中で直後に
     * `rename()`/`ensureBatchMode()`/`delete()` を呼ぶと、`ProfileManager.init()` の
     * ディレクトリ再走査がまだディスクに無いファイルを見失う——という競合を
     * 避けるためだった。ここは `ProfileManager.get(uuid)` が既に見つけている
     * （＝ディレクトリ走査が既に把握している）既存プロファイルの更新であり、
     * この競合が起きる条件を満たさない。[rename] / [ensureBatchMode] と全く同じ
     * 状況なので、それらと同じく `apply()` で足りる。
     *
     * @return 対象プロファイルが見つかり、アドレスを変更できたら true。
     *         見つからなければ false。
     */
    fun updateServerAddress(uuid: String, serverAddress: String): Boolean {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return false

        val previous = profile.mPrefs.getString("server_address", null)
        val editor = profile.mPrefs.edit().putString("server_address", serverAddress)

        if (previous != serverAddress) {
            profile.mPrefs.all.keys
                .filter { isCredentialOrCertKey(it) }
                .forEach { editor.remove(it) }
        }

        editor.apply()
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

        /**
         * 裁定67: [key] が、サーバアドレス変更時に消すべき資格情報・証明書承認の
         * キーかどうか。既存 Java（`ClearPasswordPreference.java:47`）が
         * 「パスワードを消去」設定でこの2接頭辞を使っているのと同じ判定を
         * 再利用する（あちらは全プロファイル横断・こちらは1プロファイルの
         * prefs 内だけが対象という違いはあるが、判定条件自体は同じでよい）。
         */
        fun isCredentialOrCertKey(key: String): Boolean =
            key.startsWith("FORMDATA-") || key.startsWith("ACCEPTED-CERT-")
    }
}
