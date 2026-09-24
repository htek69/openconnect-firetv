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
     * [GroupStore.loadGroups] は既知のプロファイル UUID に無いメンバーを毎回
     * 自動的にふるい落とし、メンバーが0件になったグループ自体も落とすため、
     * 削除後にグループが存在しない接続先を指したままになることはない。
     * ただしその挙動をユーザーは知らないので、削除前にどのグループへ
     * 影響するかをここで案内する。対象がどのグループにも属していなければ null。
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
}
