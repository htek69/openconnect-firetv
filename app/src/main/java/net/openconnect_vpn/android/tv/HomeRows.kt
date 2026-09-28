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
         * なく**失敗したときも消える**（[HomeRows.firstLoginAttemptProgress]。
         * 自分のダイヤルを観測する前と後で「決着」の意味が変わるので2段になっている）。
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
 * 確認で始めた初回ログインの試行の進み具合（[HomeRows.firstLoginAttemptProgress]）。
 *
 * [WaitingToStart] と [Dialing] を分けているのは、**自分のダイヤルを観測する前と
 * 後で「決着」の意味が変わる**からである（観測前に届く
 * [FailoverState.FailingOver] は Ruling 25 の2段階切替の1枚目であって決着ではない。
 * 詳細は [HomeRows.firstLoginAttemptProgress] の KDoc）。
 */
enum class FirstLoginAttemptProgress {
    /** 指示は出したが、自分のダイヤル（そのグループの `Connecting`）はまだ観測していない。 */
    WaitingToStart,

    /** 自分のダイヤルが進行中（認証ダイアログが出ている窓もここに入る）。 */
    Dialing,

    /** 決着した（成功・失敗・中断・横取りのいずれでも）。印を降ろす。 */
    Ended,
    ;

    /** 印（[HomeRow.ProfileRow.firstLoginInProgress]）を立てておくべきか。 */
    val inFlight: Boolean get() = this != Ended
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
     *
     * **短く言う**（指摘7・レビュー2）。[ConfirmDialog] の本文は
     * `fillMaxWidth(0.6f)` の `Column` に置かれ、`verticalScroll` を持たない。
     * 長いと低解像度・大フォント設定の端末で**ボタン行が下にはみ出す**ので、
     * この画面のどの確認よりも長い文面にしてはならない。削ったのは
     * 「ユーザー名とパスワード」「『パスワードを保存』にチェック」という手順の
     * 説明であって、**落としてはならない2点——いまの接続が切れること・どのグループか
     * ——は残してある**（手順は認証画面そのものと README・`docs/MANUAL-TEST.md` が
     * 案内する）。上限はテストで固定した。
     */
    fun firstLoginConfirmation(row: HomeRow.ProfileRow): String? {
        val group = row.firstLoginGroup ?: return null
        if (!row.needsFirstLogin || row.firstLoginInProgress) return null
        return "「${row.name}」で初回ログインしますか？\n\n" +
            "いまの VPN 接続を切り、グループ「${group.name}」経由で繋ぎ直します。" +
            "認証画面が出たら入力してください。"
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
     * 確認で始めた初回ログインの試行の進み具合（`HomeScreen` の `FirstLoginAttempt`）。
     * [FirstLoginAttemptProgress.Ended] 以外のあいだだけ
     * [HomeRow.ProfileRow.firstLoginInProgress] を立てる。
     *
     * 立てる契機は確認そのもので、ここは**進み方と降ろす契機だけ**を決める。
     *
     * ### なぜ2段なのか（レビュー2・指摘2 の欠陥）
     *
     * 前の版は「`Connecting`（そのグループ）か、鍵が確認時点と同じなら進行中」
     * という1段の条件だった。ところが**利用者がいま VPN に繋がっている状態から
     * この操作を実行すると、状態機械が最初に publish するのは必ず
     * [FailoverState.FailingOver] である**（Ruling 25 の2段階切替。
     * `FailoverController.stopBeforeStarting` の `Connecting`/`Verifying`/`Healthy`
     * 枝）。`FailingOver` は「決着」に分類されていたので、**確認の文面が想定して
     * いる状況——いまの接続を切って繋ぎ直す——でだけ印が即座に降り**、切断確認から
     * 認証ダイアログまでの数秒〜数十秒のあいだ操作が押せたままだった。
     * つまり閉じたはずの二度押しの窓が、最も普通の状態からは閉じていなかった。
     *
     * そこで**自分のダイヤルを観測したか**（[sawConnecting]）で2段に分ける:
     *
     * - **1段目（[sawConnecting] が false）**: まだ自分のダイヤルを見ていない。
     *   `FailingOver`（切断待ち）と、確認時点と同じ鍵（まだ何も publish されて
     *   いない）は [FirstLoginAttemptProgress.WaitingToStart] のまま進行中に数える。
     *   [attemptGroupId] の `Connecting` を観測したら
     *   [FirstLoginAttemptProgress.Dialing] へ進む（呼び出し側が [sawConnecting] を
     *   立てる）。
     * - **2段目（[sawConnecting] が true）**: 観測したダイヤルが続いているあいだ
     *   （[attemptGroupId] の `Connecting`）は [FirstLoginAttemptProgress.Dialing]。
     *   そこから外れたら [FirstLoginAttemptProgress.Ended]——**成功でも失敗でも
     *   降りる**（`Verifying`/`Healthy` は認証を通った、
     *   `FailingOver`/`Exhausted`/`Idle` は失敗・中断。成功でしか降りない印は
     *   失敗後に再試行の手段を奪うので元の欠陥より悪い）。
     *
     * 認証ダイアログが出ている窓が `Dialing` に収まるのは、既存コアの `UserPrompt` が
     * [FailoverState] を変えないためである（`FailoverController.onVpnState` に
     * `UserPrompt` の分岐は無く `else -> s` に落ちる。人が答えたときの
     * `startedAtMs` の置き直し（裁定94）も [firstLoginRecheckKey] は時刻を鍵に
     * 入れていないので鍵を動かさない）。
     *
     * ### 1段目で降ろす2つの場合
     *
     * どちらも**確認時点の鍵と同じあいだは降ろさない**（上の鍵一致の分岐が先に
     * 評価される）。別グループや `Exhausted` に繋いだまま／留まったままこの操作を
     * 実行した場合、確認の直後にその同じ状態が（疎通確認やバックオフの再評価で）
     * もう一度 publish されることがあり、それを「終わった」と読むと**自分の
     * ダイヤルが始まる前に印が消える**（＝二度押しの窓が戻る）。
     *
     * 1. **`Idle` または `Exhausted` に変わった**（＝鍵が確認時点から動いている）。
     *    どちらも「生きている候補が1つも無い」状態であり、
     *    [FailoverState.FailingOver] のような「これから起動する」含意を持たない。
     *    自分の指示が**実際に終わってしまった**経路がここに落ちる（レビュー3 の
     *    指摘。いずれも時計なしで観測できる）:
     *    - 切断待ちのあと `advanceAfterFailingOver` が範囲外の保留を拒否して
     *      `Idle` を返した（`FailoverController` の該当分岐）
     *    - `vpn.connect()` がプロファイルを見つけられず全候補が起動に失敗して
     *      `Exhausted` に入った
     *    - `startService` が失敗した等で状態機械が指示を受け取れなかった
     *    - 裁定44 の消灯で `UserDisconnect` → `Idle` になった
     *    ここで降ろさないと、**何も進んでいないのに**行は
     *    「初回ログイン中 …（押し直し不要）」と嘘をつき続ける（画面を離れるまで）。
     *    嘘をやめるほうが安全側である: `Idle`/`Exhausted` では生きている候補が
     *    無いので、利用者が操作を押し直しても畳まれる認証ダイアログが存在しない。
     * 2. **別のグループの生きた候補**（[attemptGroupId] 以外の
     *    `Connecting`/`Verifying`/`Healthy`）が publish された。自分の指示は
     *    横取りされており、待ち続けても始まらないので降ろす（利用者は押し直せる）。
     *
     * **時計は使わない。** 1段目に留まるのは「切断待ち（`FailingOver`）」と
     * 「まだ何も publish されていない」の2つだけで、前者は状態機械が必ず次へ進め、
     * 後者は次の publish で解ける。
     *
     * ### この判定では気づけないこと（旧版の注意書きの更新。消さないこと）
     *
     * 以前ここには「VPN 許可が失われていた場合は `Idle` のまま残るが、許可を
     * 取り直すには画面を離れるしかなく、離れればこの印ごと消える」と書いてあった。
     * その注意書きは**上の `Idle`/`Exhausted` の分岐を足したときに一度消して
     * しまったが、消したことで限界が無くなったわけではない**ので、いま真である形に
     * 直して残す（レビュー4 の指摘。既知の限界を記録したコメントは証拠であり、
     * 限界が実際に消えていないなら消してはならない）。
     *
     * 真であること: [FailoverState.Idle] は引数を持たない `data object` なので
     * [firstLoginRecheckKey] は常に `"idle"` であり、**「実行前の `Idle`」と
     * 「起動できずに戻った `Idle`」をこの関数は区別できない**（同値なので
     * `StateFlow` が畳み、再コンポーズすら起きないことがある）。同じことは
     * 実行前がちょうど `Exhausted(G, 0)` だった場合にも起こる。
     * つまり**起動そのものが失敗した場合は、状態を見ていても気づけない。**
     *
     * だからそこは状態ではなく**実行の前**で塞いでいる: `HomeScreen` は決定の時点で
     * プロファイルとグループの一覧を読み直し、VPN 許可の有無も確かめて、
     * 起動が失敗する条件なら**指示せず理由を出す**（[firstLoginRefusal]。
     * 印をそもそも立てないので嘘も出ない）。
     *
     * それでも残る窓: 読み直しから `FailoverService` の dispatch までのあいだに
     * メンバーが外された・プロファイルが削除された・許可が失われた場合は、
     * 起動の失敗を画面から観測できない。その場合この印は1段目に留まり、画面を
     * 離れるまで消えない（データは壊れない。時計で誤魔化していない）。
     *
     * ### 据え置いた残件（コントローラの裁定。**消さないこと**）
     *
     * `context.startService(...)` が例外を投げて `ConnectResult.Failed` になる場合は、
     * **実行の前に知ることが原理的にできない**（投げるかどうかは呼んでみないと
     * 分からない。許可の有無やプロファイルの有無と違って事前に確かめられない）。
     * それが残り全部の候補で起き、かつ実行前の状態がちょうど
     * `Exhausted(G, 0)` だったときは、戻り先も `Exhausted(G, 0)` で鍵が動かず、
     * **この印はまた1段目に取り残される**（行は「初回ログイン中」と嘘をつく）。
     *
     * **直さないと決めた。** コントローラの裁定をそのまま記録する:
     *
     * - 塞ぐには (a) 時計を持ち込むか、(b) dispatch の結果を画面へ返す経路を
     *   足すかのどちらかしかない。(a) は裁定78 が禁じた形（生きているかの問いに
     *   時計で答えると、単に遅い人を切る）であり、(b) は計画が禁じている
     *   「新しいシグナリング経路」である。**どちらの禁止もこの欠陥より先に
     *   決まっており、稀な場合の見た目の嘘のために覆さない。**
     * - このアプリの API 25 にはバックグラウンド起動制限が無いので、
     *   `startService` が投げるのは実質 OOM のときだけである。
     * - 起きたときの影響: 行が「初回ログイン中」と表示されたままになる
     *   （画面を離れれば消える）。**データは失われず、二重にダイヤルもしない。**
     *
     * 将来これを本当に直すなら、特別な場合をもう1つ足すのではなく
     * **画面が dispatch の結果を状態から推測するのをやめる**こと——
     * すなわち「指示がどうなったか」を状態機械から画面へ返す経路を設ける
     * （＝計画の制約を変える）判断が要る。`Idle` が
     * `data object` であること（識別情報を意図的に持たないこと）がこの構造的な
     * 限界の根っこであり、これまでのラウンドはその特別な場合を順に潰してきた。
     * **同じ形の特別な場合を足すのは修正ではない。**
     */
    fun firstLoginAttemptProgress(
        attemptGroupId: String,
        sawConnecting: Boolean,
        startedAtRecheckKey: String,
        state: FailoverState,
    ): FirstLoginAttemptProgress = when {
        state is FailoverState.Connecting && state.groupId == attemptGroupId ->
            FirstLoginAttemptProgress.Dialing

        sawConnecting -> FirstLoginAttemptProgress.Ended

        firstLoginRecheckKey(state) == startedAtRecheckKey ->
            FirstLoginAttemptProgress.WaitingToStart

        state is FailoverState.Idle || state is FailoverState.Exhausted ->
            FirstLoginAttemptProgress.Ended

        liveGroupIdOf(state)?.let { it != attemptGroupId } == true ->
            FirstLoginAttemptProgress.Ended

        else -> FirstLoginAttemptProgress.WaitingToStart
    }

    /**
     * 「初回ログイン」を実行してよいかを、**実行の直前に読み直したグループ一覧**
     * [freshGroups] で確かめる。実行してよければ null、できなければ利用者に出す
     * 理由の文面を返す（`HomeScreen` の `statusMessage`。既存の仕組みをそのまま使い、
     * 新しい通知経路は作らない）。
     *
     * なぜ要るか（レビュー2・指摘4）: uuid → 添字の解決は `FailoverService` 側に
     * あり、指したメンバーがそのグループに見当たらなければ**何も dispatch せずに
     * 黙って捨てる**。画面はそれを知らないので、行は
     * 「初回ログイン中 …」と嘘を表示し続ける。行が描かれてから決定が押されるまでに
     * メンバーがグループから外れることは実際に起こる（別の画面や旧 UI での編集、
     * プロファイルの削除に伴う [GroupStore.loadGroups] のふるい落とし）。
     *
     * そこで**捨てられる前に画面側で気づく**: 決定の時点で一覧を読み直し、
     * その接続先がまだ [HomeRow.ProfileRow.firstLoginGroup] のグループに
     * 属しているかを確かめる。属していなければ理由を出して実行しない
     * （印も立てないので「進行中」の嘘も出ない）。時計で取り下げる必要も無くなる。
     *
     * ### なぜ `ConnectResult` の失敗もここで見るのか（レビュー4・high）
     *
     * `FailoverController` が候補の起動に失敗したとき、**publish される状態が
     * 実行前と区別できない場合がある**。[FailoverState.Idle] は引数を持たない
     * `data object` なので [firstLoginRecheckKey] は常に `"idle"` であり、
     * 「実行前の `Idle`」と「`ConnectResult.NeedsUserConsent` で起動できずに
     * 戻った `Idle`」は**同じ鍵・同じ値**になる（`StateFlow` は同値を畳むので
     * 再コンポーズさえ起きないことがある）。同じことは
     * `ConnectResult.Failed` が全候補で起こった場合にもありうる——
     * `onUserConnect` は `exhaustionAttempt` を 0 に戻すので、実行前が
     * ちょうど `Exhausted(G, 0)` だと戻り先も `Exhausted(G, 0)` で鍵が動かない。
     *
     * つまり**状態だけを見て気づくことは原理的にできない**（[firstLoginAttemptProgress]
     * の鍵一致の分岐が先に当たり、印が降りない）。時計で諦めるのは裁定78 の誤りに
     * 戻るだけなので、**起動が失敗する条件を実行の前に見て、そもそも指示しない**。
     * どちらも画面側から確かめられる:
     *
     * - `NeedsUserConsent`（`VpnController.needsUserConsent` が true のときに
     *   `connect` が返す値。判定はその port の実装にだけある）→ [vpnConsentMissing]。
     *   これは利用者に**伝えるべきこと**でもある（許可を取り直さないと、この操作に
     *   限らず一切繋がらない）。文面でそう言う。
     * - `Failed`（`ProfileManager.get(uuid)` が null＝プロファイルが消えている）
     *   → [profileExists]。旧 UI などで外部から削除されると、画面が持っている
     *   一覧にはまだ残っている。
     *
     * [profileExists] を読み直した一覧から渡すことは、[freshGroups] の精度も上げる:
     * 以前はこの関数に渡す [freshGroups] を**古い**プロファイル一覧で
     * [GroupStore.loadGroups] していたため、外部で削除されたプロファイルが
     * ふるい落とされずに「まだメンバーである」と読めてしまっていた
     * （報告書の裁定 G1 の訂正で挙げた穴）。
     *
     * 判定は5つ（データの食い違いを先に、許可を最後に見る。どちらも当てはまる
     * ときに「許可を取り直したのにまた失敗する」を避けるため）:
     * - もう初回ログインが要らない／経路が無い行（[firstLoginApplies] が false）:
     *   一覧が古いということなので、読み直しを促す文面を返す。
     * - プロファイル自体が消えている（[profileExists] が false）。
     * - そのグループ自体が無くなっている: グループごと消えた（最後のメンバーが
     *   削除された等）。
     * - グループはあるがメンバーから外れている: 一番起こりやすい場合。
     * - VPN の利用許可が無い（[vpnConsentMissing]）。
     */
    fun firstLoginRefusal(
        row: HomeRow.ProfileRow,
        freshGroups: List<FailoverGroup>,
        profileExists: Boolean = true,
        vpnConsentMissing: Boolean = false,
    ): String? {
        val group = row.firstLoginGroup
        if (group == null || !row.needsFirstLogin) {
            return "「${row.name}」の状態が変わりました。一覧を読み直します。"
        }
        if (!profileExists) {
            return "「${row.name}」は見つかりません。一覧を読み直します。"
        }
        val fresh = freshGroups.firstOrNull { it.id == group.id }
            ?: return "グループ「${group.name}」が見つかりません。一覧を読み直します。"
        if (row.uuid !in fresh.memberUuids) {
            return "「${row.name}」はグループ「${group.name}」から外れています。一覧を読み直します。"
        }
        if (vpnConsentMissing) {
            return "VPN の利用許可がありません。設定の「VPN の許可を取得する」で取り直してください。"
        }
        return null
    }

    /**
     * その状態で**生きている候補**があるなら、そのグループ ID。
     * `Connecting`/`Verifying`/`Healthy` は生きている。`FailingOver` は切断待ちで
     * （次の候補はまだ起動していないので）生きている候補として数えない。
     * `Idle`/`Exhausted` は何も起動していない。
     *
     * [FailoverState] の6ケースすべてを網羅し、将来ケースが増えたときに
     * コンパイルエラーで気づけるよう `else` は使わない。
     */
    private fun liveGroupIdOf(state: FailoverState): String? = when (state) {
        FailoverState.Idle -> null
        is FailoverState.Connecting -> state.groupId
        is FailoverState.Verifying -> state.groupId
        is FailoverState.Healthy -> state.groupId
        is FailoverState.FailingOver -> null
        is FailoverState.Exhausted -> null
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
