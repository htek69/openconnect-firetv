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
        /**
         * [needsFirstLogin] が true で、かつこの接続先が少なくとも1つのグループに
         * 属しているとき、**初回ログインをどのグループ経由で行うか**。
         * どちらかが成り立たなければ null。
         *
         * 裁定（このラウンド）: 属するグループが複数ある場合は**グループ一覧の順で
         * 最初に見つかったもの**を使う。決定的であり、利用者に選ばせる画面を
         * 増やさない。初回ログインの目的（認証情報を1度保存する）はどのグループ
         * 経由でも達成される。
         *
         * null が「行の操作も注記も出さない」を意味する。グループに属していない
         * 接続先は自動切替の対象でもないので飛ばされることがなく、初回ログインを
         * 急ぐ理由が無い（そこに「初回ログインが必要」と出すのは意味の無い警告に
         * なる）。文言の組み立ては [HomeRows.firstLoginNotice] /
         * [HomeRows.firstLoginActionLabel] / [HomeRows.firstLoginConfirmation]。
         */
        val firstLoginGroup: FirstLoginGroup? = null,
        /**
         * この接続先で、**利用者が確認で始めた初回ログインの試行がまだ進行中か**。
         *
         * true の間は行の操作（[HomeRows.firstLoginActionLabel]）を出さず、注記を
         * 進行中の文言に差し替える。理由: [needsFirstLogin] の判定は
         * [HomeRows.firstLoginRecheckKey] の節目でしか引き直さないので、確認の直後
         * ——認証ダイアログが出て利用者が入力している最中——も「初回ログインが
         * 必要」のままである。そこで操作を押せる状態に残すと、もう一度押した接続
         * 指示が裁定95 の `cancelActiveDialog()` を通して**入力中の認証ダイアログを
         * 畳む**。それはこのブランチ全体が直した症状（パスワード画面が消える）
         * そのものであり、TV には押した手応えが無いので利用者は実際に押す。
         *
         * この印は**確認そのもので立てる**（状態の到着を待たない。その隙間が
         * 欠陥の原因だから）。消えるのは試行が決着したときで、成功したときだけで
         * なく**失敗したときも消える**（[HomeRows.firstLoginAttemptInFlight]）。
         * 成功時にしか消えない印は欠陥より悪い（retry する手段が無くなる）。
         *
         * 画面側だけの印なので、[needsFirstLogin] の判定を引き直す回数は増えない。
         */
        val firstLoginInProgress: Boolean = false,
    ) : HomeRow

    data object AddProfile : HomeRow
}

/**
 * 初回ログインを行う経路になるグループ（[HomeRow.ProfileRow.firstLoginGroup]）。
 * [id] は接続の指示に使い（`FailoverService.connectGroup`）、[name] は確認の
 * 文面に出す（どのグループが繋ぎ直されるのかを利用者に見せるため）。
 */
data class FirstLoginGroup(val id: String, val name: String)

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
        /**
         * 利用者が確認で始めた初回ログインの試行が進行中の接続先の uuid
         * （[HomeRow.ProfileRow.firstLoginInProgress]）。既定 null は
         * 「進行中のものは無い」を意味する。**判定の供給関数ではない**ので、
         * これを渡しても `prefs` の読み直しは1回も増えない。
         */
        firstLoginInProgressUuid: String? = null,
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
            val needsLogin = needsFirstLogin(profile.uuid)
            rows += HomeRow.ProfileRow(
                uuid = profile.uuid,
                name = profile.name,
                serverAddress = profile.serverAddress,
                needsFirstLogin = needsLogin,
                // 初回ログインが必要なときだけ経路を決める。判定は
                // [needsFirstLogin] の1回の呼び出しを使い回し、prefs の
                // 読み直しを増やさない（呼び出し側の remember の鍵は
                // [firstLoginRecheckKey]）。
                firstLoginGroup = if (needsLogin) firstLoginGroupFor(profile.uuid, groups) else null,
                firstLoginInProgress = profile.uuid == firstLoginInProgressUuid,
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
     * [uuid] の接続先が属するグループのうち、**グループ一覧 [groups] の順で最初に
     * 見つかったもの**（[HomeRow.ProfileRow.firstLoginGroup] の裁定）。
     * どのグループにも属していなければ null。
     */
    private fun firstLoginGroupFor(uuid: String, groups: List<FailoverGroup>): FirstLoginGroup? =
        groups.firstOrNull { uuid in it.memberUuids }
            ?.let { FirstLoginGroup(it.id, it.name) }

    /**
     * 初回ログインが未完了の接続先の行に添える注記。終えていれば null（何も出さない）。
     *
     * 自動切替はこの接続先を候補にしない（認証情報が保存されていないので人が
     * 居なければ絶対に成功せず、無人で起動すると裁定30 の10秒で見捨てられて
     * 認証ダイアログごと消える）。**理由の分からない自動挙動を作らない**という
     * 既存の方針（仕様書5）に従い、飛ばされていること自体を利用者に見せる。
     *
     * **解き方は文ではなく操作そのもので示す。** 以前ここには「グループ設定の ▲ で
     * 先頭へ動かしてから接続する」（回避策）と書いてあり、次に「右（→）の
     * 「初回ログイン」で……」と書いていた。前者は操作が無かった時代の案内であり、
     * 後者は**同じ行にその操作が見えている以上、字数を使う価値が無い**。
     *
     * 短くすること自体が安全策でもある（指摘1・レビュー2）: この注記は行の本体
     * カードの中にあり、長いほど本体カードの要求幅が伸びる。`ProfileCard` は
     * 本体カードを `weight(1f)` 側に置いて操作カードの幅を保証しているが、
     * 注記を短く保てば、フォントスケールやオーバースキャンが不利に振れたときの
     * 余裕そのものが大きくなる。解き方は隣のカード
     * （[firstLoginActionLabel] ＋ `決定で接続`）が示す。
     *
     * [HomeRow.ProfileRow.firstLoginGroup] が null（どのグループにも属していない）
     * なら null を返す。自動切替の対象でないので飛ばされることもなく、
     * 行に出せる操作も無いため、注記は意味の無い警告になる。
     *
     * 文言の組み立ては [groupDeletionWarning] と同じく純関数にしてテストで固定
     * してある（Compose 側はこの結果を描くだけにする）。
     *
     * [HomeRow.ProfileRow.firstLoginInProgress] のときは**進行中の文言に差し替える**。
     * 操作は消えている（[firstLoginActionLabel] が null）ので、「押しても何も
     * 起きない操作」を出しっぱなしにするのではなく、いま何が起きているのかと
     * **もう一度押す必要が無いこと**を書く（TV には押した手応えが無いため、
     * 書かないと利用者は押し直す）。
     */
    fun firstLoginNotice(row: HomeRow.ProfileRow): String? = when {
        !firstLoginApplies(row) -> null
        row.firstLoginInProgress -> "初回ログイン中 / 認証画面で入力してください（押し直し不要）"
        else -> "初回ログインが必要 / 自動切替では選ばれません"
    }

    /**
     * 行に出す「初回ログイン」の操作のラベル。出さないときは null
     * （[firstLoginNotice] と同じ条件で現れ、同じ条件で消える）。
     *
     * D-pad での操作は同じ行の「編集」「削除」と同じ作法に合わせてある:
     * この操作は**決定**（`Card` の `onClick`）で、既存の長押し（削除）と
     * 衝突しない。カードを1枚右に並べるので、行から **→** で移動して決定する
     * （`GroupEditScreen` のメンバー行の ▲/▼/名前と同じ並べ方であり、
     * 新しい入力の作法を増やしていない）。長押しでは発火しないため、裁定78/80 の
     * 孤児 UP（`LongPressKeyUpFilter` が捨てるもの）はこの経路では起きない。
     *
     * **進行中（[HomeRow.ProfileRow.firstLoginInProgress]）は null。** カードごと
     * 消えるので、確認した直後から二度押しができない
     * （[HomeRow.ProfileRow.firstLoginInProgress] の KDoc の欠陥）。カードが消えても
     * フォーカスは行の本体のカードへ戻してあるので（`HomeScreen` の
     * `FocusRestoreRequest.ToRow`）、フォーカスの行き先が無くなることはない。
     */
    fun firstLoginActionLabel(row: HomeRow.ProfileRow): String? =
        if (firstLoginApplies(row) && !row.firstLoginInProgress) "初回ログイン" else null

    /**
     * 「初回ログイン」を実行する前に出す確認の文面（[ConfirmDialog]。既存の削除の
     * 確認と同じ作法）。出さないときは null。
     *
     * **何が起きるかをはっきり書く。** この操作は
     * `FailoverEvent.UserConnectGroup`（有人の接続）であり、既存の有人接続と同じく
     * **いま張っているトンネルを切ってから**指定した接続先へ繋ぎ直す。
     * どのグループが繋ぎ直されるのかも利用者から見えるよう、経路になるグループの
     * 名前（[HomeRow.ProfileRow.firstLoginGroup]）を文面に出す。
     */
    fun firstLoginConfirmation(row: HomeRow.ProfileRow): String? {
        val group = row.firstLoginGroup ?: return null
        if (!row.needsFirstLogin || row.firstLoginInProgress) return null
        return "「${row.name}」で初回ログインを行いますか？\n\n" +
            "いまの VPN 接続を切って、グループ「${group.name}」の「${row.name}」へ繋ぎ直します。" +
            "認証画面が出たらユーザー名とパスワードを入力してください" +
            "（「パスワードを保存」にチェックを入れると、次回からは自動で接続できます）。"
    }

    /**
     * その行に初回ログインの注記と操作を出すか。**初回ログインが未完了で、かつ
     * 少なくとも1つのグループに属している**ときだけ true
     * （[HomeRow.ProfileRow.firstLoginGroup] の KDoc）。
     */
    private fun firstLoginApplies(row: HomeRow.ProfileRow): Boolean =
        row.needsFirstLogin && row.firstLoginGroup != null

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
     * 確認で始めた初回ログインの試行が**まだ進行中か**（`HomeScreen` の
     * `FirstLoginAttempt`。true の間だけ [HomeRow.ProfileRow.firstLoginInProgress] を
     * 立てる）。
     *
     * 立てる契機は確認そのものであり、ここは**降ろす契機だけ**を決める。
     * 進行中と見なすのは次の2つのどちらかである:
     *
     * 1. [startedAtRecheckKey]（確認した時点の [firstLoginRecheckKey]）と現在の鍵が
     *    同じ。まだ接続の節目が1つも動いていない＝指示が状態機械へ届いて
     *    `Connecting` が publish されるまでの隙間。**この隙間を進行中に含めることが
     *    この関数の目的である**（含めないと、隙間のあいだ操作が押せてしまう）。
     * 2. 状態が [attemptGroupId] のグループの [FailoverState.Connecting]。
     *    これが認証ダイアログが出ている窓である（既存コアの `UserPrompt` は
     *    [FailoverState] を変えず、裁定94 により人が答えているあいだ
     *    `Connecting` のまま留まる）。
     *
     * それ以外は**決着した**とみなして降ろす:
     *
     * - [FailoverState.Verifying] / [FailoverState.Healthy]: 認証を通った
     *   （成功。この場合はそもそも次の再判定で `needsFirstLogin` が false になり
     *   注記も操作も消える）。
     * - [FailoverState.FailingOver] / [FailoverState.Exhausted] /
     *   [FailoverState.Idle] / 別グループ: **失敗・中断**。ここで降ろすので、
     *   利用者は操作をもう一度押して再試行できる。**成功したときだけ降ろす印には
     *   していない**（それでは失敗したあとに手段が無くなり、元の欠陥より悪い）。
     *
     * 同じグループの `Connecting` が続く限り（自分の候補が落ちて次の候補が
     * 起動した場合など）は進行中のままだが、`Connecting` は Ruling 22 の45秒で
     * 必ず打ち切られ、候補の切替は [FailoverState.FailingOver] を経るので
     * そこで降りる。したがって**印が無期限に残ることはない**。
     * 指示がどこにも届かなかった場合（画面のグループ一覧が古く、
     * `FailoverService` が添字を解決できずに何も dispatch しなかった場合）だけは
     * 鍵が動かないので 1. のまま残る。それは `HomeScreen` 側の
     * `FIRST_LOGIN_STALL_MS` の取り下げが回収する。
     */
    fun firstLoginAttemptInFlight(
        attemptGroupId: String,
        startedAtRecheckKey: String,
        state: FailoverState,
    ): Boolean =
        firstLoginRecheckKey(state) == startedAtRecheckKey ||
            (state is FailoverState.Connecting && state.groupId == attemptGroupId)

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
