package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverState

enum class ConnectionBadge {
    None,
    Connecting,
    Verifying,
    Connected,
    Retrying,
}

sealed interface HomeRow {

    data class GroupRow(
        val groupId: String,
        val name: String,
        val memberCount: Int,
        val autoFailoverEnabled: Boolean,
        val badge: ConnectionBadge,
        /**
         * この行のグループで、いま対象になっている接続先の表示名。
         * **接続済みを意味しない。** 接続済みかどうかは [badge] が示す
         * （例: [ConnectionBadge.Connecting] の間はダイヤル中でトンネル未確立でも
         * この名前が出る）。対象が定まっていない状態（`Idle` / `Exhausted` /
         * `FailingOver`）では null。
         */
        val memberName: String?,
    ) : HomeRow

    data class SectionHeader(val title: String) : HomeRow

    data class ProfileRow(
        val uuid: String,
        val name: String,
        val serverAddress: String,
        /**
         * この接続先がまだ初回ログインを終えていないか
         * （`ProfileRepository.needsFirstLogin`）。true の間、自動切替はこの
         * 接続先を候補にしない（無人では絶対に成功しないため）。
         *
         * 既定値が false なのは、判定を渡さない呼び出し元（既存のテスト）で
         * 印が付かないことを意味する。表示する文言は [HomeRows.firstLoginNotice]。
         */
        val needsFirstLogin: Boolean = false,
    ) : HomeRow

    data object AddProfile : HomeRow
}

/**
 * この行を一意に識別する安定したキー。[HomeRow.SectionHeader] はフォーカス対象では
 * ないため null。
 *
 * 裁定84（fix8）: 削除確認オーバーレイ（[ConfirmDialog]）を閉じたあと、
 * `HomeScreen` がどの行へフォーカスを戻すかを決めるために使う。`rows` は
 * バッジの変化などで作り直される（毎回新しい `List<HomeRow>` インスタンスに
 * なる）が、uuid/groupId は再生成されない安定した ID なので、同じ行なら
 * 作り直されたあとも同じキーを返す。`HomeScreen` はこのキーで
 * `FocusRequester` をキャッシュして引き当てる。
 */
fun HomeRow.focusKey(): String? = when (this) {
    is HomeRow.GroupRow -> "group:$groupId"
    is HomeRow.ProfileRow -> "profile:$uuid"
    HomeRow.AddProfile -> "add"
    is HomeRow.SectionHeader -> null
}

/**
 * 一覧画面に出す行を組み立てる純粋ロジック。
 * Compose 側はこの結果を描くだけにして、表示の判断をここに集約する。
 */
object HomeRows {

    fun build(
        groups: List<FailoverGroup>,
        profiles: List<ProfileSummary>,
        state: FailoverState,
        /**
         * その接続先（引数は uuid）が初回ログインを終えていないか。
         * 実体は `ProfileRepository.needsFirstLogin`。既定 `{ false }` は
         * 「どの行にも印を付けない」を意味する。
         */
        needsFirstLogin: (String) -> Boolean = { false },
    ): List<HomeRow> {
        val rows = mutableListOf<HomeRow>()

        groups.forEach { group ->
            rows += HomeRow.GroupRow(
                groupId = group.id,
                name = group.name,
                memberCount = group.memberUuids.size,
                autoFailoverEnabled = group.autoFailoverEnabled,
                badge = badgeFor(group.id, state),
                memberName = memberName(group, profiles, state),
            )
        }

        if (groups.isNotEmpty() && profiles.isNotEmpty()) {
            rows += HomeRow.SectionHeader("個別の接続先")
        }

        profiles.forEach { profile ->
            rows += HomeRow.ProfileRow(
                uuid = profile.uuid,
                name = profile.name,
                serverAddress = profile.serverAddress,
                needsFirstLogin = needsFirstLogin(profile.uuid),
            )
        }

        rows += HomeRow.AddProfile
        return rows
    }

    /**
     * [state] が対象グループ [groupId] のものでなければ常に [ConnectionBadge.None]。
     * [FailoverState] の6ケースすべてを網羅し、将来ケースが増えたときに
     * コンパイルエラーで気づけるよう `else` は使わない。
     */
    private fun badgeFor(groupId: String, state: FailoverState): ConnectionBadge = when (state) {
        FailoverState.Idle -> ConnectionBadge.None
        is FailoverState.Connecting ->
            if (state.groupId == groupId) ConnectionBadge.Connecting else ConnectionBadge.None
        is FailoverState.Verifying ->
            if (state.groupId == groupId) ConnectionBadge.Verifying else ConnectionBadge.None
        is FailoverState.Healthy ->
            if (state.groupId == groupId) ConnectionBadge.Connected else ConnectionBadge.None
        is FailoverState.FailingOver ->
            // 現在の候補の切断完了を待っている最中で、次候補への接続はまだ
            // 成立していない。ユーザー視点では「まだつながろうとしている」
            // ので Connecting 扱いにする。
            if (state.groupId == groupId) ConnectionBadge.Connecting else ConnectionBadge.None
        is FailoverState.Exhausted ->
            if (state.groupId == groupId) ConnectionBadge.Retrying else ConnectionBadge.None
    }

    /**
     * この行のグループで、いま対象になっている接続先の表示名。
     * **接続済みを意味しない**（[FailoverState.Connecting] はダイヤル中でトンネル
     * 未確立でも候補名を返す。接続済みかどうかは [badgeFor] が示す）。
     * [FailoverState.FailingOver] の `failedIndex` は見限られつつある候補を指す
     * だけで、次の候補への接続はまだ成立していないため null を返す。
     * こちらも6ケースを網羅し `else` は使わない。
     */
    private fun memberName(
        group: FailoverGroup,
        profiles: List<ProfileSummary>,
        state: FailoverState,
    ): String? {
        val index: Int? = when (state) {
            FailoverState.Idle -> null
            is FailoverState.Connecting ->
                if (state.groupId == group.id) state.candidateIndex else null
            is FailoverState.Verifying ->
                if (state.groupId == group.id) state.candidateIndex else null
            is FailoverState.Healthy ->
                if (state.groupId == group.id) state.candidateIndex else null
            is FailoverState.FailingOver -> null
            is FailoverState.Exhausted -> null
        }

        val uuid = index?.let { group.memberUuids.getOrNull(it) } ?: return null
        return profiles.firstOrNull { it.uuid == uuid }?.name
    }

    /**
     * 接続先の削除確認に添える警告文。
     *
     * [GroupStore.loadGroups] は既知のプロファイル UUID に無いメンバーを
     * **読み込みのたびに**ふるい落とし、メンバーが0件になったグループ自体も
     * 落とす。ユーザーはその挙動を知らないので、削除前にどのグループへ
     * 影響するかをここで案内する。対象がどのグループにも属していなければ null。
     *
     * 裁定86（H1）: 以前ここには「削除後にグループが存在しない接続先を指したまま
     * になることはない」と書いてあったが、それは**この画面が読み直した写しに
     * ついてだけ**真であり、稼働中の `FailoverService` が持つ写しには当てはまら
     * なかった。エンジン側が読み直すのは既定 `SharedPreferences` の
     * [GroupStore.isReloadTriggerKey] に該当するキーが変わったときだけで、
     * `ProfileManager.delete()` はそのどれにも書かないためである
     * （接続中の接続先を削除してもトンネルが `Healthy` のまま残っていた）。
     * 現在は [ProfileRepository.delete] が [GroupStore.bumpProfileGeneration] を
     * 進め、その既存の再読込経路を通してエンジン側の一覧にも同じふるい落としが
     * 届く（そこから先の切替は裁定72a の経路に合流する）。
     */
    fun groupDeletionWarning(uuid: String, groups: List<FailoverGroup>): String? {
        val affected = groups.filter { uuid in it.memberUuids }
        if (affected.isEmpty()) return null

        val parts = affected.map { group ->
            if (group.memberUuids.size == 1) {
                "「${group.name}」（削除するとグループごと消えます）"
            } else {
                "「${group.name}」"
            }
        }
        return "この接続先は次のグループに含まれています: ${parts.joinToString("、")}。" +
            "削除すると自動的にグループから外れます。"
    }

    /**
     * 裁定65（指摘8）: [build] の `memberName` は、UI 側が読んだ [rows] 生成時点の
     * グループ定義（`memberUuids` の並び）に対して `candidateIndex` を当てはめて
     * 解決している。一方、状態機械（[FailoverStateHolder.activeGroup]）が実際に
     * 使っているグループ定義は `FailoverService` が保持する `groups` から取った
     * ものである。裁定72-fix(F4): この `groups` はもう `onCreate` 時点で固定では
     * なく `FailoverService.reloadGroupsAndProbeTarget` がその都度読み直すが、
     * それでも UI 側が [rows] を作った時点の読み直しとは別のタイミングの
     * スナップショットであることに変わりはなく、両者の世代がずれる余地は残る
     * （UI 側の再読込と、サービス側の SharedPreferences リスナ経由の再読込は
     * 別々の契機で走る）。ずれたまま同じ index を当てはめると、「実際に
     * 繋いでいるのとは別の候補」を確信ありげに表示してしまう
     * （[HomeRow.GroupRow.memberName] の契約が壊れる）。
     *
     * [engineGroup] は [FailoverStateHolder.activeGroup] からそのまま渡す。対象
     * グループの `memberUuids` が [uiGroups] 側の同じ ID のグループと一致しない
     * 場合だけ、その行の `memberName` を null にする（誤った名前を出すくらいなら
     * 何も出さない）。一致していれば [rows] をそのまま返す。
     */
    fun withTrustworthyMemberNames(
        rows: List<HomeRow>,
        uiGroups: List<FailoverGroup>,
        engineGroup: FailoverGroup?,
    ): List<HomeRow> {
        if (engineGroup == null) return rows
        val uiGroup = uiGroups.firstOrNull { it.id == engineGroup.id } ?: return rows
        if (uiGroup.memberUuids == engineGroup.memberUuids) return rows

        return rows.map { row ->
            if (row is HomeRow.GroupRow && row.groupId == engineGroup.id) {
                row.copy(memberName = null)
            } else {
                row
            }
        }
    }

    /**
     * 初回ログインが未完了の接続先の行に添える注記。終えていれば null（何も出さない）。
     *
     * 自動切替はこの接続先を候補にしない（認証情報が保存されていないので人が
     * 居なければ絶対に成功せず、無人で起動すると裁定30 の10秒で見捨てられて
     * 認証ダイアログごと消える）。**理由の分からない自動挙動を作らない**という
     * 既存の方針（仕様書5）に従い、飛ばされていること自体を利用者に見せる。
     *
     * **状態だけでなく解き方も書く。** 利用者の明示操作による接続は必ず
     * `fromIndex = 0`（グループの先頭メンバー）から始まり、個別の接続先だけを
     * 単体で接続する操作は TV UI に無いので、**この接続先がグループの先頭でないと
     * 有人の接続でもそこへ到達しない**。「飛ばされています」だけを出すと、
     * 利用者には打つ手が分からない。`GroupEditScreen` の ▲ で先頭へ動かせば
     * 接続できることまで書く（README の「仕組み」と同じ案内）。
     *
     * 文言の組み立ては [groupDeletionWarning] と同じく純関数にしてテストで固定
     * してある（Compose 側はこの結果を描くだけにする）。
     */
    fun firstLoginNotice(needsFirstLogin: Boolean): String? =
        if (needsFirstLogin) {
            "初回ログインが必要 / 自動切替では選ばれません。" +
                "グループ設定の ▲ で先頭にしてから接続するとログインできます"
        } else {
            null
        }

    /**
     * 初回ログインの判定（`ProfileRepository.needsFirstLogin`）を**引き直してよい
     * 節目**を表す鍵。画面側はこの値を `remember` のキーに使い、
     * [FailoverState] そのものはキーにしない。
     *
     * なぜ必要か: `rows` の `remember` は [FailoverState] をキーに含む（バッジと
     * 候補名がそれで決まる）。しかし [FailoverState.Healthy] は `lastProbeAtMs` を
     * 持ち、疎通確認ごと（既定30秒）に値が変わる。判定は `SharedPreferences` の
     * 読み直し（`ProfileManager.init` による `shared_prefs` の列挙 × プロファイル数）を
     * 伴うので、そのままでは**接続中にホームを開いているあいだ30秒ごとに、
     * メインスレッドでディスクを走査し続ける**（裁定16 によりこの経路はメイン
     * スレッドに固定されているので、逃がすのではなく回数を減らす）。
     *
     * そこで**時刻と失敗回数を鍵から外し、接続の節目だけを残す**:
     * 状態の種類・対象グループ・候補インデックス（`Exhausted` は再試行回数）。
     *
     * 初回ログインが通った直後に注記が消えること（アプリの再起動が要らないこと）は
     * これで保たれる。認証が通ると候補は `Connecting` →（`Connected` の観測で）
     * `Verifying` → `Healthy` と進み、**状態の種類が変わるので鍵が変わる**。
     * 認証後に落ちた場合も次候補の `Connecting`（`candidateIndex` が違う）か
     * `FailingOver` / `Exhausted` へ移るので、やはり鍵が変わる。
     * 加えて画面遷移（接続先やグループの編集）はこの画面をコンポジションから
     * 外すため、戻ってきた時点で `remember` ごと作り直される。
     *
     * [FailoverState] の6ケースすべてを網羅し、将来ケースが増えたときに
     * コンパイルエラーで気づけるよう `else` は使わない。
     */
    fun firstLoginRecheckKey(state: FailoverState): String = when (state) {
        FailoverState.Idle -> "idle"
        is FailoverState.Connecting -> "connecting:${state.groupId}:${state.candidateIndex}"
        is FailoverState.Verifying -> "verifying:${state.groupId}:${state.candidateIndex}"
        is FailoverState.Healthy -> "healthy:${state.groupId}:${state.candidateIndex}"
        is FailoverState.FailingOver -> "failingOver:${state.groupId}:${state.failedIndex}"
        is FailoverState.Exhausted -> "exhausted:${state.groupId}:${state.attempt}"
    }

    /**
     * 裁定84（fix8）: 削除確認オーバーレイで削除を実行したあと、フォーカスを
     * どの行に戻すかを決める純粋ロジック。
     *
     * 対象行そのものは一覧から消えるので、「削除前の並びでの位置（index）」を
     * 「削除後の並び」でも同じ index に当てはめ直す。つまり「詰めたあとに
     * 同じ場所へ来た行」を選ぶ。一覧の途中にフォーカスがあった状態で削除すると、
     * 直後に呼び出し側がここへ戻す（いきなり一覧の先頭へ飛ばされることはない）。
     *
     * - 末尾の行を削除した場合は index が配列外になるので、削除後の一覧の
     *   末尾（多くの場合 [HomeRow.AddProfile]）へ丸める。
     * - [deletedRowKey] が [focusableKeysBeforeDelete] に見当たらない（通常
     *   起こらない防御的分岐）場合は一覧の先頭に丸める。
     * - [focusableKeysAfterDelete] が空になることは [build] が必ず
     *   [HomeRow.AddProfile] を末尾に足すため実際には起きないが、その場合は
     *   戻す先が無いので null を返す。
     */
    fun targetKeyAfterConfirmedDelete(
        focusableKeysBeforeDelete: List<String>,
        deletedRowKey: String,
        focusableKeysAfterDelete: List<String>,
    ): String? {
        if (focusableKeysAfterDelete.isEmpty()) return null
        val originalIndex = focusableKeysBeforeDelete.indexOf(deletedRowKey)
        val index = if (originalIndex < 0) 0 else originalIndex
        return focusableKeysAfterDelete[index.coerceIn(0, focusableKeysAfterDelete.lastIndex)]
    }
}
