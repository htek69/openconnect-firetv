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
