package net.openconnect_vpn.android.failover

/**
 * フェイルオーバー状態機械（仕様書 7章）。
 *
 * Android API を一切参照しない。プラットフォームとのやり取りは
 * [Clock] / [VpnController] / [NetworkGate] の3つの境界だけを通す。
 * プローブの実行自体は呼び出し側（FailoverService）が [shouldProbeNow] を見て行い、
 * 結果を [FailoverEvent.ProbeResult] として戻す。
 */
class FailoverController(
    private val groupsProvider: () -> List<FailoverGroup>,
    private val clock: Clock,
    private val vpn: VpnController,
    private val network: NetworkGate,
    /**
     * 裁定93: いま認証ダイアログを描画できる（＝人が答えられる）状態か。
     * [groupsProvider]（裁定72）と同じ「参照のたびに呼ぶ供給関数」の形で受け取る。
     *
     * 実体は `OpenVpnService.mActivityConnections > 0` に相当する値で、
     * `FailoverService` が供給する。**ここから Android API は一切参照しない。**
     * 既定値が `{ false }` なのは、供給しない呼び出し元（既存のテスト）に対して
     * 裁定30 の従来の振る舞い＝「無人と見なして除外する」をそのまま残すため。
     *
     * 裁定94: この値は2か所で使う。裁定93 の除外判定
     * （[onUnattendedPromptTimeout]）と、接続タイムアウト（[onConnectTimeout]）を
     * 止める [awaitingHumanAuthInput] である。後者は [userPromptSinceMs] との
     * **論理積**でのみ成立する（片方だけでは足りない理由は
     * [awaitingHumanAuthInput] の KDoc）。
     */
    private val dialogHostAttachedProvider: () -> Boolean = { false },
    /**
     * そのメンバー（引数はメンバーの uuid）が初回ログインを終えていないか。
     * [groupsProvider]（裁定72）/ [dialogHostAttachedProvider]（裁定93）と同じ
     * 「参照のたびに呼ぶ供給関数」の形で受け取る。実体は
     * `ProfileRepository.needsFirstLogin`（プロファイル自身の prefs に
     * `FORMDATA-` で始まるキーが1つも無いか）で、`FailoverService` が供給する。
     * **ここから Android API は一切参照しない。** 真偽値だけを見る。
     *
     * true が意味するのは「認証情報が保存されていないので、人が居なければ
     * **絶対に**接続が成功しない」ことである。それを無人で起動すると、裁定30 の
     * `UserPrompt` 10秒で候補を見捨てて裁定95 の `cancelActiveDialog()` が
     * 認証ダイアログを畳むため、**利用者が答えようとしているダイアログを
     * 巡回のたびに消し続ける**（実機で8回連続の「取り消し」として観測された）。
     * よって [nextStartableIndex] は**無人で始めるときだけ**このメンバーを飛ばす。
     *
     * **利用者の明示操作（`unattended = false`）では飛ばさない。** 初回ログインは
     * その経路で行うのだから、そこで飛ばすと誰も登録できなくなる。
     * その経路が先頭メンバーにしか届かないという穴は
     * [FailoverEvent.UserConnectGroup.fromIndex] で埋めてある（一覧の行の
     * 「初回ログイン」から、その接続先の位置を指定して有人で開始する）。
     *
     * 飛ばすことは S1 の除外（[excludedUuids]）とは別物で、集合には何も足さない。
     * 供給関数なので、利用者が初回ログインを終えた次の候補選択からは false に
     * なり、そのメンバーは自動の巡回へ戻る。
     *
     * 既定値が `{ false }` なのは「従来どおり誰も飛ばさない」を意味する。
     * 供給しない呼び出し元（既存のテスト）の振る舞いは無改変で変わらない。
     */
    private val needsFirstLoginProvider: (String) -> Boolean = { false },
) {

    /**
     * 裁定48/72: グループ一覧を1回だけ受け取って保持するのをやめ、参照のたびに
     * [groupsProvider] を呼ぶ。これにより FailoverService が SharedPreferences の
     * 変更を検知して読み直した最新のグループ一覧が、コントローラを作り直さずに
     * （＝接続中の状態・除外集合・世代カウンタを失わずに）ここへ届く。
     * [groupOf] / [configOf] の呼び出し側はこれまでどおり同期的な参照のまま使える。
     */
    private val groups: List<FailoverGroup> get() = groupsProvider()

    var state: FailoverState = FailoverState.Idle
        private set

    /** 安全策 S1: 認証失敗でこのセッション中は試さない候補。 */
    private val _excludedUuids = mutableSetOf<String>()
    val excludedUuids: Set<String> get() = _excludedUuids

    /** VPN 許可が未取得で自動接続できなかったことを UI へ伝えるフラグ。 */
    var needsUserConsent: Boolean = false
        private set

    /** 安全策 S3: 自分が出した切断要求による Disconnected を障害扱いしないための印。 */
    private var expectingDisconnect = false

    /** 安全策 S1: 現在の候補が認証を通過したか。通過前の切断は認証失敗とみなす。 */
    private var currentCandidatePassedAuth = false

    /**
     * Ruling 23: 現在の候補が認証段階まで到達したか
     * （`Authenticating` = TLS接続成功かつサーバが認証フォームを返した、または
     * `UserPrompt` を観測した）。S1 は「認証段階に到達していながら通過しなかった」
     * 場合だけを認証失敗とみなす。到達すらしていない切断（`10.255.255.1` のような
     * 到達不能ホストなど）はネットワーク障害であり、S1 で除外すると健全な候補まで
     * 一時的な障害で焼き切ってしまう。
     */
    private var currentCandidateReachedAuth = false

    /** 安全策 S3: 現在の候補の UUID。Ruling 13 により切替前の古い通知を除外する。 */
    private var currentCandidateUuid: String? = null

    /**
     * 裁定31: 現在の候補の接続試行が既存コア側で始まったことを観測したか
     * （core の `STATE_CONNECTING`）。
     *
     * 既存コアの `OpenVpnService.onStartCommand` は `mUUID` を新しい候補のものへ
     * 書き換えてから古いスレッドを停止し（:259-274、join は最大1秒）、状態の
     * ブロードキャストはサービスの現在の `mUUID` を載せる（:378-385）。そのため
     * **1秒で終わらなかった旧スレッドの `Disconnected` に新しい候補の UUID が付く。**
     * Ruling 13 の UUID 照合では区別できない（実機で観測: 欠陥15）。
     *
     * 一方、新しい試行は必ず `runVPN()` の冒頭で `STATE_CONNECTING` を送る
     * （`OpenConnectManagementThread.java:695`）。よって自分の `Connecting` を
     * 観測する前の `Disconnected` は旧スレッドのものと判断して無視できる。
     *
     * 裁定37: このガードは `Connecting`/`FailingOver`（＝まだ接続が確立していない、
     * または確立済みトンネルの切断を待っている）状態にだけ適用する。`Verifying`/
     * `Healthy`（＝ `Connected` を既に観測した）候補の `Disconnected` は、この
     * フラグの値に関わらず確実に本人のものであり、捨てると本物の切断イベントに
     * よる即時切替（仕様書 §8 R5 系統1）が黙って死に、検知がプローブ（系統2）
     * 任せになる。
     *
     * 裁定86（L1）: このフラグが立つのは [startCandidateFrom]（＝候補の接続を
     * 開始した経路）で false に落としたあと、その候補の `Connecting` を実際に
     * 観測したときだけである。したがって
     * **`FailingOver` 中に必ず true だとは限らない。**
     * [stopBeforeStarting] が作る `FailingOver`（放棄候補の確認待ち、および
     * 生きている候補を止めてからの切替）は [startCandidateFrom] を通らないので、
     * この値は**直前の別の候補についての観測結果**がそのまま残っている。
     * その値が false だった場合（例: 直前の候補の `vpn.connect()` が
     * `NeedsUserConsent` を返して `Idle` に落ち、`Connecting` を一度も
     * 観測しなかった）、待っている候補の `Disconnected` がこのガードに
     * 捨てられる。影響は [onFailingOverTimeout] が [DISCONNECT_WAIT_MS]
     * （3秒）で先へ進めるまでの遅延と、[unconfirmedAbandonedUuid] がそこで
     * 再び立つこと（＝「まだ生きているかもしれない」という悲観の持ち越し）に
     * 留まる。悲観の側に倒れるので安全側の誤りであり、ここで
     * `sawCoreConnecting = true` を置いて回るより、成立条件を正確に書いておく
     * ほうが将来の誤読が少ないと判断した。
     */
    private var sawCoreConnecting = false

    /**
     * Ruling 21: 現在の候補を人が見ている保証が無いか。
     * onUserConnect（ユーザーが画面の前で接続操作をした）から開始した候補は false、
     * failOver（自動切替）や onTick の再試行から開始した候補は true になる。
     * true のときに UserPrompt に来ても誰も答えられないので、認証失敗と同様に扱う。
     */
    private var currentCandidateUnattended = false

    /**
     * 現在の候補が `UserPrompt`（認証フォーム処理中）に入った時刻。
     * 次の状態へ進んだら null に戻す。
     *
     * `UserPrompt` 自体は「人の入力が必要」を意味しない。既存コアは
     * `onProcessAuthForm` の冒頭で無条件に `STATE_USER_PROMPT` を送るので、
     * 保存済み認証情報で完全に自動ログインする場合でも必ず観測される
     * （`AuthFormHandler` は `batch_mode=empty_only` かつ全項目が埋まっていれば
     * ダイアログを出さずに即 OK を返す）。区別できるのは「その後進むかどうか」だけで、
     * 自動入力なら `Authenticating` がミリ秒で続き、ダイアログが出た場合は
     * `waitForResponse()` で無期限に止まる。
     *
     * **どの経路で arming されるか（裁定94 の C2 で訂正した点）**:
     * この値は**候補の有人・無人を問わず、`UserPrompt` を観測すれば必ず**立つ。
     * つまり意味は名前どおりの「いま `UserPrompt` で止まっているか、その開始時刻」
     * だけであり、それ以上の含みは無い。
     *
     * 裁定93 までは arming 自体が [currentCandidateUnattended] で絞られていた
     * （`if (currentCandidateUnattended && userPromptSinceMs == null)`）。その形だと
     * **ユーザーが画面から押した接続（`UserConnectGroup` 由来の候補）では
     * この値が永久に null** であり、「`UserPrompt` が届いているか」を問う判定を
     * ここに足しても有人経路では一切効かない。裁定94 は「人が答えている最中か」を
     * 接続タイムアウト（[onConnectTimeout]）の条件にも使うので、有人経路で
     * 効かないのでは目的（利用者がパスワードを打ち終わるまで待つ）を果たさない。
     *
     * そこで arming は無条件にし、Ruling 21 の「無人の候補だけを除外対象にする」
     * という絞り込みは判定側（[onUnattendedPromptTimeout] の
     * [currentCandidateUnattended] ガード）へ移した。**振る舞いは等価である**:
     * 有人候補ではこれまで `since == null` で null が返っていたところが、
     * これからは `!currentCandidateUnattended` で null が返る。
     */
    private var userPromptSinceMs: Long? = null

    /**
     * 枯渇の世代。次に枯渇したときの Exhausted.attempt になる。
     * `onTick` の再試行で 1 つ進め、復旧（Healthy 到達）とユーザー操作でのみ 0 に戻す。
     */
    private var exhaustionAttempt = 0

    /** 安全策 S2: 下層ネット復帰時に次のプローブを即座に実行するフラグ。Ruling 14 により一度だけ消費される。 */
    private var probeImmediatelyOnNetworkRecovery = false

    /**
     * 裁定59: 「生きている可能性のある候補がある状態から」別グループへの接続を
     * 指示された際の保留先。Ruling 25 の2段階切替（切断要求 → 完了確認 →
     * 次を起動）を経てから、このグループを先頭候補から開始する。
     *
     * これが無いと [onUserConnect] が現在の候補を止めずに新しい候補を起動してしまい、
     * 放棄したはずの候補のスレッドが `killVPNThread` の1秒 join を生き延びて後から
     * tun を取る、という計画1 で欠陥13・欠陥15 として潰した事故が UI 起点の切替で
     * 再現する（レビュー指摘1・裁定59）。自動切替の [failOver] は最初からこの規律を
     * 守っているが、ユーザー操作による切替はこのフィールドが無いと素通りしていた。
     *
     * 裁定86（H2）: 以前は `String?`（グループ ID だけ）だった。接続開始の3経路
     * （[onUserConnect] / [onAutoConnect] / [onExhaustedRetry]）が共通の前処理
     * [stopBeforeStarting] を通るようになり、この保留が**有人・無人のどちらの
     * 経路から来たのか**を覚えておく必要が生じたため、[PendingConnect] にした。
     * 「切断を待ってから起動する」という保留の性質そのものであって新しい規則では
     * ない——ここを落とすと、無人経路（画面点灯時の再接続・枯渇後の再試行）から
     * 保留された接続が有人として起動してしまい、裁定30 の「`UserPrompt` から
     * 進まなければ除外して次へ」が働かず、認証ダイアログの前で無期限に止まる
     * （裁定36 が防いでいる事故）。
     */
    private var pendingConnect: PendingConnect? = null

    /**
     * 裁定86（H2）: [pendingConnect] の中身。[unattended] は
     * [startCandidateFrom] にそのまま渡す値で、Ruling 21 の「人が見ている保証が
     * あるか」を保留の前後で失わないために持つ。
     *
     * [fromIndex] も同じ理由で持つ。既定の 0 は「先頭から順に試す」（従来の意味。
     * 保留を作る3経路のうち [onAutoConnect] / [onExhaustedRetry] は常にこれ）。
     * 一覧の行の「初回ログイン」から来た指示だけが 0 以外を持ち、これを落とすと
     * 「接続中に初回ログインを指示した利用者」が切断のあと先頭メンバーへ
     * 繋ぎ直され、目的の接続先へは永久に到達できない（保留の前後で
     * [unattended] を失うと裁定30 の除外が働かなくなるのと同じ形の欠陥）。
     */
    private data class PendingConnect(
        val groupId: String,
        val unattended: Boolean,
        val fromIndex: Int = 0,
    )

    /**
     * 裁定72-fix(F2): [onFailingOverTimeout] が確認を諦めて先へ進んだときの
     * `awaitingUuid`。確認が来ないまま次候補（またはグループの巡回終了）へ
     * 進んだだけであり、諦めた候補のスレッドが実際に終了した保証は無い
     * （[DISCONNECT_WAIT_MS] の KDoc が説明する、認証ダイアログで
     * `UserDialog.waitForResponse()` にブロックしたスレッドのケース）。
     *
     * したがって、この値が非 null の間は [FailoverState.Idle] /
     * [FailoverState.Exhausted] に対して「生きている候補が無い」という前提を
     * 信用してはならない。
     *
     * 裁定86（H2）: この契約を守る場所は [stopBeforeStarting] の1か所だけで
     * あり、候補の起動を始める3経路（[onUserConnect] / [onAutoConnect] /
     * [onExhaustedRetry]）はすべてそこを通る。以前は [onUserConnect] しか
     * この値を読んでおらず、残る2経路は契約を破っていた。**新しい経路を
     * 足すときも必ず [stopBeforeStarting] を通すこと。**
     *
     * 対応する `Disconnected` が実際に届いたら
     * （[onVpnState] 冒頭）null に戻す。二度と届かなければ非 null のまま残り
     * 続けるが、それは「まだ生きているかもしれない」が偽であることを一度も
     * 確認できていないという事実をそのまま表しているだけであり、根拠の無い
     * 楽観に倒すよりは安全である。
     */
    private var unconfirmedAbandonedUuid: String? = null

    fun handle(event: FailoverEvent) {
        // 裁定72a: どのイベントを処理する前にも、まず現在の候補の添字が
        // 最新のグループ構成とまだ整合しているかを確認する。理由は
        // reconcileCandidateIdentity のドキュメントを参照。
        state = reconcileCandidateIdentity(state)
        state = when (event) {
            is FailoverEvent.UserConnectGroup -> onUserConnect(event.groupId, event.fromIndex)
            is FailoverEvent.AutoConnectGroup -> onAutoConnect(event.groupId)
            is FailoverEvent.UserDisconnect -> onUserDisconnect()
            is FailoverEvent.VpnStateChanged -> onVpnState(event.state, event.uuid)
            is FailoverEvent.ProbeResult -> onProbeResult(event.reachable)
            is FailoverEvent.UnderlyingNetworkChanged -> onUnderlyingNetworkChanged(event.available)
            is FailoverEvent.Tick -> onTick()
        }
    }

    /**
     * 裁定72a: `FailoverState` は現在の候補を**添字**（`candidateIndex` /
     * `failedIndex`）で持つ。これは「[groupsProvider] が返す一覧のその時点の並び」
     * における位置でしかない。TV UI が接続中にグループのメンバーを並び替えたり
     * 削除したりすると、次に読み直された一覧では同じ添字が別の接続先を指す
     * ことになる——計画1 で欠陥13・欠陥15 として潰した「画面は A に接続済みと
     * 言っているのに実際のトンネルは B」という嘘と同じ形の事故が、エンジンの
     * 内部競合ではなく設定変更という経路から再来する。
     *
     * [currentCandidateUuid] は候補への接続を開始した瞬間に固定される真実であり、
     * 添字より信頼できる。ここで両者を突き合わせ:
     *
     * - 現在の候補がまだ新しい一覧のどこかに存在する（並び替えられただけ）:
     *   添字をその位置へ振り直す。接続そのものは維持してよい。
     * - 存在しない（削除された）: この候補はグループから外されたということ。
     *   「生きている候補」として扱い続けてはならず、通常の障害経路
     *   （[failOver]。まだ生きている可能性があるので `alreadyDown = false`）へ
     *   入れる。ただし既に [FailoverState.FailingOver] で切断確認を待っている
     *   最中なら、[failOver] を再度呼んで `vpn.disconnect()` を二重に要求したり
     *   `startedAtMs` をリセットしたりしてはならない（Ruling 25/裁定26 の
     *   「上限は起点が変わらない限り有界」という前提を壊す）。その場合は
     *   `failedIndex` を「不明」を表す `-1` に落とすだけに留める。
     *   [advanceAfterFailingOver] は `failedIndex + 1` を次の探索開始位置に
     *   使うので、`-1` なら新しい一覧の先頭（0）から探し直すことになり、
     *   もう存在しない位置を引きずらない。
     *
     * [handle] の冒頭でイベント種別を問わず毎回呼ぶ（Tick に限らない）。判定は
     * 現在の候補ひとつを `memberUuids` から引くだけの O(メンバー数) であり、
     * メンバー数はたかだか数件なので Tick のたびに走らせても安い。Tick 以外の
     * `onDisconnected` / `onUnattendedUserPrompt` も候補の添字からメンバーを
     * 逆引きして S1 の除外対象を決めるため、Tick 経由でしか直さないと次の
     * Tick が来るまでの間はそれらのハンドラが古い添字を使ってしまう
     * （＝間違った候補を除外しかねない）。イベント種別を問わず毎回直すことで
     * その隙間を作らない。
     */
    private fun reconcileCandidateIdentity(s: FailoverState): FailoverState {
        val groupId = groupIdOf(s) ?: return s
        val index = candidateIndexOf(s) ?: return s
        val uuid = currentCandidateUuid ?: return s
        val group = groupOf(groupId)

        if (group == null) {
            // 裁定72-fix(F1): グループそのものが丸ごと削除された（例: 最後の
            // メンバーだったプロファイルが消え GroupStore.loadGroups が
            // グループごと落とした）。供給関数化する前はこの分岐に到達すること
            // 自体が無かった（グループ一覧はサービスの生存期間中不変だった）ため、
            // 「トンネルはまだ生きているかもしれないが、もう誰も追跡しない」まま
            // Idle を騙ってはならない、という考慮がここには元々無かった。
            return if (s is FailoverState.FailingOver) {
                // この状態に入った時点で既に vpn.disconnect() を awaitingUuid 宛に
                // 要求済みである（failOver または onUserConnect の生存候補分岐）。
                // advanceAfterFailingOver 自身が groupOf(...) == null を Idle として
                // 正しく処理し、pendingConnect の消費もそこで行う。ここで
                // 先回りして Idle に落とすと、その消費を経由せず
                // pendingConnect が迷子になる（F6 と同じ形の罠）ので、
                // 確認到着または DISCONNECT_WAIT_MS のタイムアウトによる通常の
                // 前進に任せる。
                s
            } else {
                // Connecting/Verifying/Healthy: まだ生きている可能性のある候補を
                // 誰も切断要求していない。ここで初めて気づいたので、S3 のフラグを
                // 立てたうえで切断を要求してから Idle へ落ちる。これをせずに
                // 単に Idle を返すと、トンネルは張られたまま誰にも監視されず、
                // 次の onUserConnect が Idle からの「即座に起動してよい」経路
                // （生きている候補が無い前提）を取ってしまい、欠陥13/欠陥15 と
                // 同じ事故（画面は新グループ、実際のトラフィックは旧グループ）が
                // 設定変更という経路から再来する。
                disconnectIfStillUp(alreadyDown = false)
                FailoverState.Idle
            }
        }

        if (group.memberUuids.getOrNull(index) == uuid) return s

        val newIndex = group.memberUuids.indexOf(uuid)
        if (newIndex >= 0) return withCandidateIndex(s, newIndex)

        return if (s is FailoverState.FailingOver) {
            s.copy(failedIndex = -1)
        } else {
            failOver(groupId, failedIndex = -1, alreadyDown = false)
        }
    }

    private fun withCandidateIndex(s: FailoverState, index: Int): FailoverState = when (s) {
        is FailoverState.Connecting -> s.copy(candidateIndex = index)
        is FailoverState.Verifying -> s.copy(candidateIndex = index)
        is FailoverState.Healthy -> s.copy(candidateIndex = index)
        is FailoverState.FailingOver -> s.copy(failedIndex = index)
        is FailoverState.Exhausted -> s
        FailoverState.Idle -> s
    }

    /**
     * 呼び出し側がいまプローブを打つべきかの判定。
     * 安全策 S2 により下層ネットワークが無いときは常に false。
     * Ruling 14 により下層ネット復帰時は即座にプローブする。
     */
    fun shouldProbeNow(): Boolean {
        if (!network.hasUnderlyingNetwork()) return false

        // 下層ネット復帰時は即座にプローブする（一度だけ）
        if (probeImmediatelyOnNetworkRecovery) {
            probeImmediatelyOnNetworkRecovery = false
            return true
        }

        val now = clock.nowMs()
        return when (val s = state) {
            is FailoverState.Verifying ->
                now - s.connectedAtMs >= configOf(s.groupId).graceAfterConnectSec * 1_000L

            is FailoverState.Healthy ->
                now - s.lastProbeAtMs >= configOf(s.groupId).probeIntervalSec * 1_000L

            else -> false
        }
    }

    /**
     * 裁定88（Low 1）: 呼び出し側（`FailoverService`）が CPU を起こし続けるべきか
     * （裁定43 の `PARTIAL_WAKE_LOCK` を保持すべきか）の判定。
     * [shouldProbeNow] と同じ「状態機械への読み取り専用の問い合わせ」であり、
     * ここで状態遷移は起こさない（Android API も参照しない）。
     *
     * 裁定43 の条件は `state !is Idle` だった。その意図は「監視しているあいだは
     * 起きている／監視していないなら起きていない」である。裁定86（H2）以降、
     * その条件では意図から外れる状態が日常的に作れるようになった:
     *
     * - [unconfirmedAbandonedUuid] がラッチしたまま（[onFailingOverTimeout] が
     *   [DISCONNECT_WAIT_MS] で諦めるたびに立て直されるので、対応する
     *   `Disconnected` が二度と届かなければ実質ずっと非 null のまま）の端末で
     *   画面が点灯すると、[onAutoConnect] が [stopBeforeStarting] を通って
     *   [FailoverState.FailingOver] に入る。
     * - そのとき下層ネットワークが無いと [advanceAfterFailingOver] の S2 ゲートで
     *   前進できず、`Exhausted` のバックオフにも入らないまま `FailingOver` に
     *   留まる。`Idle` ではないので wake lock は保持され続ける——
     *   **前進できないと分かっている状態で CPU を起こし続ける**ことになる。
     *
     * そこで「`FailingOver` かつ下層ネットワークが無い」あいだは保持しない。
     * この状態の状態機械にできることは何も無いことを数え上げて確認している:
     * `onTick` の `FailingOver` 分岐は [onFailingOverTimeout] しか呼ばず、
     * その先は必ず [advanceAfterFailingOver] の `hasUnderlyingNetwork()` ゲートで
     * 止まる。`Disconnected` の確認が届いた場合も同じゲートを通るので
     * `FailingOver` のまま返る。つまりネットワークが戻るまで**どのイベントでも
     * 状態は変わらない。**
     *
     * 安全策 S2（下層ネットが無いときは切り替えない）は壊していない。ここは
     * `FailoverService` の副作用（wake lock）の条件だけを変えるもので、
     * [advanceAfterFailingOver] のゲートにも `vpn.connect()` の呼び出し条件にも
     * 触れていない。むしろ「S2 で保留されている間は細かく tick しない」という
     * `FailoverService` 側の既存の判断（`TICK_INTERVAL_SWITCHING_MS` を使わず
     * 待機間隔に戻す）と同じ方向の整理である。
     *
     * ネットワークが戻ったことに気づけなくなることも無い: 復帰の検知は
     * ティックループの `hasUnderlyingNetwork()` の監視（→
     * [FailoverEvent.UnderlyingNetworkChanged]）と、`VpnStatusBridge` の
     * ブロードキャスト受信であり、どちらもこの wake lock に依存しない
     * （ブロードキャスト配送は OS 側が起こす）。加えて裁定44 により画面消灯中は
     * 必ず `Idle`（＝wake lock は元から解放されている）なので、この分岐が
     * 効くのは画面が点いている＝端末が interactive で CPU が動いている場面に
     * 限られる。次の tick か次のイベントで `FailoverService.dispatch` が走れば
     * `syncWakeLock()` が再評価して取り直す。
     */
    fun requiresCpuAwake(): Boolean = when (state) {
        FailoverState.Idle -> false
        is FailoverState.FailingOver -> network.hasUnderlyingNetwork()
        else -> true
    }

    /**
     * 裁定59: 生きている可能性のある候補（`Connecting`/`Verifying`/`Healthy`）が
     * ある状態からの切替は、必ず Ruling 25 の2段階（切断要求 → 完了確認 →
     * 次を起動）を経由させる。これを呼び出し側（UI）の規律に依存させると、
     * UI 層に別の呼び出し経路が増えるたびに同じ事故（放棄した候補のスレッドが
     * 後から tun を取る）が再現しうる。状態機械自身が不変条件
     * （生きている候補がある切替は必ず2段階）を保証する。
     * `Idle`/`Exhausted` は生きている候補が無いので即座に起動してよい
     * （後述）。
     *
     * [fromIndex] は起動を試し始める位置。既定の 0 は従来どおり
     * 「グループの先頭から順に試す」であり、**既定値で呼ぶ限り振る舞いは
     * 以前と完全に同じ**である（裁定72/93 と同じ既定値の作法）。0 以外が来るのは
     * 一覧の行の「初回ログイン」からだけで、そこから先は既存の
     * [startCandidateFrom] に位置を渡すだけである（新しい接続経路は無い）。
     * 有人（`unattended = false`）は変えないので、初回ログインの飛ばしは
     * ここでは適用されない（[needsFirstLoginProvider] の KDoc）。
     *
     * 範囲外の [fromIndex]（グループが無い／その位置にメンバーが居ない）の扱い:
     * **何もしない。** 状態も副作用も一切変えずに現在の状態を返す。
     * 画面が読んだグループ定義とここが持つ定義がずれていた場合に起こりうるが、
     * そのとき先頭から繋ぎ直すと**利用者が指したのとは別の接続先**が起動して
     * しまう（「理由の分からない自動挙動を作らない」という既存の方針に反する）。
     * 枯渇（[enterExhausted]）にも落とさない——利用者の1回の操作が、繋ぐ気の
     * 無い候補へのバックオフ再試行を始める理由は無いためである。
     * `fromIndex == 0` のときはこの判定を通さないので、既存の経路
     * （グループが無ければ [FailoverState.Idle]、メンバー0件なら枯渇）は
     * 従来どおりである。
     */
    private fun onUserConnect(groupId: String, fromIndex: Int = 0): FailoverState {
        if (fromIndex != 0) {
            val requested = groupOf(groupId)
            if (requested == null || fromIndex !in requested.memberUuids.indices) return state
        }

        exhaustionAttempt = 0
        // 裁定35a: 明示的なユーザー操作は新しいセッションの意思表示である。
        // S1（_excludedUuids）は「無人リトライでアカウントをロックさせない」ための
        // ものであり、ユーザー自身がいま接続を指示している以上、その目的とは
        // 衝突しない。ここでクリアしないと、全候補が除外された後はユーザーが
        // 何度「接続」を押しても vpn.connect() が一度も呼ばれない（欠陥・M1）。
        _excludedUuids.clear()
        needsUserConsent = false

        stopBeforeStarting(groupId, unattended = false, fromIndex = fromIndex)?.let { return it }

        val group = groupOf(groupId) ?: return FailoverState.Idle
        return startCandidateFrom(group, fromIndex = fromIndex, unattended = false)
    }

    /**
     * 裁定86（H2）: 「候補を起動する前に、まだ生きているかもしれない候補の切断を
     * 先に済ませる必要があるか」という前処理。[onUserConnect] だけが守っていた
     * 契約を、接続を開始する3経路（[onUserConnect] / [onAutoConnect] /
     * [onExhaustedRetry]）で共有するために切り出したものであり、判断内容は
     * 以前の [onUserConnect] とまったく同じである（新しい規則は足していない）。
     *
     * 非 null を返したら、それが新しい状態（切断の完了を待つ
     * [FailoverState.FailingOver]、または既に待っている状態そのもの）であり、
     * 呼び出し側は**候補を起動してはならない**。起動は切断確認または
     * [DISCONNECT_WAIT_MS] のタイムアウトのあと [advanceAfterFailingOver] が
     * [pendingConnect] を消費して行う。null を返したら、待つべき相手は本当に
     * 無いのでそのまま [startCandidateFrom] してよい。
     *
     * 分けて扱う理由（3経路それぞれで実際に踏める事故）:
     *
     * - [onAutoConnect]: 裁定44 により TV の画面が消えるたびに `UserDisconnect`
     *   → `Idle` になり、点灯で `AutoConnectGroup` が飛ぶ。直前の切替が
     *   [onFailingOverTimeout] で確認を諦めていた場合（[unconfirmedAbandonedUuid]
     *   が非 null）、ここを通さないと放棄した候補のスレッドが生きているまま
     *   新しい候補を起動することになり、**TV の電源を切って点けるだけで**
     *   Ruling 25 と裁定72-fix(F2) が防いでいる事故（旧スレッドが後から tun を
     *   取る）を踏む。
     * - [onExhaustedRetry]: バックオフ満了時の自動再試行。同じく `Exhausted` に
     *   至る直前がタイムアウト経由なら放棄候補が残っている。ここは遅延コストが
     *   実質ゼロ（既に数十秒待っている）。
     * - [onUserConnect]: 裁定72-fix(F2) で既に守っていた分。
     */
    private fun stopBeforeStarting(
        groupId: String,
        unattended: Boolean,
        fromIndex: Int = 0,
    ): FailoverState? {
        val current = state

        if (current is FailoverState.Idle || current is FailoverState.Exhausted) {
            // 裁定72-fix(F2): 以前のコメントは「Exhausted に至る前の切替はすべて
            // failOver（Ruling 25 の2段階）を経ているため、ここで待つべき相手が
            // 無い」と主張していたが、これは偽である。failOver を*経由した*こと
            // と、その切断が*確認された*ことは別で、onFailingOverTimeout が
            // DISCONNECT_WAIT_MS の確認待ちを諦めて先へ進む経路では、諦めた
            // 候補のスレッドが実際に終了した保証が無い（[unconfirmedAbandonedUuid]
            // 参照）。その値が立っている間は「生きている候補が無い」という
            // 前提を信用せず、Ruling 25 と同じ2段階（切断要求 → 確認 or
            // 再タイムアウト → 起動）を Idle/Exhausted からでも踏む。
            val abandonedUuid = unconfirmedAbandonedUuid
                ?: run {
                    // 待つべき相手は本当に無い。即座に起動してよい（待たせると
                    // 「除外がクリアされたのに何も繋がらない」空回りの時間が
                    // 延びるだけで、安全性は上がらない）。ここで古い保留先を
                    // 消すのは裁定72-fix(F6) と同じ理由——直前の保留が何らかの
                    // 経路で残っていた場合、次の切替でこの起動と無関係な
                    // グループへ横取りされる。
                    pendingConnect = null
                    return null
                }
            pendingConnect = PendingConnect(groupId, unattended, fromIndex)
            currentCandidateUuid = abandonedUuid
            vpn.disconnect()
            return FailoverState.FailingOver(
                groupId = groupId,
                // pendingConnect が非 null な限り advanceAfterFailingOver は
                // これを一切読まない（pendingConnect 分岐が必ず先に評価
                // される）。ここでは「不明」を表す -1 を置くだけでよい。
                failedIndex = -1,
                awaitingUuid = abandonedUuid,
                startedAtMs = clock.nowMs(),
            )
        }

        if (current is FailoverState.FailingOver) {
            // 既に切断待ちの最中（自動切替由来・前回の切替指示由来を問わず）。
            // 裁定26: DISCONNECT_WAIT_MS という上限は起点の startedAtMs が変わらない
            // 限りにおいて有界である。ここで vpn.disconnect() を再送したり
            // startedAtMs を更新したりすると、指示が繰り返されるたびに上限が
            // 延び続け、上限が上限でなくなる。保留先だけを差し替える。
            pendingConnect = PendingConnect(groupId, unattended, fromIndex)
            return current
        }

        // Connecting / Verifying / Healthy: 現在の候補（まだ生きている可能性がある）
        // を先に止め、完了を待ってから新グループを起動する（Ruling 25 と同じ2段階。
        // expectingDisconnect は立てない — この Disconnected は S3 で捨てるのでは
        // なく、advanceAfterFailingOver への合図として観測する必要がある）。
        pendingConnect = PendingConnect(groupId, unattended, fromIndex)
        val awaitingUuid = currentCandidateUuid
        val currentGroupId = groupIdOf(current) ?: groupId
        val currentIndex = candidateIndexOf(current) ?: 0
        vpn.disconnect()
        return FailoverState.FailingOver(
            groupId = currentGroupId,
            failedIndex = currentIndex,
            awaitingUuid = awaitingUuid,
            startedAtMs = clock.nowMs(),
        )
    }

    /**
     * 裁定36: プロセス kill からの自動復帰（Ruling 18）など、人の操作ではない
     * 接続開始。[onUserConnect] と異なり候補を無人（`unattended = true`）として
     * 開始するので、裁定30 の「`UserPrompt` から進まなければ除外」が働く。
     * 除外集合はクリアしない（無人で除外を落とすと、失効した認証情報を無期限に
     * 再試行してサーバ側のアカウントロックを招く。S1 が存在する理由そのもの）。
     *
     * 裁定86（H2）: [stopBeforeStarting] を通す。以前はこの経路が
     * [unconfirmedAbandonedUuid] を一度も読まずに [startCandidateFrom] を直接
     * 呼んでいたため、**TV の画面を消して点けるだけで**（裁定44 により消灯で
     * 切断・点灯で `AutoConnectGroup`）放棄候補のスレッドが生きているまま次の
     * `vpn.connect()` が走り、Ruling 25 が防いでいる事故を日常操作から踏めた。
     * 裁定72-fix(F6) の「古い保留先を持ち越さない」も、同じ前処理の中
     * （待つ相手が無いと判った分岐）で行うようになった。
     */
    private fun onAutoConnect(groupId: String): FailoverState {
        exhaustionAttempt = 0
        needsUserConsent = false

        stopBeforeStarting(groupId, unattended = true)?.let { return it }

        val group = groupOf(groupId) ?: return FailoverState.Idle
        return startCandidateFrom(group, fromIndex = 0, unattended = true)
    }

    private fun onUserDisconnect(): FailoverState {
        exhaustionAttempt = 0
        probeImmediatelyOnNetworkRecovery = false
        expectingDisconnect = true
        // 裁定59: 切断を指示したのに保留先が残っていると、切断確認の後で
        // 勝手に別グループへ繋ぎ始めてしまう。ユーザーは「止めろ」と言ったのであり
        // 「別のグループへ繋ぎ直せ」とは言っていない。
        pendingConnect = null
        vpn.disconnect()
        return FailoverState.Idle
    }

    /**
     * [fromIndex] 以降で**起動してよい**最初の候補の位置。無ければ
     * `group.memberUuids.size`（＝枯渇）を返す。
     *
     * 候補を飛ばす規則をこの1か所に集めるために切り出してある。以前は
     * [startCandidateFrom] のループの中に直接書かれていたが、飛ばす条件が
     * 「除外されている」以外にも増えたので、条件が呼び出し経路ごとに食い違う
     * 余地を残さないよう1つの関数にした。[startCandidateFrom] がこの関数の
     * 唯一の呼び出し元であり、候補を起動する5経路（[onUserConnect] /
     * [onAutoConnect] / [failOver] / [advanceAfterFailingOver] /
     * [onExhaustedRetry]）はすべて [startCandidateFrom] を通る。
     *
     * 飛ばす条件は2つ:
     * - 安全策 S1 の除外集合（[excludedUuids]）に入っている。有人・無人を問わない
     *   ……**わけではない**。除外は「認証段階に到達して通らなかった」記録であり、
     *   裁定35 により利用者の明示操作（[onUserConnect]）ではその集合ごとクリア
     *   されてからここへ来る。したがってこの判定を `unattended` で分ける必要は
     *   なく、従来どおり無条件でよい（振る舞いは以前と同一）。
     * - [needsFirstLoginProvider] が true で、**かつ [unattended] が true**。
     *   人が居ないときに成功しえない候補を起動しないための規則で、利用者の
     *   明示操作では適用しない（理由は [needsFirstLoginProvider] の KDoc）。
     *
     * 供給関数を引くのは候補選択のときだけである（`onTick` の毎周期では引かない）。
     *
     * [fromIndex] の下限を 0 に丸めるのは防御でしかない。負の値が来る経路は
     * 現在存在しない（[stopBeforeStarting] が置く `failedIndex = -1` は
     * [advanceAfterFailingOver] の `pendingConnect` 分岐が先に評価されるため
     * 読まれず、読まれる経路では必ず `failedIndex + 1 >= 0` になる）。
     * 丸めの有無で到達しうる振る舞いは変わらない。
     */
    private fun nextStartableIndex(group: FailoverGroup, fromIndex: Int, unattended: Boolean): Int {
        var index = fromIndex.coerceAtLeast(0)
        while (index < group.memberUuids.size) {
            val uuid = group.memberUuids[index]
            val skip = uuid in _excludedUuids || (unattended && needsFirstLoginProvider(uuid))
            if (!skip) return index
            index++
        }
        return group.memberUuids.size
    }

    /**
     * [fromIndex] 以降で起動してよい最初の候補（[nextStartableIndex]）へ接続する。
     * 候補が無ければ Exhausted に入る。
     */
    private fun startCandidateFrom(group: FailoverGroup, fromIndex: Int, unattended: Boolean): FailoverState {
        var index = fromIndex
        while (true) {
            index = nextStartableIndex(group, index, unattended)
            if (index >= group.memberUuids.size) break
            val uuid = group.memberUuids[index]
            currentCandidatePassedAuth = false
            currentCandidateReachedAuth = false
            currentCandidateUnattended = unattended
            userPromptSinceMs = null
            sawCoreConnecting = false
            expectingDisconnect = false
            currentCandidateUuid = uuid
            probeImmediatelyOnNetworkRecovery = false
            return when (vpn.connect(uuid)) {
                ConnectResult.Started -> FailoverState.Connecting(
                    groupId = group.id,
                    candidateIndex = index,
                    startedAtMs = clock.nowMs(),
                )

                ConnectResult.NeedsUserConsent -> {
                    needsUserConsent = true
                    FailoverState.Idle
                }

                ConnectResult.Failed -> {
                    _excludedUuids.add(uuid)
                    index++
                    continue
                }
            }
        }
        return enterExhausted(group.id, attempt = exhaustionAttempt)
    }

    private fun onVpnState(core: VpnCoreState, uuid: String?): FailoverState {
        // 裁定72-fix(F2): 諦めた候補の確認が遅れて届いた。currentCandidateUuid が
        // 既に別の候補を指している場合、このあとの Ruling 13 のガードで
        // 状態機械としては無視される（意図通り）が、「まだ生きているかもしれ
        // ない」という疑いはここで晴らしてよい。ガードより前に見るのは、この
        // 確認はどのみち「もう追っていない候補」のものであり、Ruling13 の対象
        // そのものだから。
        if (core == VpnCoreState.Disconnected && uuid != null && uuid == unconfirmedAbandonedUuid) {
            unconfirmedAbandonedUuid = null
        }

        val s = state

        // 切替前の古い通知は無視する。遅れて届いた切断通知を
        // 新しい候補の障害と誤認すると、一度も試していない候補が S1 で除外される。
        if (uuid != null && uuid != currentCandidateUuid) return state

        // 認証を通過したことを覚えておく（S1 の判定に使う）
        if (core == VpnCoreState.Authenticated || core == VpnCoreState.Connected) {
            currentCandidatePassedAuth = true
        }

        // Ruling 23: 認証段階まで到達したことを覚えておく（S1 の誤判定を防ぐ）。
        // Authenticating は TLS 接続が成功しサーバが認証フォームを返したことを、
        // UserPrompt はそのフォームとやり取りしていたことを意味する。
        if (core == VpnCoreState.Authenticating || core == VpnCoreState.UserPrompt) {
            currentCandidateReachedAuth = true
        }

        // 裁定31: 自分の接続試行が既存コア側で始まったことを覚えておく
        // （sawCoreConnecting の説明を参照）。
        if (core == VpnCoreState.Connecting) sawCoreConnecting = true

        // 裁定30: `UserPrompt` に入った時刻を覚え、次の状態へ進んだら解除する。
        // 進展の有無だけがダイアログの有無を見分ける唯一の手段である（詳細は
        // userPromptSinceMs の説明）。
        // 裁定94: 有人・無人を問わず立てる。「無人の候補だけを除外する」という
        // Ruling 21 の絞り込みは onUnattendedPromptTimeout 側で行う（等価。理由は
        // userPromptSinceMs の KDoc「どの経路で arming されるか」参照）。
        var humanJustAnswered = false
        if (core == VpnCoreState.UserPrompt) {
            if (userPromptSinceMs == null) {
                userPromptSinceMs = clock.nowMs()
            }
        } else {
            // 裁定94: 「人が答え終わった瞬間」を捉える（この変数を読む箇所は
            // この関数の末尾。理由はそこのコメント）。
            humanJustAnswered = userPromptSinceMs != null && dialogHostAttachedProvider()
            userPromptSinceMs = null
        }

        val next = when {
            // 裁定31a/37: 自分の Connecting を観測する前に届いた Disconnected は
            // 旧スレッドのものである。ただしこの門番は「まだ接続が始まっていない」
            // 状態にだけ適用する。Connected を観測済みの候補（Verifying / Healthy）の
            // Disconnected は確実にその候補のものであり、捨てると R5 の系統1
            // （切断イベントによる即時切替）が死んで検知がプローブ任せになる
            // （仕様書 §8）。
            // 裁定86（L1）: FailingOver 中にこのフラグが必ず true になる、とは
            // 限らない（成立条件の正確な説明は sawCoreConnecting の KDoc を
            // 参照）。false のまま入った場合は確認の受け取りを妨げるが、その
            // 代償は onFailingOverTimeout が先へ進めるまでの3秒の遅延だけで、
            // 悲観（まだ生きているかもしれない）の側に倒れる誤りである。
            core == VpnCoreState.Disconnected &&
                !sawCoreConnecting &&
                (s is FailoverState.Connecting || s is FailoverState.FailingOver) -> s

            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                    consecutiveFailures = 0,
                )

            // Ruling 25: 切断完了の確認。これを合図に次候補を起動する。
            // awaitingUuid との照合は、このメソッド冒頭の Ruling 13 ガード
            // （uuid != currentCandidateUuid の通知はここまで来ない）により
            // 実際には到達不能な分岐である。二重の安全策ではなく、
            // 「FailingOver は自分が待っている候補の Disconnected だけを合図とする」
            // という不変条件をこの状態自身に明示するために残してある。
            core == VpnCoreState.Disconnected && s is FailoverState.FailingOver ->
                if (s.awaitingUuid == null || uuid == null || uuid == s.awaitingUuid) {
                    advanceAfterFailingOver(s)
                } else {
                    s
                }

            core == VpnCoreState.Disconnected -> onDisconnected(s)

            else -> s
        }

        // 裁定94: 人が答え終わった直後に Ruling 22 の期限切れで畳まれないよう、
        // ここで**1回だけ** `Connecting` の起点を「答え終わった瞬間」へ置き直す。
        //
        // これが無いと裁定94 の保護は肝心なところで役に立たない: 利用者が90秒かけて
        // パスワードを打ち、`OK` を押してコアが `Authenticating` へ進んだ瞬間に
        // [awaitingHumanAuthInput] は false へ戻り、`Connecting.startedAtMs` 起点の
        // 45秒は**すでに大幅に過ぎている**ので、次の tick（`TICK_INTERVAL_MS` = 5秒）で
        // 即座に候補を畳む。**正しいパスワードを打ち終えた直後に切られる**という、
        // 裁定94 が防ごうとしたものと実質同じ結末になる。サーバ側の認証往復に数秒
        // かかる構成（LDAP/RADIUS/OTP）や、フォームが複数ページに分かれる構成
        // （証明書の承認 → 認証フォーム）では確実に踏む。
        //
        // 置き直しても有界性は損なわれない。Ruling 22 が測りたいのは
        // **サーバの沈黙**であって人がキーを押していた時間ではないので、起点が人の
        // 回答時刻に移ったあとも「サーバが45秒沈黙したら諦める」は保たれる。
        // 置き直しが起きるのは `UserPrompt` から次の状態へ進んだ観測1回につき1回
        // だけで、その遷移は必ず人の操作（またはコアの自動入力）に対応する。
        // 人が居なくなれば供給関数が false になり、この条件自体が成り立たない。
        //
        // `next` が `Connecting` になるのは「`s` のまま」か「新しい候補を起動した
        // 直後」のどちらかで、後者の `startedAtMs` は既に `clock.nowMs()` なので
        // この置き直しは無害である（どちらの場合も結果は同じ値になる）。
        return if (humanJustAnswered && next is FailoverState.Connecting) {
            next.copy(startedAtMs = clock.nowMs())
        } else {
            next
        }
    }

    /**
     * 裁定30: 自動切替または枯渇後の再試行で開始した候補の `UserPrompt` が
     * [USER_PROMPT_WAIT_MS] を過ぎても次の状態へ進まなかった（＝ダイアログが出て
     * 人の入力を待っている。保存済み認証情報が拒否された等）。TV の前に人がいる
     * 保証は無く、このまま放置すると S1 の安全策（認証失敗した候補を除外して
     * 次へ進む）が一度も働かずに無期限へ止まってしまう。よって認証失敗と同じ
     * 扱いにする：この候補を除外して次候補へ進める（alreadyDown = false。
     * Ruling 25 により切断を要求してから完了を待って次候補へ進む）。
     *
     * `UserPrompt` を観測しただけでは呼ばない（Ruling 21 時代の誤り）。既存コアは
     * `onProcessAuthForm` の冒頭で無条件に `STATE_USER_PROMPT` を送るため、
     * 保存済み認証情報で完全に自動ログインする場合でも必ず観測され、それだけで
     * 除外すると認証情報が正しい健全な候補まで焼き切ってしまう（実機で確認:
     * 起動から約1秒で健全な候補が除外された）。
     */
    private fun onUnattendedUserPrompt(s: FailoverState): FailoverState {
        val groupId = groupIdOf(s) ?: return s
        val index = candidateIndexOf(s) ?: return s
        groupOf(groupId)?.memberUuids?.getOrNull(index)?.let { _excludedUuids.add(it) }
        return failOver(groupId, index, alreadyDown = false)
    }

    private fun onDisconnected(s: FailoverState): FailoverState {
        // S3: 自分が出した切断要求の結果なら障害ではない
        if (expectingDisconnect) {
            expectingDisconnect = false
            return s
        }
        // S2: 下層ネットが無いなら切り替えても無意味。現状を保って待つ
        if (!network.hasUnderlyingNetwork()) return s

        val groupId = groupIdOf(s) ?: return s
        val index = candidateIndexOf(s) ?: return s

        // Ruling 23: 認証段階まで到達していながら通過せずに落ちた場合だけ
        // 認証失敗とみなして除外する。到達すらしていない切断（TLS接続失敗など）は
        // ネットワーク障害であり、除外すると一時的な障害で健全な候補まで
        // セッション中焼き切ってしまう。除外しない場合も次候補へは進める。
        if (currentCandidateReachedAuth && !currentCandidatePassedAuth) {
            groupOf(groupId)?.memberUuids?.getOrNull(index)?.let { _excludedUuids.add(it) }
        }
        return failOver(groupId, index, alreadyDown = true)
    }

    private fun onProbeResult(reachable: Boolean): FailoverState {
        return when (val s = state) {
            is FailoverState.Verifying ->
                if (reachable) {
                    exhaustionAttempt = 0
                    FailoverState.Healthy(
                        groupId = s.groupId,
                        candidateIndex = s.candidateIndex,
                        consecutiveFailures = 0,
                        lastProbeAtMs = clock.nowMs(),
                    )
                } else if (clock.nowMs() - s.connectedAtMs < configOf(s.groupId).graceAfterConnectSec * 1_000L) {
                    // S4: 猶予期間中の失敗は無視する。本来 shouldProbeNow() が
                    // graceAfterConnectSec 経過前はプローブの実行自体を許可しないため、
                    // 実運用ではこの分岐に届く失敗は無いはずである。それでも
                    // S2（下層ネット断）・S3（意図的切断）と同様、呼び出し側の規律だけに
                    // 頼らず状態機械自身でも猶予期間を再確認する。
                    s
                } else {
                    // Ruling 22: 猶予期間を過ぎてから届いた失敗は意味のある疎通断である
                    // （ここに来る時点で shouldProbeNow() 経由なら必ず post-grace のはず、
                    // という前提が仮に崩れても、直前の分岐で二重に守られている）。
                    // 無視し続けるとトンネルは張れたが疎通が無い候補に無期限に留まって
                    // しまうので、Healthy と同じ閾値で切替える。alreadyDown = false
                    // であり、Ruling 25 により切断を要求してから完了を待って次候補へ
                    // 進む。疎通できないだけで認証は失敗していないので、タイムアウト
                    // （Ruling 22 a）と同様に候補は除外しない。
                    val failures = s.consecutiveFailures + 1
                    if (failures >= configOf(s.groupId).failureThreshold) {
                        failOver(s.groupId, s.candidateIndex, alreadyDown = false)
                    } else {
                        s.copy(consecutiveFailures = failures)
                    }
                }

            is FailoverState.Healthy -> {
                val failures = if (reachable) 0 else s.consecutiveFailures + 1
                if (failures >= configOf(s.groupId).failureThreshold) {
                    failOver(s.groupId, s.candidateIndex, alreadyDown = false)
                } else {
                    s.copy(consecutiveFailures = failures, lastProbeAtMs = clock.nowMs())
                }
            }

            else -> s
        }
    }

    /**
     * 候補 [failedIndex] を諦めて次候補へ進む。
     * 自動切替が無効なグループでは切り替えず Idle で停止する（R6）。
     *
     * Ruling 24: 切り替える先の候補があるうちは、こちらから明示的に切断しない
     * ……という前提だったが、Ruling 25 でこれは覆された。`vpn.connect()`（=
     * `OpenVpnService.onStartCommand`）が自分で行う `killVPNThread(true)` は
     * スレッド join を 1000ms で打ち切るため、接続処理の途中でブロックしている
     * スレッドはその時間内に終わらず、後から接続を完了させる（実機で確認: 放棄
     * したはずの候補の `tun0` が生き残り、状態機械は無関係に巡回し続けた）。
     * Ruling 25 により切断を要求してから完了を待って次候補へ進む（まだ生きている
     * 可能性がある場合。[alreadyDown] が true なら Disconnected 起因なので待つ
     * 対象が無く、直接次候補へ進む）。
     */
    private fun failOver(groupId: String, failedIndex: Int, alreadyDown: Boolean): FailoverState {
        val group = groupOf(groupId) ?: run {
            // 裁定72-fix(F1): グループが丸ごと削除されていた場合も、他の
            // 「諦めて Idle へ戻る」経路（!autoFailoverEnabled の直後）と同じく
            // disconnectIfStillUp を経由する。alreadyDown = false（＝まだ生きて
            // いる候補を諦める呼び出し）でここに来た場合、切断要求を出さずに
            // Idle を返すとトンネルが誰にも監視されないまま残る。今日この分岐は
            // reconcileCandidateIdentity が呼び出し元で先に同じ状況を捕まえる
            // ため到達しないはずだが、その前提（グループ一覧が1回の handle()
            // 呼び出し内で安定していること）はここでは検証できない値なので、
            // 単独でも安全なように防御的に直す。
            disconnectIfStillUp(alreadyDown)
            return FailoverState.Idle
        }

        if (!group.autoFailoverEnabled) {
            disconnectIfStillUp(alreadyDown)
            return FailoverState.Idle
        }

        // トンネルは既に落ちている（Disconnected 起因）。待つ対象が無いので即座に次候補へ。
        if (alreadyDown) {
            return startCandidateFrom(group, fromIndex = failedIndex + 1, unattended = true)
        }

        // Ruling 25: まだ生きている可能性のある候補を先に止め、その完了を待つ。
        // ここで expectingDisconnect を立ててはならない。この Disconnected は
        // S3 で捨てるのではなく、次候補へ進む合図として観測する必要がある。
        val awaiting = currentCandidateUuid
        vpn.disconnect()
        // 裁定30: Ruling 26 が守っていた性質（FailingOver 中は UserPrompt 由来の
        // 除外判定を走らせない）は、次の2つが揃って保たれている。
        // (1) onTick の `FailingOver` 分岐は onFailingOverTimeout しか呼ばず、
        //     onUnattendedPromptTimeout を経由しない（今この瞬間の主たる理由）。
        // (2) ここで userPromptSinceMs を null にし、古いタイムスタンプを
        //     FailingOver へ持ち越さない（(1) の判定経路が将来変わっても、
        //     突入直後に古い値で即座に誤判定しないための保険）。
        // どちらか一方だけでは説明として不十分なので、変更するときは両方を見ること。
        userPromptSinceMs = null
        return FailoverState.FailingOver(
            groupId = groupId,
            failedIndex = failedIndex,
            awaitingUuid = awaiting,
            startedAtMs = clock.nowMs(),
        )
    }

    /** [alreadyDown] が false のときだけ、S3 のフラグを立てたうえで実際に切断する。 */
    private fun disconnectIfStillUp(alreadyDown: Boolean) {
        if (alreadyDown) return
        expectingDisconnect = true
        vpn.disconnect()
    }

    /**
     * Ruling 25: 切断が完了した（または待ち時間を過ぎた）ので次候補を起動する。
     *
     * 安全策 S2: 下層ネットワークが無いなら次候補を起動しても失敗するだけなので
     * `FailingOver` に留まる。`onTick` が毎回ここを再評価するので、
     * ネットワークが戻った時点で前進する（待ち時間は既に過ぎているため即座に）。
     */
    private fun advanceAfterFailingOver(s: FailoverState.FailingOver): FailoverState {
        if (!network.hasUnderlyingNetwork()) return s

        // 裁定59: 切替先が保留されていれば、自動切替の続き（次候補）より
        // そちらを優先する。
        // 裁定86（H2）: 有人・無人は保留した経路のものをそのまま使う
        // （[PendingConnect.unattended]）。以前は `false` 固定だったが、
        // 保留を作る経路が [onUserConnect] だけではなくなったので、固定すると
        // 無人経路（画面点灯時の再接続・枯渇後の再試行）から保留された接続が
        // 有人として起動し、裁定30 の除外が働かなくなる。
        pendingConnect?.let { pending ->
            pendingConnect = null
            val group = groupOf(pending.groupId) ?: return FailoverState.Idle
            // 開始位置の指定（一覧の行の「初回ログイン」）も保留の前後で失わない。
            // 待っているあいだにその位置のメンバーが消えていたら、先頭から
            // 繋ぎ直さずに止める（`onUserConnect` の範囲外と同じ判断。利用者が
            // 指したのとは別の接続先を勝手に起動しない）。
            if (pending.fromIndex != 0 && pending.fromIndex !in group.memberUuids.indices) {
                return FailoverState.Idle
            }
            return startCandidateFrom(
                group,
                fromIndex = pending.fromIndex,
                unattended = pending.unattended,
            )
        }

        val group = groupOf(s.groupId) ?: return FailoverState.Idle
        return startCandidateFrom(group, fromIndex = s.failedIndex + 1, unattended = true)
    }

    /** 全候補が枯渇した。指数バックオフ後に先頭から再試行する（仕様書 7.2）。 */
    private fun enterExhausted(groupId: String, attempt: Int): FailoverState =
        FailoverState.Exhausted(
            groupId = groupId,
            attempt = attempt,
            retryAtMs = clock.nowMs() + Backoff.delayMsForAttempt(attempt),
        )

    private fun onTick(): FailoverState = when (val s = state) {
        is FailoverState.Connecting -> onUnattendedPromptTimeout(s) ?: onConnectTimeout(s)
        is FailoverState.Verifying -> onUnattendedPromptTimeout(s) ?: s
        is FailoverState.Healthy -> onUnattendedPromptTimeout(s) ?: s
        is FailoverState.FailingOver -> onFailingOverTimeout(s)
        is FailoverState.Exhausted -> onExhaustedRetry(s)
        FailoverState.Idle -> state
    }

    /**
     * 裁定30: 無人運用の候補が `UserPrompt` から [userPromptWaitMs] 経っても
     * 進まない＝ダイアログが出て人の入力を待っている。誰も答えられないので
     * 認証失敗と同じ扱いにする（除外して次候補へ）。
     * 該当しなければ null を返し、呼び出し側の判定を続けさせる。
     *
     * 裁定93: ただし「誰も答えられない」が成り立つのは、**ダイアログの描画先が
     * 無いとき**だけである。裁定30 の KDoc は最初から「無人運用の候補が…誰も
     * 答えられないので」と書いていたのに、実装は有人かどうかを見ていなかった。
     * 裁定92 で TV 画面が実際にダイアログを出せるようになると、
     * **利用者がパスワードを打っている最中に10秒で候補が切り替わり、
     * ダイアログごと消える。** 現状ダイアログが出ないため表面化していなかっただけ。
     *
     * よって [dialogHostAttachedProvider] が true の間は null を返す（＝除外しない。
     * 呼び出し側の判定を続けさせる）。「無人運用のときの除外」は従来どおり効く
     * ので S1（認証失敗した候補を自動再試行から外す）は壊れない。
     *
     * **無限に待つ状態は作らない**（`onTick` の各分岐で上限が別に存在する）:
     * - `Connecting`: null を返せば [onConnectTimeout]（Ruling 22、既定45秒、
     *   `Connecting.startedAtMs` 起点）が続いて評価される。このタイムアウトは
     *   候補を除外しない（＝アカウントロックを招かない）。
     *   **裁定94 により、そこにも同じ「人が答えている最中か」のガードが入った。**
     *   その状態が有界である根拠は [awaitingHumanAuthInput] の KDoc にある。
     * - `Verifying` / `Healthy`: 元々 `s` を返すだけなのでこの判定は無関係。
     *   どちらもトンネル確立後の状態であり、疎通が失われれば
     *   [onProbeResult] が `failureThreshold` 回の失敗で切替える。
     * - `FailingOver` / `Exhausted` / `Idle`: この関数を経由しない。
     *
     * さらに、描画先が離れれば（利用者が TV 画面から離れる・消灯する）供給関数は
     * false に戻り、次の tick で従来どおり除外が効く。[userPromptSinceMs] は
     * ここでリセットしないので、そのとき既に待ち時間を過ぎていれば即座に除外される
     * （「ダイアログを出したまま人が居なくなった」＝まさに裁定30 が想定した状況）。
     *
     * 裁定94（報告書 C2 の訂正）: [currentCandidateUnattended] のガードはここに
     * ある。以前は [userPromptSinceMs] の arming 側にあり、有人候補ではこの関数が
     * `since == null` で必ず null を返していた。判定の結果は等価だが、
     * [userPromptSinceMs] が「`UserPrompt` が届いているか」を素直に表すようになり、
     * 裁定94 が [onConnectTimeout] 側でその事実を使えるようになった。
     */
    private fun onUnattendedPromptTimeout(s: FailoverState): FailoverState? {
        val since = userPromptSinceMs ?: return null
        // Ruling 21: 除外するのは「人が見ている保証が無い」候補だけ。ユーザー自身が
        // 画面の前で接続を指示した候補は、いつまでダイアログの前にいてもよい。
        if (!currentCandidateUnattended) return null
        if (dialogHostAttachedProvider()) return null
        val waitMs = groupIdOf(s)?.let { userPromptWaitMs(it) } ?: USER_PROMPT_WAIT_MS
        if (clock.nowMs() - since < waitMs) return null
        return onUnattendedUserPrompt(s)
    }

    /**
     * 裁定33: 実際に使う `UserPrompt` の待ち時間。除外を伴うこの判定は、除外を伴わない
     * 接続タイムアウト（Ruling 22）より先に発火してほしい。後になると認証情報が
     * 間違っている候補が除外されずに切り替わり、次の巡回でまた試されてサーバ側の
     * アカウントロックを招く（S1 が存在する理由）。`connectTimeoutSec` は利用者が
     * 変更できるので、コメントでの約束ではなく実装で上限を絞る。
     *
     * ただし2つの期限は起点が違うため、この関係は**無条件には**成り立たない:
     * `onConnectTimeout` の期限は候補の `Connecting.startedAtMs`（候補が接続を
     * 開始した瞬間）を起点とするのに対し、この関数が返す待ち時間は
     * `userPromptSinceMs`（`UserPrompt` が実際に届いた瞬間）を起点とする。
     * 順序が保証されるのは **`UserPrompt` が候補開始から `connectTimeoutSec / 2`
     * 以内に届いた場合**だけである。実際の認証フォームは接続直後（TLS 接続確立
     * 直後）に届くため実務上はほぼ常に成立するが、無条件の保証ではない。
     */
    private fun userPromptWaitMs(groupId: String): Long =
        minOf(USER_PROMPT_WAIT_MS, configOf(groupId).connectTimeoutSec * 1_000L / 2)

    /** Ruling 25: 切断完了の確認が来ないまま [DISCONNECT_WAIT_MS] を過ぎたら先へ進む。 */
    private fun onFailingOverTimeout(s: FailoverState.FailingOver): FailoverState {
        if (clock.nowMs() - s.startedAtMs < DISCONNECT_WAIT_MS) return s
        // 裁定72-fix(F2): 確認を諦めて先へ進む。s.awaitingUuid のスレッドが実際に
        // 終了した保証はまだ無い（DISCONNECT_WAIT_MS の KDoc 参照）。この先
        // Idle/Exhausted に至っても、対応する Disconnected が届くまではその
        // 前提を信用しない（unconfirmedAbandonedUuid のドキュメント参照）。
        unconfirmedAbandonedUuid = s.awaitingUuid
        return advanceAfterFailingOver(s)
    }

    /**
     * Ruling 22: `Connecting` に無期限に留まらないための上限。
     * ブラックホール宛先など RST が返らない相手だと、`Connected`/`Disconnected` の
     * どちらも来ないまま既存コアが OS の TCP タイムアウトまで沈黙し、
     * 状態機械もそれに引きずられて何もしなくなる。`connectTimeoutSec` を超えたら
     * 次候補へ進める。タイムアウトはネットワーク障害であり認証失敗ではないので、
     * S1 の除外はしない（10分後には繋がるかもしれない候補を焼き切らない）。
     * alreadyDown = false であり、Ruling 25 により切断を要求してから完了を
     * 待って次候補へ進む。
     *
     * 裁定94: ただし [awaitingHumanAuthInput] が成立している間は発火させない。
     * Ruling 22 がここに上限を置くのは**死んだサーバの前で永久に固まらない**ため
     * であり、人が入力欄の前で文字を打っている状態は「固まっている」ではない
     * ——上限を課す理由がそもそも当てはまらない。Fire TV の D-pad ＋
     * ソフトキーボードでユーザー名とパスワードを打つのに既定45秒は足りない。
     *
     * **この経路は「コードから到達可能」であって「実機で観測した」ではない。**
     * 裁定92・93 を入れた実機で認証ダイアログを3秒・45秒・176秒の時点で確認した
     * 測定が1件あるが、そこではダイアログは消えていない。ただし
     * **ダイアログが出ていることは状態機械が諦めていないことの証拠にならない**:
     * この上限が発火しても、画面に出ているダイアログを畳む経路は存在しないためで
     * ある（`vpn.disconnect()`＝`ACTION_STOP_VPN` は `stopVPN()` を呼ぶだけで
     * `OpenVpnService.mDialog` に触れず、VPN スレッドは
     * `UserDialog.waitForResponse()` で停まったまま。`AuthFormHandler` が
     * `mAlert.dismiss()` を呼ぶのは `onStop`＝Activity の `onPause` 経由か、
     * 利用者自身がボタンを押したときだけ。[DISCONNECT_WAIT_MS] の KDoc の
     * follow-up F4 と同じ事情）。次候補が `promptUser` に来れば
     * `setDialog` で `mDialog` が差し替わり新しいダイアログが重ねて描画されるので、
     * 外から見た「ダイアログが出ている」は**発火した場合と発火しなかった場合を
     * 区別できない**。したがってこの観測でこの経路を否定することもできない
     * （逆に、発火したと主張することもできない）。
     */
    private fun onConnectTimeout(s: FailoverState.Connecting): FailoverState {
        if (awaitingHumanAuthInput()) return s
        val timeoutMs = configOf(s.groupId).connectTimeoutSec * 1_000L
        if (clock.nowMs() - s.startedAtMs < timeoutMs) return s
        return failOver(s.groupId, s.candidateIndex, alreadyDown = false)
    }

    /**
     * 裁定94: **いま人が認証ダイアログに答えている最中か。**
     *
     * 成立条件は2つで、**両方が同時に成り立っている間だけ**真である:
     *
     * 1. [dialogHostAttachedProvider]（裁定93）: ダイアログの描画先が bind されて
     *    いる。裁定92 以降これは「TV 画面が前面にある」と一致する
     * 2. [userPromptSinceMs] が非 null: コアが `UserPrompt` で止まっている
     *    （＝認証フォームを処理中であり、次の状態へ進んでいない）
     *
     * **片方だけでは足りない**（どちらを落としても実際に事故になる）:
     *
     * - 1 だけで止めると、TV 画面を開いたまま**認証に到達してすらいない**
     *   死んだサーバの前で永久に待つ。Ruling 22 が存在する理由そのものを失う
     * - 2 だけで止めると、誰も見ていない（描画先が無い＝ダイアログは出ていない、
     *   Fire TV には通知シェードも無い）のに永久に待つ
     *
     * **この状態が無限に続かない根拠**（時間しきい値を足して解決してはならない。
     * 裁定78 で誤りと判明した手口であり、「打つのが遅い人」を切ることになる）:
     *
     * - 条件 2 が解ける: 利用者が答えれば `Authenticating`、間違えて切断されれば
     *   `Disconnected` が届き、[onVpnState] が [userPromptSinceMs] を null に戻す。
     *   そこで Ruling 22 の期限が復活する。**このとき起点は「人が答え終わった
     *   瞬間」へ1回だけ置き直される**（[onVpnState] 末尾。理由と有界性はそこの
     *   コメント）。以後はサーバが `connectTimeoutSec` 沈黙すれば従来どおり畳む
     * - 条件 2 が解ける: 利用者がダイアログを閉じる（BACK / キャンセル）と
     *   コアは認証をやめて切断へ進むので、上と同じ経路で解ける
     * - 条件 1 が解ける: 利用者が席を離れれば必ず画面が消え、`TvMainActivity` の
     *   `onPause` が [DialogHostTracker] を減らす。加えて裁定44 により
     *   `ACTION_SCREEN_OFF` で `UserDisconnect` が飛び `Idle` へ落ちるので、
     *   そもそもこの判定を通る状態が残らない（`Idle` は `onTick` でこの経路を
     *   通らない）。ダイアログは Activity と同じウィンドウに出るので、
     *   ダイアログを出したこと自体で `onPause` にはならない。
     *   **こちらの経路では起点の置き直しは起きない**（置き直しの条件に
     *   [dialogHostAttachedProvider] が入っている）ので、既に期限を過ぎていれば
     *   次の tick で即座に前進する。「ダイアログを出したまま人が居なくなった」は
     *   まさに上限を課すべき状況である
     * - プロセスが死ねば状態そのものが消える（Ruling 18 の復帰は無人経路であり、
     *   復帰後の候補は `unattended = true` で始まる）
     *
     * つまり**有界性の根拠は時間ではなく「人が居る」という観測そのもの**であり、
     * 人が居なくなったことは必ず観測される（消灯 → `onPause` ＋ 裁定44）。
     *
     * 消費電力: この間 [requiresCpuAwake] は `Connecting` なので true を返し、
     * 裁定43 の `PARTIAL_WAKE_LOCK` を保持し続ける。ただし条件 1 が成り立つのは
     * 画面が点いている＝端末が interactive な場面だけであり（裁定44 により消灯中は
     * 必ず `Idle`）、欠陥17 で問題になった「消灯中に起き続ける」形にはならない。
     *
     * S1 は壊れない: ここで変えたのは**除外を伴わない** Ruling 22 の経路だけで
     * ある。認証失敗による除外（[onDisconnected] の Ruling 23 判定、および
     * 無人候補に対する [onUnattendedPromptTimeout]）はこの値に関係なく従来どおり
     * 効く。
     */
    private fun awaitingHumanAuthInput(): Boolean =
        userPromptSinceMs != null && dialogHostAttachedProvider()

    /**
     * 裁定86（H2）: 再試行の前に [stopBeforeStarting] を通す。この経路も
     * [unconfirmedAbandonedUuid] を一度も読まずに [startCandidateFrom] を
     * 直接呼んでいた（`Exhausted` に至る直前が [onFailingOverTimeout] だった
     * 場合、放棄した候補のスレッドが生きている可能性がある）。
     */
    private fun onExhaustedRetry(s: FailoverState.Exhausted): FailoverState {
        if (clock.nowMs() < s.retryAtMs) return s
        val group = groupOf(s.groupId) ?: return FailoverState.Idle

        // 裁定35b: グループの全メンバーが除外済みなら再試行しない。除外は認証失敗を
        // 意味し、無人で再試行してよい相手ではない（S1 が存在する理由そのもの）。
        // ここで startCandidateFrom を呼んでも1件も vpn.connect() されずに即座に
        // enterExhausted へ戻るだけなので、exhaustionAttempt を進めず retryAtMs も
        // 更新せず、Exhausted のまま留まる（接続試行ゼロでバックオフだけが
        // 無限に回る空回りを断つ）。回復はユーザー操作（onUserConnect）に委ねる。
        if (group.memberUuids.isNotEmpty() && group.memberUuids.all { it in _excludedUuids }) {
            return s
        }

        // 再試行時は認証失敗による除外を維持したまま先頭から試す。
        // 次に枯渇したときの attempt を1つ進める。ここでリセットしてはならない
        // （リセットすると2回目の枯渇でも attempt が 0 に戻り、バックオフが伸びない）。
        // 裁定86（H2）: 進めるのは stopBeforeStarting の前でよい。切断待ちを挟んだ
        // 場合も、その後 advanceAfterFailingOver が起動して次に枯渇したときの
        // attempt は同じ値になる（バックオフは伸び続ける）。
        exhaustionAttempt = s.attempt + 1

        stopBeforeStarting(s.groupId, unattended = true)?.let { return it }

        return startCandidateFrom(group, fromIndex = 0, unattended = true)
    }

    private fun onUnderlyingNetworkChanged(available: Boolean): FailoverState {
        if (available) {
            // 下層ネットが復帰した。次のプローブを即座に実行する。
            probeImmediatelyOnNetworkRecovery = true
        }
        return state
    }

    private fun groupOf(groupId: String): FailoverGroup? = groups.firstOrNull { it.id == groupId }

    private fun configOf(groupId: String): FailoverConfig =
        groupOf(groupId)?.config ?: FailoverConfig()

    private fun groupIdOf(s: FailoverState): String? = when (s) {
        is FailoverState.Connecting -> s.groupId
        is FailoverState.Verifying -> s.groupId
        is FailoverState.Healthy -> s.groupId
        is FailoverState.FailingOver -> s.groupId
        is FailoverState.Exhausted -> s.groupId
        FailoverState.Idle -> null
    }

    private fun candidateIndexOf(s: FailoverState): Int? = when (s) {
        is FailoverState.Connecting -> s.candidateIndex
        is FailoverState.Verifying -> s.candidateIndex
        is FailoverState.Healthy -> s.candidateIndex
        is FailoverState.FailingOver -> s.failedIndex
        is FailoverState.Exhausted -> null
        FailoverState.Idle -> null
    }

    private companion object {
        /**
         * Ruling 25: 切断完了の確認を待つ上限。既存コアの `killVPNThread(true)` は
         * スレッド join を 1000ms で打ち切るので、正常終了ならその内に
         * `Disconnected` が届く。届かないまま待ち続けて機能停止するよりは、
         * 諦めて次候補へ進むほうがマシである。
         *
         * Ruling 28（裁定39 で訂正）: 当初は「`disconnect()` は `stopService` であり、
         * `BIND_AUTO_CREATE` の bind が残っている間サービスが破棄されずアプリ層で
         * 打てる追加の手が無い」と書いていたが、**打てる手はあった。** 裁定39 で
         * `vpn.disconnect()`（`OpenConnectVpnController`）は `stopService` をやめ、
         * `OpenVpnService.ACTION_STOP_VPN` を `startService` で送るようにした。
         * この経路はサービスが破棄されるかどうかに依存せず、bind の有無に関係なく
         * `onStartCommand` を経由して確実に `stopVPN()` を呼ぶ。
         *
         * それでも救えない経路が一つ残る（follow-up F4、今回は対象外）:
         * 認証ダイアログで `UserDialog.waitForResponse()` にブロックしたスレッドは
         * `stopVPN()`（`mOC.cancel()` と `mMainloopLock.notify()` だけ）では
         * 解放されない。したがって、この待ち時間の上限で諦めた場合、確認が
         * 来なかった原因がダイアログ待ちのブロックであれば、それ以上アプリ層で
         * 打てる手は無く、既存コアに 1000ms ではなく 3000ms を与えた分だけ
         * 成功率が上がるだけで、放棄した候補が後からトンネルを張る可能性
         * そのものは残る。
         */
        const val DISCONNECT_WAIT_MS = 3_000L

        /**
         * 裁定30: `UserPrompt` から次の状態へ進むのを待つ上限の既定値。
         * これを超えたらダイアログが出て人の入力を待っているとみなす
         * （無人運用では誰も答えられない）。自動入力の経路はローカル処理だけ
         * なのでミリ秒で `Authenticating` へ進む。
         *
         * 裁定33: 実際に使う値は [userPromptWaitMs] であり、`connectTimeoutSec`
         * （利用者が変更できる）の半分より必ず小さくなるよう実装で上から抑える。
         * これにより、`UserPrompt` が候補開始から `connectTimeoutSec / 2` 以内に
         * 届く通常のケースでは、除外を伴うこの判定が除外を伴わない接続タイムアウト
         * より先に発火する（詳細と条件付きである理由は [userPromptWaitMs] 参照）。
         * 逆に接続タイムアウトが先に発火すると、候補が除外されずに（Ruling 22 の
         * 意味論で）切り替わってしまい、認証情報が間違っている候補を何度も
         * 試してアカウントロックを招く。
         */
        const val USER_PROMPT_WAIT_MS = 10_000L
    }
}
