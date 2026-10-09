package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * [FailoverController] の**不変条件**テスト（手書きシナリオではない唯一のスイート）。
 *
 * 既存の11ファイル・406件はすべて「この順番で流したらこうなる」を1本ずつ書いたもので、
 * **書いた人が思いついた組み合わせしか守らない。** ここではイベント列を機械的に生成して
 * 流し、**どの順番でも破れてはならない性質**を**毎イベント後に**検査する。
 *
 * 依存は増やさない（kotest / jqwik は使わない）。[Random] のシードを固定配列で持ち、
 * 破れたときは**その手順をそのまま再現手順として出力する**（全列と、削れるところまで
 * 削った最小列の両方）。
 *
 * 生成するのは**実際に起きうるイベントだけ**である（詳細は [Runner.nextSteps] と
 * [allowedCoreStates]）。存在しないイベントを作ると偽の失敗になり、判断する人の時間を
 * 無駄にする。
 *
 * 製品コードの private な内部（`userPromptSinceMs` / `candidateConnectedAtMs` /
 * `pendingConnect` / `slowLinkLaps` / `currentCandidateUuid`）は触れないので、
 * **観測できる事実だけから写した影の模型**を [Runner] が持つ（写し方は各フィールドの
 * コメント）。影が製品より「甘い」側に寄る箇所は必ず甘い側に寄せてある——偽の失敗を
 * 出さないためである。
 */
class FailoverControllerInvariantTest {

    // ------------------------------------------------------------------ 手順

    /**
     * 生成される1手。イベントの投入と供給関数の反転の両方を含む。
     * すべて**絶対値**で持つ（差分ではない）ので、途中の手を落としても残りの手の
     * 意味が変わらない——最小列への削り込み（[shrink]）がこの性質に依存している。
     */
    private sealed interface Step {
        fun render(): String
    }

    private data class Dispatch(val event: FailoverEvent) : Step {
        override fun render(): String = "controller.handle(FailoverEvent.$event)"
    }

    /**
     * 実運用でプローブ結果が届くのは `FailoverService` のティックが
     * [FailoverController.shouldProbeNow] に許されたときだけである。
     * 無条件の `ProbeResult` は**起きえないイベント**なので生成しない。
     */
    private data class ProbeIfDue(val reachable: Boolean) : Step {
        override fun render(): String =
            "if (controller.shouldProbeNow()) " +
                "controller.handle(FailoverEvent.ProbeResult(reachable = $reachable))"
    }

    private data class AdvanceClock(val ms: Long) : Step {
        override fun render(): String = "clock.advance(${ms}L)"
    }

    private data class SetGroups(val groups: List<FailoverGroup>) : Step {
        override fun render(): String = "groups = " + renderGroups(groups)
    }

    private data class SetNetwork(val available: Boolean) : Step {
        override fun render(): String = "network.available = $available"
    }

    private data class SetDialogHost(val attached: Boolean) : Step {
        override fun render(): String = "dialogHostAttached = $attached"
    }

    private data class SetSlow(val slow: Boolean) : Step {
        override fun render(): String = "slowLink = $slow"
    }

    private data class SetFirstLogin(val uuid: String, val needs: Boolean) : Step {
        override fun render(): String =
            if (needs) "needsFirstLogin += \"$uuid\"" else "needsFirstLogin -= \"$uuid\""
    }

    private data object SlowSettingsChanged : Step {
        override fun render(): String = "controller.onSlowLinkSettingsChanged()"
    }

    // ------------------------------------------------------------ 破れた記録

    private data class Violation(
        val invariant: String,
        val kind: String,
        val detail: String,
        val seed: Long,
        val sequence: Int,
        val config: FailoverConfig,
        val initialGroups: List<FailoverGroup>,
        val steps: List<Step>,
        val stateAtFailure: String,
        val worldAtFailure: String,
    ) {
        val key: String get() = "$invariant/$kind"
    }

    // ------------------------------------------------------------------ 実行

    /**
     * 1本のイベント列を生成しながら流し、毎イベント後に不変条件を検査する器。
     *
     * 偽物は `Fakes.kt` の [FakeClock] / [FakeVpnController] / [FakeNetworkGate] を
     * そのまま使う。供給関数（ダイアログの描画先・初回ログイン・低速判定・グループ定義）は
     * 素のラムダで渡すので、新しい偽物は足していない。
     */
    private class Runner(
        val seed: Long,
        val sequence: Int,
        val config: FailoverConfig,
        val initialGroups: List<FailoverGroup>,
        /**
         * 実運用で普通に起きる並びの出現率を上げるか（[biasedSteps]）。半分の列は
         * 一様のまま回す——偏りを入れると `Healthy` の先に届く代わりに、一様版でしか
         * 踏めない並びを取りこぼすので、両方を混ぜる。
         */
        val biased: Boolean = true,
    ) {
        val clock = FakeClock(1_000L)
        val vpn = FakeVpnController()
        val network = FakeNetworkGate(available = true)

        var groups: List<FailoverGroup> = initialGroups
        var dialogHostAttached = false
        var slowLink = false
        val needsFirstLogin = mutableSetOf<String>()

        /** この dispatch のあいだに `slowLinkProvider` が問われた回数と、返した答え。 */
        private var slowAsks = 0
        private var slowAnsweredTrue = false

        val controller = FailoverController(
            groupsProvider = { groups },
            clock = clock,
            vpn = vpn,
            network = network,
            dialogHostAttachedProvider = { dialogHostAttached },
            needsFirstLoginProvider = { uuid -> uuid in needsFirstLogin },
            slowLinkProvider = { onSlowLinkAsked() },
        )

        private fun onSlowLinkAsked(): Boolean {
            slowAsks++
            if (slowLink) slowAnsweredTrue = true
            return slowLink
        }

        // ---- 影の模型（製品コードの private を観測できる事実から写したもの） ----

        /**
         * `currentCandidateUuid` の写し。**公開されている状態から導く**ので推測ではない:
         * `Connecting` / `Verifying` / `Healthy` では「現グループの `candidateIndex` の
         * メンバー」（`reconcileCandidateIdentity` が添字と uuid の一致を保つ）、
         * `FailingOver` では `awaitingUuid`（`failOver` も
         * `stopBeforeStarting` もそこへ `currentCandidateUuid` を入れる）。
         * `Idle` / `Exhausted` では製品側も直前の値を保持するので、こちらも保持する。
         */
        var candidateUuid: String? = null

        /**
         * `userPromptSinceMs != null` の写し。立つのは現候補の `UserPrompt` が届いたとき、
         * 落ちるのは (1) 現候補の別の core 状態が届いたとき、(2) 新しい候補を起動したとき
         * （`startCandidateFrom`）、(3) `FailingOver` に入ったとき（`failOver`）である。
         * (3) は製品より広い（`stopBeforeStarting` は消さない）——**影が甘い側**に寄る
         * だけなので偽の失敗にはならない。
         */
        var promptOpen = false

        /** `candidateConnectedAtMs` の写し。現候補の `Connected` で入り、起動で消える。 */
        var connectedAtMs: Long? = null

        /**
         * `pendingConnect.unattended` の写し。true は「利用者の明示操作が切断待ちを
         * 挟んで持ち越されている」ことを意味する（裁定86 H2）。
         */
        var pendingAttended: Boolean? = null

        /**
         * `slowLinkLaps` の写し。メンバー列・**前へ進む**速度起因の切替の回数・
         * 一周が止まったか（最良へ戻す判断を下したか）。止まったかは製品の
         * `LapRecord.stoppedAtMs() != null` に当たるが、これも**観測できる事実から
         * 導く**（導き方は I5 のコメント）。
         */
        data class ShadowLap(
            val members: List<String>,
            val forward: Int,
            val stopped: Boolean,
            /** 前進か戻りか分類できない着地があった。前進の回数は下限でしかない。 */
            val uncertain: Boolean = false,
        )

        // **この写しは再開（rearmAfterLapMin）を模さない。** この不変条件のコントローラは
        // `slowLinkSettingsProvider` を渡さないので既定の `rearmAfterLapMin = 0`
        // ＝再開しない。よって製品が一周の記録を時間で消すことは無く、`laps` は
        // 製品の記録とずれない。この前提に依存している——将来この生成器へ設定を
        // 配線する、または既定値を変えると、製品が記録を消すのに `laps` は残り、
        // I5 の「止まった後の問い合わせ」が偽の失敗を出す。そのときは再開の
        // 写し（経過時間での `laps` の削除）をここに足すこと。

        val laps = mutableMapOf<String, ShadowLap>()

        /**
         * 速度起因の切替を始めた直後の（グループ, 離れた候補, そのときのメンバー列）。
         * 次に起動される候補の位置で前進か戻りかを分類する（I5）ために持つ。
         * グループごとではなく一つだけなのは、切断確認を待つ切替は同時に一つしか
         * 無いから（状態は一つ）。
         */
        private var pendingSlow: PendingSlow? = null

        /**
         * [aheadStartable]: 切替を**決めた時点**で、離れる候補の後ろに無人で起動できる
         * 候補が一つでもあったか。前へ進む切替は、製品が行き先の門でそれを確かめて
         * から始める（無ければ前進はしない）。よって後ろが無かった切替は戻す切替で、
         * 後ろへ着地したら破れである（I5）。予算は使わない。
         */
        data class PendingSlow(
            val groupId: String,
            val fromUuid: String,
            val members: List<String>,
            val aheadStartable: Boolean,
        )

        /** 製品の [FailoverController] が「無人で起動してよい」とする候補と同じ判定。 */
        private fun startable(uuid: String, excluded: Set<String>): Boolean =
            uuid !in excluded && uuid !in needsFirstLogin

        /**
         * uuid ごとの「既存コアのセッション」。キーがあるのは一度でも起動した uuid だけ。
         * 値はその uuid について最後に流した core 状態（null は起動直後で無通知）。
         * 起きえない core 状態列を生成しないために使う。
         */
        val sessions = mutableMapOf<String, VpnCoreState?>()

        /** グループ定義を変えた直後は、状態の添字が次の dispatch まで古い（設計どおり）。 */
        private var staleGroups = false

        val violations = mutableListOf<Violation>()
        val steps = mutableListOf<Step>()
        var dispatchCount = 0
        val coverage = mutableMapOf<String, Int>()

        private fun mark(name: String) {
            coverage[name] = (coverage[name] ?: 0) + 1
        }

        // ------------------------------------------------------------ 生成

        fun generate(rnd: Random, maxSteps: Int) {
            var n = 0
            while (n < maxSteps && violations.isEmpty()) {
                for (step in nextSteps(rnd)) {
                    execute(step)
                    n++
                    if (violations.isNotEmpty()) return
                }
            }
        }

        /**
         * 次の1手（まれに2手）を引く。**実際に起きうるものだけ**を引く:
         * - `VpnStateChanged` は「一度でも起動した uuid」についてのみ、しかも
         *   その uuid のセッションが次に取りうる core 状態だけ（[allowedCoreStates]）。
         *   uuid は現候補と**別候補（切替前の古い通知）**の両方を引く。null は
         *   本番の橋渡しが決して入れないので引かない。
         * - `ProbeResult` は [FailoverController.shouldProbeNow] が許したときだけ。
         * - グループ定義の変更は「利用者が一覧を編集した」に相当する操作だけ。
         */
        fun nextSteps(rnd: Random): List<Step> {
            if (biased) biasedSteps(rnd)?.let { return it }
            var r = rnd.nextInt(TOTAL_WEIGHT)
            fun take(w: Int): Boolean {
                if (r < w) return true
                r -= w
                return false
            }
            return when {
                take(20) -> listOf(Dispatch(FailoverEvent.Tick))
                take(18) -> listOf(AdvanceClock(ADVANCE_AMOUNTS[rnd.nextInt(ADVANCE_AMOUNTS.size)]))
                take(24) -> vpnStateSteps(rnd)
                take(10) -> listOf(ProbeIfDue(rnd.nextInt(100) < 35))
                take(4) -> listOf(
                    Dispatch(
                        FailoverEvent.UserConnectGroup(
                            groupId = GROUP_IDS[rnd.nextInt(GROUP_IDS.size)],
                            fromIndex = if (rnd.nextInt(100) < 70) 0 else rnd.nextInt(6) - 1,
                        )
                    )
                )
                take(4) -> listOf(
                    Dispatch(FailoverEvent.AutoConnectGroup(GROUP_IDS[rnd.nextInt(GROUP_IDS.size)]))
                )
                take(3) -> listOf(Dispatch(FailoverEvent.UserDisconnect))
                take(5) -> networkSteps(rnd)
                take(5) -> listOf(
                    SetDialogHost(if (rnd.nextInt(100) < 85) !dialogHostAttached else dialogHostAttached)
                )
                take(6) -> listOf(SetSlow(rnd.nextInt(100) < 55))
                take(5) -> listOf(
                    SetFirstLogin(MEMBER_POOL[rnd.nextInt(MEMBER_POOL.size)], rnd.nextInt(100) < 50)
                )
                take(6) -> listOf(groupEdit(rnd))
                else -> listOf(SlowSettingsChanged)
            }
        }

        /**
         * 一様に引くと状態機械は `Idle` と `Connecting` のあたりを回るだけで、
         * `Healthy` の先（速度起因の切替＝I5 / I9 / I10 の前提）にほとんど届かない
         * （一様版の到達記録では `Healthy` は全イベントの 0.1% 未満だった）。
         * **引くイベントの種類は増やさず**、実運用で普通に起きる並び
         * （猶予明けのプローブ、健全なあいだの tick）の出現率だけを上げる。
         * 生成器の分布は探索の道具であって、検査する性質の側ではない。
         */
        private fun biasedSteps(rnd: Random): List<Step>? = when (controller.state) {
            is FailoverState.Idle ->
                // 画面点灯や利用者の操作で接続が始まるところまで進める。
                if (rnd.nextInt(100) < 40) {
                    val groupId = GROUP_IDS[rnd.nextInt(GROUP_IDS.size)]
                    if (rnd.nextInt(100) < 50) {
                        listOf(Dispatch(FailoverEvent.UserConnectGroup(groupId)))
                    } else {
                        listOf(Dispatch(FailoverEvent.AutoConnectGroup(groupId)))
                    }
                } else {
                    null
                }
            is FailoverState.Verifying ->
                // 猶予が明けてから初回プローブが届く、という実運用どおりの並び。
                if (rnd.nextInt(100) < 50) {
                    listOf(
                        AdvanceClock(config.graceAfterConnectSec * 1_000L + 1_000L),
                        ProbeIfDue(reachable = true),
                    )
                } else {
                    null
                }
            is FailoverState.Healthy ->
                if (rnd.nextInt(100) < 30) {
                    listOf(SetSlow(true), Dispatch(FailoverEvent.Tick))
                } else if (rnd.nextInt(100) < 30) {
                    listOf(Dispatch(FailoverEvent.Tick))
                } else {
                    null
                }
            is FailoverState.Connecting ->
                // 起動した候補が実際に繋がるところまで進める（コアの通常の並び）。
                if (rnd.nextInt(100) < 35) towardConnected(candidateUuid) else null
            is FailoverState.FailingOver -> {
                // 切断の確認が届いて次候補へ進む、という Ruling 25 の通常の並び。
                val awaiting = (controller.state as FailoverState.FailingOver).awaitingUuid
                if (awaiting != null && awaiting in sessions && rnd.nextInt(100) < 40) {
                    listOf(
                        Dispatch(
                            FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, awaiting)
                        )
                    )
                } else {
                    null
                }
            }
            else -> null
        }

        /**
         * その候補のセッションが `Connected` に近づく向きの、**次に起こりうる**通知。
         * 既に終了しているセッションには何も足さない（[allowedCoreStates] の規則を守る）。
         */
        private fun towardConnected(uuid: String?): List<Step>? {
            if (uuid == null || uuid !in sessions) return null
            val core = when (sessions[uuid]) {
                null -> VpnCoreState.Connecting
                VpnCoreState.Connecting, VpnCoreState.Authenticating,
                VpnCoreState.Authenticated, VpnCoreState.UserPrompt -> VpnCoreState.Connected
                else -> return null
            }
            return listOf(Dispatch(FailoverEvent.VpnStateChanged(core, uuid)))
        }

        private fun networkSteps(rnd: Random): List<Step> {
            val available = if (rnd.nextInt(100) < 85) !network.available else network.available
            // 実運用ではゲートの値が変わり、続いて通知が届く。まれに通知が来ない
            // （落としても状態機械が壊れてはならない）ので、その場合も引く。
            return if (rnd.nextInt(100) < 80) {
                listOf(SetNetwork(available), Dispatch(FailoverEvent.UnderlyingNetworkChanged(available)))
            } else {
                listOf(SetNetwork(available))
            }
        }

        private fun vpnStateSteps(rnd: Random): List<Step> {
            if (sessions.isEmpty()) return listOf(Dispatch(FailoverEvent.Tick))
            val current = candidateUuid
            val uuid = if (current != null && current in sessions && rnd.nextInt(100) < 70) {
                current
            } else {
                val keys = sessions.keys.toList()
                keys[rnd.nextInt(keys.size)]
            }
            val choices = allowedCoreStates(sessions[uuid])
            val core = choices[rnd.nextInt(choices.size)]
            return listOf(Dispatch(FailoverEvent.VpnStateChanged(core, uuid)))
        }

        private fun groupEdit(rnd: Random): Step {
            val list = groups.toMutableList()
            when (rnd.nextInt(6)) {
                0 -> if (list.isNotEmpty()) { // メンバーを足す
                    val i = rnd.nextInt(list.size)
                    val g = list[i]
                    val free = MEMBER_POOL.filter { it !in g.memberUuids }
                    if (free.isNotEmpty()) {
                        list[i] = g.copy(memberUuids = g.memberUuids + free[rnd.nextInt(free.size)])
                    }
                }
                1 -> if (list.isNotEmpty()) { // メンバーを外す
                    val i = rnd.nextInt(list.size)
                    val g = list[i]
                    if (g.memberUuids.isNotEmpty()) {
                        val at = rnd.nextInt(g.memberUuids.size)
                        list[i] = g.copy(memberUuids = g.memberUuids.filterIndexed { k, _ -> k != at })
                    }
                }
                2 -> if (list.isNotEmpty()) { // 並べ替え
                    val i = rnd.nextInt(list.size)
                    val g = list[i]
                    if (g.memberUuids.size >= 2) {
                        val a = rnd.nextInt(g.memberUuids.size)
                        val b = rnd.nextInt(g.memberUuids.size)
                        val m = g.memberUuids.toMutableList()
                        val tmp = m[a]
                        m[a] = m[b]
                        m[b] = tmp
                        list[i] = g.copy(memberUuids = m)
                    }
                }
                3 -> if (list.isNotEmpty()) { // 自動切替の可否
                    val i = rnd.nextInt(list.size)
                    list[i] = list[i].copy(autoFailoverEnabled = !list[i].autoFailoverEnabled)
                }
                4 -> { // グループを足す
                    val free = GROUP_IDS.filter { id -> list.none { it.id == id } }
                    if (free.isNotEmpty()) {
                        val members = MEMBER_POOL.take(1 + rnd.nextInt(3))
                        list += FailoverGroup(
                            id = free[0],
                            name = free[0],
                            memberUuids = members,
                            autoFailoverEnabled = true,
                            config = config,
                        )
                    }
                }
                else -> if (list.size > 1) list.removeAt(rnd.nextInt(list.size)) // グループを消す
            }
            return SetGroups(list)
        }

        // ------------------------------------------------------------ 適用

        fun execute(step: Step) {
            steps += step
            when (step) {
                is AdvanceClock -> clock.advance(step.ms)
                is SetGroups -> {
                    groups = step.groups
                    staleGroups = true
                }
                is SetNetwork -> network.available = step.available
                is SetDialogHost -> dialogHostAttached = step.attached
                is SetSlow -> slowLink = step.slow
                is SetFirstLogin ->
                    if (step.needs) needsFirstLogin += step.uuid else needsFirstLogin -= step.uuid
                SlowSettingsChanged -> {
                    controller.onSlowLinkSettingsChanged()
                    laps.clear()
                    // 記録が消えたので、消える前に始まった切替は分類できない（着地した
                    // ときの一周は別物）。捨てる。
                    pendingSlow = null
                }
                is Dispatch -> dispatch(step.event)
                is ProbeIfDue ->
                    if (controller.shouldProbeNow()) {
                        dispatch(FailoverEvent.ProbeResult(reachable = step.reachable))
                    }
            }
        }

        private fun dispatch(event: FailoverEvent) {
            val stateBefore = controller.state
            val excludedBefore = controller.excludedUuids.toSet()
            val connectsBefore = vpn.connectCalls.size
            val disconnectsBefore = vpn.disconnectCalls
            val candidateBefore = candidateUuid
            val promptBefore = promptOpen
            val hostBefore = dialogHostAttached
            val pendingBefore = pendingAttended
            val connectedAtBefore = connectedAtMs
            slowAsks = 0
            slowAnsweredTrue = false

            controller.handle(event)
            dispatchCount++
            staleGroups = false

            val stateAfter = controller.state
            val newConnects = vpn.connectCalls.drop(connectsBefore)
            val disconnects = vpn.disconnectCalls - disconnectsBefore
            val newlyExcluded = controller.excludedUuids.toSet() - excludedBefore
            val slowSwitch = slowAnsweredTrue

            // ---- I1（初回ログインの門番）--------------------------------
            // 認証情報の無い接続先は、機械が始めた経路で起動されない。利用者が
            // 明示操作で始めた場合（その指示が切断待ちを挟んで持ち越された場合を
            // 含む）は起動されてよい。
            for (uuid in newConnects) {
                if (uuid !in needsFirstLogin) continue
                val attended = event is FailoverEvent.UserConnectGroup || pendingBefore == true
                if (!attended) {
                    violate(
                        "I1", "起動/${tagOf(event)}/${tagOf(stateBefore)}",
                        "認証情報の無い $uuid を機械が始めた経路で起動した",
                    )
                }
            }

            // ---- I2（Ruling 25。切替経路は1つだけ）----------------------
            // connect の直前は「接続指示」か「FailingOver での切断確認（または
            // その待ち時間切れ）」か「既にトンネルが落ちたと分かっている瞬間」の
            // いずれか。Healthy から直接 connect に飛ぶことは無い。
            if (newConnects.isNotEmpty()) {
                val byInstruction = event is FailoverEvent.UserConnectGroup ||
                    event is FailoverEvent.AutoConnectGroup
                val coreWentDown = event is FailoverEvent.VpnStateChanged &&
                    event.state == VpnCoreState.Disconnected
                val liveTunnel = stateBefore is FailoverState.Connecting ||
                    stateBefore is FailoverState.Verifying ||
                    stateBefore is FailoverState.Healthy
                val ok = byInstruction ||
                    (stateBefore is FailoverState.FailingOver &&
                        (coreWentDown || event is FailoverEvent.Tick)) ||
                    (liveTunnel && coreWentDown) ||
                    (stateBefore is FailoverState.Exhausted && event is FailoverEvent.Tick)
                if (!ok) {
                    violate(
                        "I2", "起動/${tagOf(event)}/${tagOf(stateBefore)}",
                        "切断の確認を経ずに ${newConnects.joinToString()} を起動した",
                    )
                }
            }

            // ---- I3（裁定93/94）----------------------------------------
            // 人が答えられるダイアログが立っているあいだ、**締切だけを理由に**
            // 現候補を見捨てない（その候補への disconnect も、除外集合への追加も
            // 起きない）。利用者自身の指示（接続/切断）と、現候補についてコアが
            // 届けた別の core 状態（＝ダイアログはもう終わっている）は前提から
            // 外す。残るのは Tick / プローブ結果 / 網の通知 / 古い uuid の通知である。
            val engineDriven = when (event) {
                is FailoverEvent.Tick,
                is FailoverEvent.ProbeResult,
                is FailoverEvent.UnderlyingNetworkChanged -> true
                is FailoverEvent.VpnStateChanged -> event.uuid != null && event.uuid != candidateBefore
                else -> false
            }
            if (promptBefore && hostBefore && candidateBefore != null && engineDriven) {
                mark("人が答えている最中のエンジン起因イベント")
                if (event is FailoverEvent.Tick) mark("人が答えている最中の tick")

                // 以下の3つは**「締切だけを理由に見捨てた」ではない**ので前提から
                // 外す。最初に書いた前提（「エンジン起因のイベントなら何であれ
                // 見捨てない」）はこの3つを含んでしまっており、**広すぎた。**
                // 3つとも、機械生成が実際に踏んだ列（系統A/B/C）に対する裁定で
                // 「製品の側が正しい」と確定したものである。
                // **便宜のために狭めたのではない。** どれも「その候補をこれ以上
                // 保ち続けても人の入力が届かない」ことが先に分かっている場合である。

                // 例外(a)（系統A）: その候補がもうグループのメンバーではない。
                // 利用者がグループ定義を編集して現候補を外した（あるいはグループ
                // ごと消した）場合で、`reconcileCandidateIdentity` が次のイベントの
                // 冒頭で気づいて切断を要求する。**接続先がもう存在しないのだから
                // 見捨てるのが正しい。** 引き金は利用者の編集であり、締切ではない
                // （効き目が現れるのが次の Tick / 網の通知なので、イベント種別だけ
                // では利用者の操作と区別できない）。
                val leftTheGroup = groupIdOf(stateBefore)
                    ?.let { groupOf(it) }
                    ?.memberUuids
                    ?.contains(candidateBefore) != true

                // 例外(b)（系統C）: その候補はもう見捨てられている。`Idle` /
                // `Exhausted` には生きた候補が無く、そこで起きる disconnect は
                // 裁定86（H2）の「未確認の放棄候補の後片付け」である。**既に
                // 見捨てた候補の片付けは、新たに見捨てる行為ではない。**
                val alreadyAbandoned = stateBefore is FailoverState.Idle ||
                    stateBefore is FailoverState.Exhausted

                // 例外(c)（系統B）: トンネルが死んだ**積極的な証拠**がある。
                // プローブ連続失敗が閾値に達したときの切替で、締切（無人プロンプトの
                // 10秒・接続の45秒）とは別物である。遅さは**誤りうる判断**
                // （仕様書 6 が誤判定を列挙している）なので裁定94 で止めるが、
                // 連続失敗はトンネルが**もう無い**証拠であり、消えたトンネルに向けて
                // ダイアログを開いたままにしてもパスワードはサーバへ届かない。
                // 次候補で認証を出し直すほうが良い。この非対称は意図されたもので、
                // `FailoverController.awaitingHumanAuthInput` の KDoc と
                // `docs/MANUAL-TEST.md` 節11 にも書いてある。
                val deadTunnelEvidence = event is FailoverEvent.ProbeResult && !event.reachable

                if (!leftTheGroup && !alreadyAbandoned && !deadTunnelEvidence) {
                    mark("締切だけが残る場面")
                    if (disconnects > 0) {
                        violate(
                            "I3", "切断/${tagOf(event)}/${tagOf(stateBefore)}",
                            "人が答えているダイアログを持つ現候補 $candidateBefore に" +
                                "締切だけを理由に切断を要求した",
                        )
                    }
                    if (candidateBefore in newlyExcluded) {
                        violate(
                            "I3", "除外/${tagOf(event)}/${tagOf(stateBefore)}",
                            "人が答えているダイアログを持つ現候補 $candidateBefore を" +
                                "締切だけを理由に除外集合に入れた",
                        )
                    }
                }
            }

            // ---- I4（安全策 S2）----------------------------------------
            // 下層ネットワークが無いあいだは、エンジンの判断で候補を起動しない。
            if (!network.available && newConnects.isNotEmpty() &&
                event !is FailoverEvent.UserConnectGroup && event !is FailoverEvent.AutoConnectGroup
            ) {
                violate(
                    "I4", "起動/${tagOf(event)}/${tagOf(stateBefore)}",
                    "下層ネットワークが無いのに ${newConnects.joinToString()} を起動した",
                )
            }

            // ---- I5（仕様書 4-5。一周の予算）----------------------------
            // **速度起因の切替には2種類ある。** 前へ進む切替（予算 メンバー数−1 の対象）と、
            // 一周の終わりに最良の候補へ戻す切替（仕様書 §2-A。予算に数えない）である。
            // どちらも `bySlowLink` を立てるので、**印だけでは区別できない。**
            // かといって「戻す切替は数えない」と一括で除外すれば、予算を超えた前進も
            // 「戻す切替」に見えてしまい、I5 は破れなくなる。
            //
            // **区別は、製品の門（予算・行き先）を写して分類するのではなく、切替の
            // 結果＝次に起動された候補の位置で行う。** 門を写して分類すると、予算の
            // 判定をテスト側がもう一度書くことになる（予算を超えた製品も「戻す切替」に
            // 分類され、数える表明が発火しなくなる。実際にそうなっていた）。結果で見れば
            // 製品の内部に依存しない:
            //  (1) 離れた候補より**後ろ**へ着いた速度起因の切替は前進。一周の前進が
            //      メンバー数−1 を超えたら破れ（`予算超過`）
            //  (2) 離れた候補**以前**へ着いたものは最良へ戻す切替。一周に一度まで。
            //      前進を一度もしていない一周ではしない（比べる相手が無い）
            //  (3) 戻したあと（一周が止まったあと）に低速判定を引いて真を得たら破れ
            //      （製品は止まった一周では判定そのものを問わない）
            // 着地を観測する（切断確認を待つあいだに利用者の指示・並び替え・
            // 行き先の脱落があれば、その切替は分類せず捨てる）。
            // **限界（正直に）:** 戻す切替が前方へ着くと、前進と見分けられない場合がある。
            //  - 決めた時点で後ろに起動できる候補が**無かった**切替は、前進の門を通って
            //    いない＝戻す切替なので、後ろへ着地すれば破れ（`戻る切替が前方へ着地`）。
            //    これは予算を使わずに検出できる。
            //  - 決めた時点で後ろに候補が**あった**切替は、前進とも戻す切替ともありうる。
            //    戻す切替が（予算が尽きた一周で）後ろへ着地した場合は、回数が上限を
            //    超えて `予算超過` が出る。回数に余裕があるあいだに製品が誤って戻す切替を
            //    後ろへ向けても、前進と区別できず、害も前進と同じ（予算は超えない）で
            //    ある。これ以上は、予算の判定をテスト側が書き直さない限り検出できず、
            //    それは本末転倒なので、検出しない。
            //  - この生成器は応答時間の記録を供給しないので、戻り先は「選べる最初の
            //    メンバー」で、現候補以前に必ず収まる。記録を供給するようにするなら
            //    この分類を見直すこと。
            // **盲目の自己申告:** 分類できない着地があると、その一周は前進の数が下限に
            // なる。そうなった回数は `分類できない着地` として到達記録に出し、0 なら
            // 生成器が分類を試していないとして `生成器は不変条件の前提に到達している`
            // が落ちる。
            if (slowSwitch) {
                val groupId = (stateBefore as? FailoverState.Healthy)?.groupId
                if (groupId != null) {
                    val members = groupOf(groupId)?.memberUuids ?: emptyList()
                    laps.keys.retainAll(groups.mapTo(mutableSetOf()) { it.id })
                    val previous = laps[groupId]
                    val lap = if (previous != null && previous.members == members) {
                        previous
                    } else {
                        ShadowLap(members, forward = 0, stopped = false)
                    }
                    laps[groupId] = lap
                    if (lap.stopped) {
                        violate(
                            "I5", "止まった後の問い合わせ",
                            "グループ $groupId の一周は止まっているのに低速判定を引いて真を得た",
                        )
                    }
                    // **切替が要らなかった「戻す判断」も一周を止める。** 低速判定が真を返し
                    // ながら状態が `Healthy` のまま残るのは、`returnToBest` が戻り先を現候補
                    // と判断した場合だけである（それ以外の真は切替を作る）。しかも前進を
                    // 一度でも観測していれば（`forward > 0`）、製品の `switches() > 0` が
                    // 成り立つので、その分岐は必ず一周を止める。着地を待つ経路だけで止めたと
                    // 見なすと、再接続で先頭から始め直した一周のように「戻る先が現候補」と
                    // なる場面（切替が起きず、着地も無い）が丸ごと抜け、止まった後の問い合わせ
                    // の検出がそこで効かなくなる。`forward == 0` は検出し損ねるだけで、
                    // 偽の失敗は出さない。製品の門は写していない（観測した事実の組だけ）。
                    if (stateAfter is FailoverState.Healthy && lap.forward > 0) {
                        mark("切替なしで止まった一周")
                        laps[groupId] = lap.copy(stopped = true)
                    }
                    if (stateAfter is FailoverState.FailingOver && stateAfter.bySlowLink) {
                        mark("速度起因の切替")
                        val index = members.indexOf(candidateBefore)
                            .takeIf { it >= 0 } ?: (stateBefore as FailoverState.Healthy).candidateIndex
                        pendingSlow = PendingSlow(
                            groupId, candidateBefore ?: "", members,
                            aheadStartable = members.indices.any {
                                it > index && startable(members[it], excludedBefore)
                            },
                        )
                    }
                }
            }
            pendingSlow?.let { (groupId, fromUuid, membersThen, aheadStartable) ->
                val members = groupOf(groupId)?.memberUuids
                // **このグループのメンバーへの起動だけが着地**。別グループのメンバーへの
                // 起動で枠を使い切らない（使い切ると、このグループでの着地が検査を免れる）。
                val landed = if (members != null) newConnects.firstOrNull { it in members } else null
                // 利用者の指示が切断待ちを挟んで持ち越されると、製品は進行中の
                // `FailingOver` から速度起因の印を下ろす（`bySlowLink = false`。起きなく
                // なった切替の理由を残さないため）。**印が下りたことが、観測できる
                // 「この切替は指示に取って代わられた」の証拠**で、以後の着地は指示の
                // 行き先なので分類しない。指示のイベントそのものを見て捨てる方式は、
                // 何もしない指示（範囲外の開始位置など）まで拾って、製品が続けている
                // 切替を見失う。
                if (stateBefore is FailoverState.FailingOver && stateBefore.bySlowLink &&
                    stateAfter is FailoverState.FailingOver && !stateAfter.bySlowLink
                ) {
                    pendingSlow = null
                    return@let
                }
                if (landed != null && members == membersThen) {
                    val from = members.indexOf(fromUuid)
                    val to = members.indexOf(landed)
                    val previous = laps[groupId]
                    val lap = if (previous != null && previous.members == members) {
                        previous
                    } else {
                        ShadowLap(members, forward = 0, stopped = false)
                    }
                    // 離れた候補以前に起動できる候補が無いときは、製品が前方へ探すしか
                    // ない（現候補が初回ログイン未了になった等）。分類できないので捨てる。
                    val classifiable = from >= 0 && to >= 0 &&
                        (to <= from || (0..from).any { startable(members[it], excludedBefore) })
                    if (from >= 0 && to >= 0 && !classifiable) {
                        // 前進の回数が数えられなかったので、前進が無いことを前提にする検査
                        // （「前進なしで戻る」）は以後この一周では行わない。
                        mark("分類できない着地")
                        laps[groupId] = lap.copy(uncertain = true)
                    } else if (classifiable && to > from) {
                        // 決めた時点で後ろに起動できる候補が無かった切替は前進ではない。
                        // 前進の門（行き先があること）を通っていないので、後ろへ着地したら
                        // 戻る切替が前方へ進んだのである。**予算を使わずに**これだけは
                        // 検出できる（予算の余裕で前進と見分けがつかない形を塞ぐ）。
                        if (!aheadStartable) {
                            violate(
                                "I5", "戻る切替が前方へ着地",
                                "グループ $groupId: 決めた時点で $fromUuid の後ろに起動できる候補が" +
                                    "無かったのに、後ろの $landed（位置 $to）へ着地した",
                            )
                        }
                        val count = lap.forward + 1
                        laps[groupId] = lap.copy(forward = count)
                        if (count > members.size - 1) {
                            violate(
                                "I5", "予算超過",
                                "グループ $groupId の前へ進む速度起因の切替が $count 回目" +
                                    "（メンバー ${members.size} 件、一周の上限 ${members.size - 1} 回）",
                            )
                        }
                    } else if (classifiable) {
                        mark("一周の終わり")
                        mark("最良へ戻る切替")
                        if (lap.stopped) {
                            violate(
                                "I5", "戻る切替が二度",
                                "グループ $groupId の一周で最良へ戻る切替が二度目に着地した",
                            )
                        }
                        if (lap.forward == 0 && !lap.uncertain) {
                            violate(
                                "I5", "前進なしで戻る",
                                "グループ $groupId は前へ進む切替を一度もしていないのに、" +
                                    "最良へ戻る切替が着地した",
                            )
                        }
                        laps[groupId] = lap.copy(stopped = true)
                    }
                }
                // 切断を待っている（FailingOver）あいだだけ持つ。Exhausted や Idle へ落ちたら、
                // その後の起動（利用者の指示や枯渇後の再試行）は別の出来事である。
                val stillWaiting = stateAfter is FailoverState.FailingOver && stateAfter.groupId == groupId
                if (landed != null || !stillWaiting) {
                    pendingSlow = null
                }
            }

            // ---- I6（低速判定を引くのは Healthy のときだけ）-------------
            if (slowAsks > 0 && stateBefore !is FailoverState.Healthy) {
                violate(
                    "I6", "問い合わせ/${tagOf(event)}/${tagOf(stateBefore)}",
                    "状態が ${tagOf(stateBefore)} なのに slowLinkProvider を $slowAsks 回引いた",
                )
            }

            // ---- I7（安全策 S1）----------------------------------------
            for (uuid in newConnects) {
                if (event !is FailoverEvent.UserConnectGroup && uuid in excludedBefore) {
                    violate(
                        "I7", "起動/${tagOf(event)}/${tagOf(stateBefore)}",
                        "除外集合に入っている $uuid を起動した（除外を消すのは利用者の接続指示だけ）",
                    )
                }
            }

            // ---- I8（状態の整合）---------------------------------------
            if (!staleGroups) {
                when (val s = stateAfter) {
                    is FailoverState.Connecting -> checkIndex(s.groupId, s.candidateIndex, event, stateBefore)
                    is FailoverState.Verifying -> checkIndex(s.groupId, s.candidateIndex, event, stateBefore)
                    is FailoverState.Healthy -> checkIndex(s.groupId, s.candidateIndex, event, stateBefore)
                    is FailoverState.FailingOver -> {
                        val group = groupOf(s.groupId)
                        // -1 は「次は先頭から」を意味する正規の値（stopBeforeStarting /
                        // reconcileCandidateIdentity が入れる）。
                        if (group != null && s.failedIndex !in -1 until group.memberUuids.size) {
                            violate(
                                "I8", "failedIndex/${tagOf(event)}/${tagOf(stateBefore)}",
                                "FailingOver.failedIndex=${s.failedIndex} が ${s.groupId} の範囲" +
                                    "（-1..${group.memberUuids.size - 1}）の外",
                            )
                        }
                    }
                    is FailoverState.Exhausted -> Unit
                    FailoverState.Idle -> Unit
                }
            }

            // ---- I9（速度起因の印は速度起因の経路だけが付ける）-----------
            val after = stateAfter as? FailoverState.FailingOver
            if (after != null && after.bySlowLink) {
                val sameSwitch = stateBefore is FailoverState.FailingOver &&
                    stateBefore.bySlowLink &&
                    stateBefore.startedAtMs == after.startedAtMs
                if (!sameSwitch && !slowSwitch) {
                    violate(
                        "I9", "印/${tagOf(event)}/${tagOf(stateBefore)}",
                        "速度起因ではない経路が作った FailingOver に bySlowLink が付いている",
                    )
                }
            }

            // ---- I10（接続直後の猶予）----------------------------------
            if (slowSwitch) {
                val grace = config.graceAfterConnectSec * 1_000L
                if (connectedAtBefore == null || clock.nowMs() - connectedAtBefore < grace) {
                    val since = if (connectedAtBefore == null) {
                        "Connected を一度も観測していない"
                    } else {
                        "Connected から ${clock.nowMs() - connectedAtBefore}ms"
                    }
                    violate(
                        "I10", "猶予/${tagOf(event)}/${tagOf(stateBefore)}",
                        "$since で速度起因の切替が起きた（猶予 ${grace}ms）",
                    )
                }
            }

            // ---- 影の更新 ----------------------------------------------
            if (event is FailoverEvent.VpnStateChanged) {
                val uuid = event.uuid
                if (uuid != null && uuid in sessions) sessions[uuid] = event.state
                if (uuid == null || uuid == candidateBefore) {
                    promptOpen = event.state == VpnCoreState.UserPrompt
                    // 既存コアは Connected を繰り返し通知する（OpenVpnService.setStats
                    // -> wakeUpActivity -> 状態の再アナウンス）。製品側は**最初の
                    // Connected だけ**で記録するので、鏡も同じにする。毎回更新すると
                    // 猶予が永久に経過せず、I10 が「切替は起きないはず」と主張して
                    // しまう——それは実機で確認された欠陥そのものである
                    // （FailoverControllerSlowLinkTest の「Connected が繰り返し届いても」）。
                    // 候補の起動（下の newConnects）で null に戻るのも製品と同じ。
                    if (event.state == VpnCoreState.Connected && connectedAtMs == null) {
                        connectedAtMs = clock.nowMs()
                    }
                }
            }
            if (stateAfter is FailoverState.FailingOver && stateBefore !is FailoverState.FailingOver) {
                promptOpen = false
            }
            if (newConnects.isNotEmpty()) {
                promptOpen = false
                connectedAtMs = null
                for (uuid in newConnects) sessions[uuid] = null
            }
            pendingAttended = when {
                newConnects.isNotEmpty() -> null
                event is FailoverEvent.UserConnectGroup ->
                    if (stateAfter is FailoverState.FailingOver) true else null
                event is FailoverEvent.AutoConnectGroup ->
                    if (stateAfter is FailoverState.FailingOver) false else null
                event is FailoverEvent.UserDisconnect -> null
                stateBefore is FailoverState.Exhausted && event is FailoverEvent.Tick &&
                    stateAfter is FailoverState.FailingOver -> false
                else -> pendingAttended
            }
            if (event is FailoverEvent.UserConnectGroup) {
                // 製品側の `onUserConnect` と同じ条件（範囲外の開始位置は何もしない）。
                val group = groupOf(event.groupId)
                if (event.fromIndex == 0 ||
                    (group != null && event.fromIndex in group.memberUuids.indices)
                ) {
                    laps.clear()
                }
            }
            derivedCandidate()?.let { candidateUuid = it }

            // ---- 到達の記録（生成器が退化していないことの証拠）----------
            mark("状態:" + tagOf(stateAfter))
            if (newlyExcluded.isNotEmpty()) mark("除外の追加")
            if (newConnects.isNotEmpty() && needsFirstLogin.isNotEmpty()) {
                mark("初回ログイン未完了を抱えた起動")
            }
            if (event is FailoverEvent.VpnStateChanged && event.uuid != null &&
                event.uuid != candidateBefore && stateBefore == stateAfter
            ) {
                mark("古い通知の無視")
            }
            if (event is FailoverEvent.Tick && stateBefore is FailoverState.Connecting &&
                stateAfter is FailoverState.FailingOver
            ) {
                mark("接続タイムアウトでの切替")
            }
            if (event is FailoverEvent.Tick && stateBefore is FailoverState.FailingOver &&
                newConnects.isNotEmpty()
            ) {
                mark("切断待ちの時間切れ")
            }
            if (after != null && after.bySlowLink) mark("状態:FailingOver(bySlowLink)")
            if (!network.available) mark("網無しでの dispatch")
        }

        private fun checkIndex(
            groupId: String,
            index: Int,
            event: FailoverEvent,
            stateBefore: FailoverState,
        ) {
            val group = groupOf(groupId)
            if (group == null) {
                violate(
                    "I8", "グループ消失/${tagOf(event)}/${tagOf(stateBefore)}",
                    "状態が指すグループ $groupId が存在しない",
                )
                return
            }
            if (index !in group.memberUuids.indices) {
                violate(
                    "I8", "candidateIndex/${tagOf(event)}/${tagOf(stateBefore)}",
                    "candidateIndex=$index が $groupId の範囲（0..${group.memberUuids.size - 1}）の外",
                )
            }
        }

        private fun groupOf(groupId: String): FailoverGroup? = groups.firstOrNull { it.id == groupId }

        private fun derivedCandidate(): String? = when (val s = controller.state) {
            is FailoverState.Connecting -> groupOf(s.groupId)?.memberUuids?.getOrNull(s.candidateIndex)
            is FailoverState.Verifying -> groupOf(s.groupId)?.memberUuids?.getOrNull(s.candidateIndex)
            is FailoverState.Healthy -> groupOf(s.groupId)?.memberUuids?.getOrNull(s.candidateIndex)
            is FailoverState.FailingOver -> s.awaitingUuid
            is FailoverState.Exhausted -> null
            FailoverState.Idle -> null
        }

        private fun violate(invariant: String, kind: String, detail: String) {
            violations += Violation(
                invariant = invariant,
                kind = kind,
                detail = detail,
                seed = seed,
                sequence = sequence,
                config = config,
                initialGroups = initialGroups,
                steps = steps.toList(),
                stateAtFailure = controller.state.toString(),
                worldAtFailure = describeWorld(),
            )
        }

        private fun describeWorld(): String = buildString {
            append("clock=").append(clock.now)
            append(", groups=").append(renderGroups(groups))
            append(", network=").append(network.available)
            append(", dialogHost=").append(dialogHostAttached)
            append(", slowLink=").append(slowLink)
            append(", needsFirstLogin=").append(needsFirstLogin.sorted())
            append(", excluded=").append(controller.excludedUuids.sorted())
            append(", connectCalls=").append(vpn.connectCalls)
            append(", disconnectCalls=").append(vpn.disconnectCalls)
            append(", 影(候補=").append(candidateUuid)
            append(", promptOpen=").append(promptOpen)
            append(", connectedAt=").append(connectedAtMs)
            append(", 持ち越した利用者の指示=").append(pendingAttended)
            append(", 一周=").append(laps)
            append(")")
        }
    }

    // ------------------------------------------------------------ 検査の入口

    @Test
    fun `I1 認証情報の無い接続先は機械が始めた経路で起動されない`() = assertHolds("I1")

    @Test
    fun `I2 Ruling 25 connect の直前は接続指示か切断の確認だけ`() = assertHolds("I2")

    /**
     * **依頼者の裁定により、この不変条件は書き直されている。**
     *
     * 最初に書いた形（「エンジン起因のイベントなら何であれ現候補を見捨てない」）は
     * 機械生成で7経路・3系統で破れた。裁定はいずれも**製品の側が正しい**で、
     * 「不変条件の書き方が広すぎた」であった。よって述語を
     * **「締切だけを理由に見捨てない」** に狭め、3つの例外を
     * [Runner.dispatch] の中に**名前の付いた条件として**書いた
     * （`leftTheGroup` / `alreadyAbandoned` / `deadTunnelEvidence`）。
     * どの系統から来た例外で、なぜ正当なのかを各条件のコメントに残してある
     * ——将来の読者が「都合で緩めた」と読み違えないために。
     *
     * 狭めても歯は残っている: 裁定93 の門（`onUnattendedPromptTimeout` の
     * `dialogHostAttachedProvider()`）を外す変異は、狭めたあとの形でも
     * 6経路・198件で捕まる（うち「除外」側が3経路・99件。報告書 §6）。
     * 狭めた前提そのものが空回りしていないことも、到達記録
     * 「締切だけが残る場面」（9600列で2,210回）として毎回検査している。
     */
    @Test
    fun `I3 裁定93 94 人が答えているあいだ現候補を見捨てない`() = assertHolds("I3")

    /**
     * **この不変条件が見つけた欠陥は製品コード側で直された**（別コミット:
     * `onExhaustedRetry` に他の3か所と同じ網の門を足した）。機械生成が出した
     * 最小列は6手で、`FailoverControllerExhaustionNetworkTest` がその場面を
     * 手書きのシナリオとしても固定している（門を足して行き止まりを作って
     * いないこと＝網が戻れば次の Tick で起動することも、同じテストで押さえた）。
     */
    @Test
    fun `I4 安全策 S2 網が無いあいだエンジンの判断で起動しない`() = assertHolds("I4")

    @Test
    fun `I5 速度起因の切替は一周の予算を超えない`() = assertHolds("I5")

    @Test
    fun `I6 低速判定を引くのは Healthy のときだけ`() = assertHolds("I6")

    @Test
    fun `I7 安全策 S1 除外された uuid は利用者の指示まで起動されない`() = assertHolds("I7")

    @Test
    fun `I8 候補の添字は常に現グループの範囲内`() = assertHolds("I8")

    @Test
    fun `I9 bySlowLink は速度起因の経路だけが立てる`() = assertHolds("I9")

    @Test
    fun `I10 Connected から猶予のあいだ速度起因の切替は起きない`() = assertHolds("I10")

    /**
     * **網に歯があることの確認。** 生成器が興味の無いところだけを回っていたら、
     * 不変条件は「破れない」のではなく「試されていない」だけである。ここでは
     * 各不変条件の前提が実際に踏まれていることを固定する。
     */
    @Test
    fun `生成器は不変条件の前提に到達している`() {
        val required = listOf(
            "状態:Connecting",
            "状態:Verifying",
            "状態:Healthy",
            "状態:FailingOver",
            "状態:Exhausted",
            "状態:Idle",
            "状態:FailingOver(bySlowLink)",
            "速度起因の切替",
            "一周の終わり",
            "最良へ戻る切替",
            "切替なしで止まった一周",
            "分類できない着地",
            "除外の追加",
            "古い通知の無視",
            "人が答えている最中のエンジン起因イベント",
            "人が答えている最中の tick",
            // I3 を狭めた3つの例外を全部外した場面。ここが0だと I3 は
            // 「破れない」ではなく「試されていない」になる。
            "締切だけが残る場面",
            "接続タイムアウトでの切替",
            "切断待ちの時間切れ",
            "初回ログイン未完了を抱えた起動",
            "網無しでの dispatch",
        )
        val missing = required.filter { (corpus.coverage[it] ?: 0) == 0 }
        assertTrue(
            "生成器が踏んでいない前提がある: $missing\n" +
                "列数=${corpus.sequences} イベント数=${corpus.events} 所要=${corpus.elapsedMs}ms\n" +
                "到達記録=${corpus.coverage.toSortedMap()}",
            missing.isEmpty(),
        )
    }

    /**
     * **固定した列で I5 `戻る切替が前方へ着地` を踏む。**
     *
     * この検出は、乱数で作る列では一度も発火が確認できなかった（探した変異は別の
     * 検出に先に捕まった）。必要な並びは、切替を決めた時点で後ろの候補が全部
     * 使えず、切断を待つあいだにそのうち一つが使えるようになる、というもの。
     * 乱数の生成器には初回ログインの切り替え（[SetFirstLogin]）があるので表現はできるが、
     * この噛み合わせは偶然には起きない。ここで手順を固定し、**後の変更でこの並びが
     * 静かに作れなくなることを防ぐ**。
     *
     * メンバー [a, b, c]。a から b へ前進（前進 1 回。予算は 2）。b に着いたところで
     * c が初回ログイン未了になっており、後ろに起動できる候補が無いので一周が終わり、
     * 最良（記録が無いので選べる最初のメンバー a）へ戻る切替が始まる。切断を待つ
     * あいだに c の初回ログインが終わる。正しい製品は a に着く（後ろへは行かない）。
     * 戻り先を無視して前方へ進む製品は c に着く——前進の回数は 2 で予算の範囲内
     * なので `予算超過` は出ず、`戻る切替が前方へ着地` だけがこれを捕まえる。
     */
    @Test
    fun `固定列 戻る切替の決定後に後ろの候補が使えるようになっても後ろへは着地しない`() {
        val group = FailoverGroup(
            id = "g1",
            name = "g1",
            memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )
        val r = Runner(seed = 0L, sequence = 0, config = FailoverConfig(), initialGroups = listOf(group))
        fun go(vararg steps: Step) = steps.forEach { r.execute(it) }
        fun toHealthy(uuid: String) = go(
            Dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid)),
            Dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid)),
            AdvanceClock(16_000L),
            Dispatch(FailoverEvent.ProbeResult(reachable = true)),
        )

        go(Dispatch(FailoverEvent.UserConnectGroup("g1")))
        toHealthy("uuid-a")
        go(SetSlow(true), Dispatch(FailoverEvent.Tick))
        assertTrue((r.controller.state as FailoverState.FailingOver).bySlowLink)

        go(Dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, "uuid-a")))
        assertEquals("uuid-b", r.vpn.connectCalls.last())
        toHealthy("uuid-b")
        assertEquals(1, (r.controller.state as FailoverState.Healthy).candidateIndex)

        // 切替を決める時点で、後ろの c は使えない。
        go(SetFirstLogin("uuid-c", true), Dispatch(FailoverEvent.Tick))
        assertTrue(
            "一周の終わりの戻る切替が始まるはず（実際: ${r.controller.state}）",
            (r.controller.state as? FailoverState.FailingOver)?.bySlowLink == true,
        )

        // 切断を待つあいだに c が使えるようになる。
        go(
            SetFirstLogin("uuid-c", false),
            Dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, "uuid-b")),
        )

        assertTrue(
            "この列は不変条件を破らない: ${r.violations.map { it.key }}",
            r.violations.isEmpty(),
        )
        assertEquals(
            "戻る切替は後ろの c ではなく a に着くはず",
            "uuid-a", r.vpn.connectCalls.last(),
        )
        assertEquals(
            "この列が戻る切替の着地まで届いていること（並びの固定）",
            1, r.coverage["最良へ戻る切替"],
        )
    }

    private fun assertHolds(invariant: String) {
        val found = corpus.worst.filterKeys { it.startsWith("$invariant/") }
        if (found.isEmpty()) return
        val message = buildString {
            appendLine(
                "不変条件 $invariant が破れた（${found.size} 種類の経路、" +
                    "${corpus.sequences} 列 / ${corpus.events} イベント中）。" +
                    "**製品コードを直してはならない。不変条件を緩めてもならない。**",
            )
            for (violation in found.values.sortedBy { it.steps.size }) {
                appendLine()
                appendLine(renderFull(violation))
                appendLine(renderMinimal(shrink(violation)))
            }
        }
        fail(message)
    }

    // ------------------------------------------------------------ 出力と削り込み

    private fun renderFull(violation: Violation): String = buildString {
        appendLine("=== ${violation.key} : ${violation.detail}")
        appendLine("  出現回数: ${corpus.counts[violation.key]}")
        appendLine("  シード: seed=${violation.seed} 列=${violation.sequence}")
        appendLine("  設定: ${violation.config}")
        appendLine("  初期グループ: ${renderGroups(violation.initialGroups)}")
        appendLine("  破れた時点の状態: ${violation.stateAtFailure}")
        appendLine("  破れた時点の周辺: ${violation.worldAtFailure}")
        appendLine("  --- 生成されたイベント列（全 ${violation.steps.size} 手）---")
        violation.steps.forEachIndexed { i, step -> appendLine("  ${i + 1}. ${step.render()}") }
    }

    private fun renderMinimal(violation: Violation): String = buildString {
        appendLine("  --- 最小のイベント列（削れるところまで削った ${violation.steps.size} 手）---")
        appendLine("  初期グループ: ${renderGroups(violation.initialGroups)}")
        appendLine("  設定: ${violation.config}")
        violation.steps.forEachIndexed { i, step -> appendLine("  ${i + 1}. ${step.render()}") }
        appendLine("  破れた時点の状態: ${violation.stateAtFailure}")
        appendLine("  破れた時点の周辺: ${violation.worldAtFailure}")
    }

    private companion object {

        /** 固定シード。ここを変えない限り、同じ失敗が同じ手順で再現する。 */
        private val SEEDS = listOf(
            1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L, 144L, 233L,
            377L, 610L, 987L, 1_597L,
        )
        private const val SEQUENCES_PER_SEED = 600
        private const val MAX_STEPS = 70

        /** 設定はアプリ全体で1つ（裁定74）なので、1列のあいだは全グループで同じ値を使う。 */
        private val CONFIGS = listOf(
            FailoverConfig(),
            FailoverConfig(
                probeIntervalSec = 5,
                failureThreshold = 1,
                graceAfterConnectSec = 2,
                connectTimeoutSec = 10,
            ),
            FailoverConfig(
                probeIntervalSec = 30,
                failureThreshold = 2,
                graceAfterConnectSec = 15,
                connectTimeoutSec = 20,
            ),
            FailoverConfig(
                probeIntervalSec = 10,
                failureThreshold = 3,
                graceAfterConnectSec = 0,
                connectTimeoutSec = 45,
            ),
        )

        class Corpus {
            val worst = mutableMapOf<String, Violation>()
            val counts = mutableMapOf<String, Int>()
            val coverage = mutableMapOf<String, Int>()
            var sequences = 0
            var events = 0
            var elapsedMs = 0L
        }

        /** 全シードぶんを一度だけ流す（11件のテストが同じ結果を読む）。 */
        val corpus: Corpus by lazy { buildCorpus() }

        private fun buildCorpus(): Corpus {
            val corpus = Corpus()
            val startedAt = System.nanoTime()
            for (seed in SEEDS) {
                for (index in 0 until SEQUENCES_PER_SEED) {
                    val rnd = Random(seed * 1_000_003L + index)
                    val config = CONFIGS[rnd.nextInt(CONFIGS.size)]
                    val runner = Runner(
                        seed = seed,
                        sequence = index,
                        config = config,
                        initialGroups = initialGroups(rnd, config),
                        biased = index % 2 == 0,
                    )
                    runner.generate(rnd, MAX_STEPS)
                    corpus.sequences++
                    corpus.events += runner.dispatchCount
                    for ((name, count) in runner.coverage) {
                        corpus.coverage[name] = (corpus.coverage[name] ?: 0) + count
                    }
                    for (violation in runner.violations) {
                        corpus.counts[violation.key] = (corpus.counts[violation.key] ?: 0) + 1
                        val known = corpus.worst[violation.key]
                        if (known == null || violation.steps.size < known.steps.size) {
                            corpus.worst[violation.key] = violation
                        }
                    }
                }
            }
            corpus.elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            return corpus
        }

        private fun initialGroups(rnd: Random, config: FailoverConfig): List<FailoverGroup> {
            val count = if (rnd.nextInt(100) < 70) 1 else 2
            return (0 until count).map { i ->
                val members = MEMBER_POOL.shuffled(rnd).take(1 + rnd.nextInt(4))
                FailoverGroup(
                    id = GROUP_IDS[i],
                    name = GROUP_IDS[i],
                    memberUuids = members,
                    autoFailoverEnabled = rnd.nextInt(100) < 85,
                    config = config,
                )
            }
        }

        /**
         * 同じ破れ（同じ種別キー）を保ったまま手を落として最小列にする。
         * 前方の塊を落とす粗い段と、1手ずつ落とす段の2段。すべての手が絶対値なので
         * 落としても残りの意味は変わらない。
         */
        private fun shrink(violation: Violation): Violation {
            var best = violation
            var budget = 900
            var chunk = best.steps.size / 2
            while (chunk >= 1 && budget > 0) {
                budget--
                val replayed = replay(best, best.steps.drop(chunk))
                if (replayed != null) best = replayed else chunk /= 2
            }
            var at = 0
            while (at < best.steps.size && budget > 0) {
                budget--
                val candidate = best.steps.toMutableList().apply { removeAt(at) }
                val replayed = replay(best, candidate)
                if (replayed != null) best = replayed else at++
            }
            return best
        }

        /** [steps] を流し直して同じ種別キーの破れが出るなら、その記録を返す。 */
        private fun replay(violation: Violation, steps: List<Step>): Violation? {
            // 流し直しは記録された手をそのまま適用するだけなので、生成の偏りは関係ない。
            val runner = Runner(
                seed = violation.seed,
                sequence = violation.sequence,
                config = violation.config,
                initialGroups = violation.initialGroups,
            )
            for (step in steps) {
                runner.execute(step)
                val hit = runner.violations.firstOrNull { it.key == violation.key }
                if (hit != null) return hit
            }
            return null
        }

        private val MEMBER_POOL = listOf("uuid-a", "uuid-b", "uuid-c", "uuid-d", "uuid-e")
        private val GROUP_IDS = listOf("g1", "g2")

        /** 猶予・タイムアウト・バックオフ・プローブ間隔の境界をまたぐ値を含める。 */
        private val ADVANCE_AMOUNTS = listOf(
            1L, 500L, 999L, 1_000L, 1_500L, 2_999L, 3_000L, 3_001L, 4_000L,
            4_999L, 5_000L, 5_001L, 9_999L, 10_000L, 10_001L, 14_999L, 15_000L,
            15_001L, 16_000L, 19_999L, 20_001L, 22_500L, 29_999L, 30_000L,
            30_001L, 31_000L, 45_000L, 45_001L, 60_000L, 121_000L, 600_001L,
        )

        private val TOTAL_WEIGHT = 20 + 18 + 24 + 10 + 4 + 4 + 3 + 5 + 5 + 6 + 5 + 6 + 2

        /**
         * その uuid のセッションが次に取りうる既存コアの状態。実機の
         * OpenConnectManagementThread は STATE_CONNECTING を最初に送り
         * （裁定31）、認証をまたいでから CONNECTED に至り、最後に DISCONNECTED を
         * 送る。**起きえない並びを生成しない**ためにここで絞る。
         * 同じ値を複数回入れているのは重み付けである。
         */
        private fun allowedCoreStates(last: VpnCoreState?): List<VpnCoreState> = when (last) {
            // 起動直後。ほぼ必ず CONNECTING が先に来るが、プロファイルが開けないと
            // それを飛ばして DISCONNECTED が来る（製品側にもその前提の門がある）。
            null -> listOf(
                VpnCoreState.Connecting, VpnCoreState.Connecting, VpnCoreState.Connecting,
                VpnCoreState.Connecting, VpnCoreState.Disconnected,
            )
            VpnCoreState.Connecting -> listOf(
                VpnCoreState.Authenticating, VpnCoreState.Authenticating,
                VpnCoreState.UserPrompt, VpnCoreState.Authenticated,
                VpnCoreState.Connected, VpnCoreState.Connected, VpnCoreState.Disconnected,
            )
            VpnCoreState.Authenticating -> listOf(
                VpnCoreState.UserPrompt, VpnCoreState.UserPrompt,
                VpnCoreState.Authenticated, VpnCoreState.Authenticated,
                VpnCoreState.Connected, VpnCoreState.Disconnected,
            )
            VpnCoreState.UserPrompt -> listOf(
                VpnCoreState.Authenticating, VpnCoreState.Authenticated,
                VpnCoreState.Connected, VpnCoreState.Disconnected,
            )
            VpnCoreState.Authenticated -> listOf(
                VpnCoreState.Connecting, VpnCoreState.Connected, VpnCoreState.Connected,
                VpnCoreState.Disconnected,
            )
            // セッション中の再認証（裁定94）はここから始まる。
            VpnCoreState.Connected -> listOf(
                VpnCoreState.UserPrompt, VpnCoreState.Authenticating,
                VpnCoreState.Disconnected, VpnCoreState.Disconnected,
            )
            // 終了後は同じ通知の重複配信だけが届きうる（Ruling 13 が想定する形）。
            VpnCoreState.Disconnected -> listOf(VpnCoreState.Disconnected)
        }

        private fun tagOf(event: FailoverEvent): String = when (event) {
            is FailoverEvent.UserConnectGroup -> "UserConnect"
            is FailoverEvent.AutoConnectGroup -> "AutoConnect"
            FailoverEvent.UserDisconnect -> "UserDisconnect"
            is FailoverEvent.VpnStateChanged -> "Vpn:${event.state}"
            is FailoverEvent.ProbeResult -> "Probe:${event.reachable}"
            FailoverEvent.Tick -> "Tick"
            is FailoverEvent.UnderlyingNetworkChanged -> "Net:${event.available}"
        }

        /** 状態が指しているグループ ID（製品側の同名の private と同じ写し）。 */
        private fun groupIdOf(state: FailoverState): String? = when (state) {
            is FailoverState.Connecting -> state.groupId
            is FailoverState.Verifying -> state.groupId
            is FailoverState.Healthy -> state.groupId
            is FailoverState.FailingOver -> state.groupId
            is FailoverState.Exhausted -> state.groupId
            FailoverState.Idle -> null
        }

        private fun tagOf(state: FailoverState): String = when (state) {
            is FailoverState.Connecting -> "Connecting"
            is FailoverState.Verifying -> "Verifying"
            is FailoverState.Healthy -> "Healthy"
            is FailoverState.FailingOver -> "FailingOver"
            is FailoverState.Exhausted -> "Exhausted"
            FailoverState.Idle -> "Idle"
        }

        private fun renderGroups(groups: List<FailoverGroup>): String =
            groups.joinToString(", ", "[", "]") { group ->
                "${group.id}(${group.memberUuids.joinToString("/")}, auto=${group.autoFailoverEnabled})"
            }
    }
}
