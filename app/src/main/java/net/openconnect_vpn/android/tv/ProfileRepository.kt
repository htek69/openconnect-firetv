package net.openconnect_vpn.android.tv

import android.content.Context
import net.openconnect_vpn.android.VpnProfile
import net.openconnect_vpn.android.core.ProfileManager
import net.openconnect_vpn.android.failover.GroupStore

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
 *
 * 裁定86（H1）: [groupStore] は保存の読み書きのためではなく、**接続先の集合が
 * 変わったことを稼働中の `FailoverService` へ届けるため**だけに受け取る
 * （[GroupStore.bumpProfileGeneration] の KDoc 参照）。`ProfileManager` は
 * プロファイルの XML しか触らないので、これを経由しないと削除がエンジンへ
 * 永久に届かない。
 *
 * 裁定88（再レビューの指摘。前の版の誤りの訂正）: ここには
 * 「ここ（**唯一**の作成・削除の通り道）で進めることで、呼び出し側（画面）の
 * 規律に依存せずに済む」と書いてあったが、これは**偽である**。
 * `ProfileManager.create` / `ProfileManager.delete` の呼び出し元は、このクラスの
 * ほかに旧 UI（既存 Java。この計画では変更しない）に3か所ある:
 *
 * - `ConnectionEditorActivity.java:84`（`askProfileRemoval()` → `delete`）
 * - `fragments/VPNProfileList.java:265`（`create`）
 * - `TokenImportActivity.java:347`（`create`）
 *
 * しかもその旧 UI は TV の設定画面（`SettingsScreen` の
 * 「詳細設定とログ（従来の画面）」）から1操作で開ける。そこで作成・削除しても
 * この世代カウンタは進まないので、**このクラスを通らない作成・削除は存在する**。
 *
 * その穴は「旧 UI を開いたあと TV UI に戻ってきた時点で1回だけ世代を進める」
 * ことで閉じてある（[net.openconnect_vpn.android.tv.TvMainActivity] の
 * `onResume`。旧画面で何が起きたかは推測せず、戻ってきたら必ず1回進める）。
 * したがって稼働中のエンジンへ届かない経路は残っていないが、
 * **このクラスが唯一の通り道だからではない。** 新しい作成・削除の経路を
 * Kotlin 側に足すときは、ここと同じく [GroupStore.bumpProfileGeneration] を
 * 通すこと（呼び出し側の規律は依然として必要である）。
 */
class ProfileRepository(
    private val context: Context,
    private val groupStore: GroupStore,
) {

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
        // 裁定86（H1）: 既知プロファイルの集合が変わった。稼働中の
        // FailoverService に読み直させる（GroupStore.bumpProfileGeneration 参照）。
        groupStore.bumpProfileGeneration()
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
     * 裁定86（L3）: F9 の修正で「空欄なら書き込まない」にした結果、一度付けた
     * 表示名を消す手段が無くなり、編集画面で空にして保存すると成功として
     * 画面を閉じるのに名前が変わらない（無言の no-op）という別の嘘が生まれた。
     * 空欄は「既定の名前に戻す」意味に解釈し、`ProfileManager.create` が
     * ホスト名から導出するのと同じ既定名（[defaultNameFor]）を書き込む。
     * `profile_name` を `remove()` しないのは、`VpnProfile.isValid()` が
     * `profile_name` の無いプロファイルを無効と判定し、次の
     * `ProfileManager.init()` の走査でプロファイルが一覧から消えてしまう
     * （＝削除と区別できない事故になる）ため。編集画面はこの挙動を
     * 表示名欄の下に明記している。
     *
     * @return 対象プロファイルが見つかれば true（空欄から既定名に戻した場合、
     *         および既定名を導出できず名前を変えなかった場合も含む）。
     *         見つからなければ false（存在しない UUID、または削除済みの
     *         プロファイル）。
     */
    fun rename(uuid: String, displayName: String): Boolean {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return false
        val name = profileNameOrNull(displayName) ?: uniqueDefaultName(profile)
        if (name != null && name != profile.mPrefs.getString("profile_name", null)) {
            profile.mPrefs.edit().putString("profile_name", name).apply()
        }
        return true
    }

    /**
     * 裁定86（L3）: [profile] の `server_address` から導出した既定名のうち、
     * 他のプロファイルと衝突しないものを返す。導出できなければ null
     * （その場合、呼び出し側は名前を変えない）。
     *
     * 衝突の回避規則は `ProfileManager.create` と同じ（`" (1)"`, `" (2)"` …）に
     * 揃える。既存 Java の `makeProfName` は private なので呼べないが、
     * `getProfileByName` は public なので一意性の判定だけは既存コアの
     * 実装をそのまま使える（自分自身の現在の名前は衝突とみなさない）。
     */
    private fun uniqueDefaultName(profile: VpnProfile): String? {
        val base = defaultNameFor(profile.mPrefs.getString("server_address", null) ?: "")
            ?: return null
        var index = 0
        while (true) {
            val candidate = if (index == 0) base else "$base ($index)"
            val holder = ProfileManager.getProfileByName(candidate)
            if (holder == null || holder.getUUIDString() == profile.getUUIDString()) return candidate
            index++
        }
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
     * 裁定88（再レビューの指摘 Low 3）: アドレスが実際に変わったときは
     * [create] / [delete] と同じく [GroupStore.bumpProfileGeneration] を進める。
     * UUID は変わらないので裁定72a の候補同一性照合（`reconcileCandidateIdentity`）は
     * 何も検知せず、**いま接続中のトンネルはそのまま（古いアドレスのまま）残る。**
     * 世代を進めることで得られるのは、稼働中の `FailoverService` が
     * `reloadGroupsAndProbeTarget()` で `ProfileManager.getProfiles()` を引き直し、
     * **次に `vpn.connect()` する時点で新しいアドレスが使われることが保証される**
     * ことである（`OpenVpnService` は接続のたびにプロファイルを読むので、実際には
     * 読み直しが無くても新アドレスで繋がるが、`groups` の再読込を通しておくことで
     * 「画面が保存した内容とエンジンが持っている一覧が食い違っている時間」を
     * 作らない）。
     *
     * 「アドレスが変わったら現在の候補を張り直す」という**新しい規則は足さない**
     * （裁定86 が明示的に禁じた方向であり、裁定88 でも求められていない）。
     * つまり接続中にアドレスを変えた場合、次に繋ぎ直すまで実際のトンネルは
     * 古いアドレスのままである。
     *
     * @return 対象プロファイルが見つかり、アドレスを変更できたら true。
     *         見つからなければ false。
     */
    fun updateServerAddress(uuid: String, serverAddress: String): Boolean {
        ProfileManager.init(context)
        val profile = ProfileManager.get(uuid) ?: return false

        val previous = profile.mPrefs.getString("server_address", null)
        val editor = profile.mPrefs.edit().putString("server_address", serverAddress)

        val addressChanged = previous != serverAddress
        if (addressChanged) {
            profile.mPrefs.all.keys
                .filter { isCredentialOrCertKey(it) }
                .forEach { editor.remove(it) }
        }

        editor.apply()
        // 裁定88（Low 3）: 変わっていないときは進めない（書き込みを増やさない、
        // という裁定66 の判断と同じ理由。既知プロファイルの内容が変わっていない）。
        if (addressChanged) groupStore.bumpProfileGeneration()
        return true
    }

    /**
     * @return `ProfileManager.delete()` の結果をそのまま返す。false は対象が見つからなかったことを示す。
     *
     * 裁定86（H1）: 削除できたときは [GroupStore.bumpProfileGeneration] を進める。
     * これが無いと、接続中の接続先を削除しても `FailoverService` は
     * `groups` を読み直さず、削除済みプロファイルのトンネルが `Healthy` を
     * 名乗って残り続ける（詳細は同メソッドの KDoc）。false（対象が見つからない）
     * のときは既知プロファイルの集合が変わっていないので進めない。
     */
    fun delete(uuid: String): Boolean {
        ProfileManager.init(context)
        val deleted = ProfileManager.delete(uuid)
        if (deleted) groupStore.bumpProfileGeneration()
        return deleted
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
         * 裁定86（L3）: サーバアドレスから既定の表示名を導出する。
         * `ProfileManager.makeProfName(hostname, 0)` ＋ `capitalize()` の移植
         * （既存 Java は変更しないという制約のもとで、`private static` である
         * あちらを呼べないため。挙動を揃えることが目的であり、規則を変えては
         * ならない）。実機で `test.example.com` → `Example` になることが
         * 確認されている（進捗記録 裁定80 の節）。
         *
         * 規則（あちらと同じ順序で判定する）:
         * 1. IPv4/IPv6 リテラルはそのまま返す。
         * 2. `/` を含む（＝パス付き）ならホスト部だけを取り出す。
         * 3. ドットで分割し、2要素未満ならそれを [capitalize] して返す。
         * 4. 末尾が国別コードらしい（2文字以下）で、その手前が 2文字以下か
         *    `com` なら1つ内側をドメインとみなす。
         * 5. その1つ手前の要素（＝FQDN のうち最初の私的な部分）を
         *    [capitalize] して返す。2文字未満なら元のアドレスを返す。
         *
         * 既存 Java との差異: 手順2 であちらは `Uri.parse().getHost()` を使う
         * （Android API）。ここは JVM 単体テストで検証できる純関数に保つため、
         * スキーム・資格情報・ポートを文字列操作で落とす。`ServerAddressValidator`
         * が資格情報入りのアドレスを弾き、スキームは `https://` だけを許して
         * `normalize` が剥がすので、実際に保存されている `server_address` に
         * 対しては同じ結果になる。
         *
         * @return 導出した既定名。[serverAddress] が空白のみ、またはホスト部が
         *         取り出せない場合は null（呼び出し側は名前を変えない）。
         */
        fun defaultNameFor(serverAddress: String): String? {
            val original = serverAddress.trim()
            if (original.isEmpty()) return null

            // 1. IP アドレスはそのまま（あちらの "leave IP addresses alone"）。
            if ((original.matches(Regex("[0-9.]+")) && original.contains('.')) ||
                (original.matches(Regex("[0-9a-fA-F:]+")) && original.contains(':'))
            ) {
                return original
            }

            // 2. パス付きならホスト部だけを見る。
            val host = if (original.contains('/')) {
                original.substringAfter("://", original)
                    .substringBefore('/')
                    .substringAfterLast('@')
                    .substringBefore(':')
                    .ifBlank { return original }
            } else {
                original
            }

            val parts = host.split('.')
            // 3. FQDN になっていない（ドットが無い）場合。
            if (parts.size < 2) return capitalize(host)

            // 4. 末尾が国別コードらしいときは1つ内側をドメインとみなす。
            var i = parts.size - 1
            if (parts[i].length <= 2 && i > 1) {
                val sld = parts[i - 1]
                if (sld.length <= 2 || sld == "com") i--
            }

            // 5. ドメインの1つ手前（最初の私的な部分）。
            val label = parts[i - 1]
            return if (label.length < 2) original else capitalize(label)
        }

        /**
         * 裁定86（L3）: `ProfileManager.capitalize` の移植。
         * 4文字以下は略語とみなして全部大文字、それより長ければ先頭だけ大文字。
         *
         * 意図的な差異が1つある: あちらは `Locale.getDefault()` で大文字化するが、
         * ここは Kotlin の `uppercase()`（ロケール非依存）を使う。ホスト名は
         * ASCII であり、既定ロケールに依存させると端末の言語設定によって
         * 同じアドレスから違う名前が出る（トルコ語の `i` など）。導出結果が
         * 端末設定で変わらないほうが、この関数の用途（既定名の復元）には正しい。
         */
        private fun capitalize(value: String): String =
            if (value.length <= 4) {
                value.uppercase()
            } else {
                value.substring(0, 1).uppercase() + value.substring(1)
            }

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
