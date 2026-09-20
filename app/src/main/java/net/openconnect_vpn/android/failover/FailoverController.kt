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
    private val groups: List<FailoverGroup>,
    private val clock: Clock,
    private val vpn: VpnController,
    private val network: NetworkGate,
) {

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
     * Ruling 21: 現在の候補を人が見ている保証が無いか。
     * onUserConnect（ユーザーが画面の前で接続操作をした）から開始した候補は false、
     * failOver（自動切替）や onTick の再試行から開始した候補は true になる。
     * true のときに UserPrompt に来ても誰も答えられないので、認証失敗と同様に扱う。
     */
    private var currentCandidateUnattended = false

    /**
     * 枯渇の世代。次に枯渇したときの Exhausted.attempt になる。
     * `onTick` の再試行で 1 つ進め、復旧（Healthy 到達）とユーザー操作でのみ 0 に戻す。
     */
    private var exhaustionAttempt = 0

    /** 安全策 S2: 下層ネット復帰時に次のプローブを即座に実行するフラグ。Ruling 14 により一度だけ消費される。 */
    private var probeImmediatelyOnNetworkRecovery = false

    fun handle(event: FailoverEvent) {
        state = when (event) {
            is FailoverEvent.UserConnectGroup -> onUserConnect(event.groupId)
            is FailoverEvent.UserDisconnect -> onUserDisconnect()
            is FailoverEvent.VpnStateChanged -> onVpnState(event.state, event.uuid)
            is FailoverEvent.ProbeResult -> onProbeResult(event.reachable)
            is FailoverEvent.UnderlyingNetworkChanged -> onUnderlyingNetworkChanged(event.available)
            is FailoverEvent.Tick -> onTick()
        }
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

    private fun onUserConnect(groupId: String): FailoverState {
        exhaustionAttempt = 0
        val group = groupOf(groupId) ?: return FailoverState.Idle
        needsUserConsent = false
        return startCandidateFrom(group, fromIndex = 0, unattended = false)
    }

    private fun onUserDisconnect(): FailoverState {
        exhaustionAttempt = 0
        probeImmediatelyOnNetworkRecovery = false
        expectingDisconnect = true
        vpn.disconnect()
        return FailoverState.Idle
    }

    /**
     * [fromIndex] 以降で除外されていない最初の候補へ接続する。
     * 候補が無ければ Exhausted に入る。
     */
    private fun startCandidateFrom(group: FailoverGroup, fromIndex: Int, unattended: Boolean): FailoverState {
        var index = fromIndex
        while (index < group.memberUuids.size) {
            val uuid = group.memberUuids[index]
            if (uuid in _excludedUuids) {
                index++
                continue
            }
            currentCandidatePassedAuth = false
            currentCandidateReachedAuth = false
            currentCandidateUnattended = unattended
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

        return when {
            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                    consecutiveFailures = 0,
                )

            // Ruling 25: 切断完了の確認。これを合図に次候補を起動する。
            core == VpnCoreState.Disconnected && s is FailoverState.FailingOver ->
                if (s.awaitingUuid == null || uuid == null || uuid == s.awaitingUuid) {
                    advanceAfterFailingOver(s)
                } else {
                    s
                }

            core == VpnCoreState.Disconnected -> onDisconnected(s)

            // Ruling 21: 人が見ている保証の無い候補（自動切替・枯渇後の再試行）が
            // 認証ダイアログで止まった。誰も答えられないので認証失敗と同様に扱う。
            // ユーザー自身が接続した候補（画面の前にいる）はそのまま待たせる。
            core == VpnCoreState.UserPrompt && currentCandidateUnattended -> onUnattendedUserPrompt(s)

            else -> s
        }
    }

    /**
     * Ruling 21: 自動切替または枯渇後の再試行で開始した候補が認証ダイアログで
     * 止まった（＝保存済み認証情報が拒否された等）。TV の前に人がいる保証は無く、
     * このまま放置すると S1 の安全策（認証失敗した候補を除外して次へ進む）が
     * 一度も働かずに無期限へ止まってしまう。よって認証失敗と同じ扱いにする：
     * この候補を除外して次候補へ進める（alreadyDown = false。Ruling 25 により
     * 切断を要求してから完了を待って次候補へ進む）。
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
        val group = groupOf(groupId) ?: return FailoverState.Idle

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
        is FailoverState.Connecting -> onConnectTimeout(s)
        is FailoverState.FailingOver -> onFailingOverTimeout(s)
        is FailoverState.Exhausted -> onExhaustedRetry(s)
        else -> state
    }

    /** Ruling 25: 切断完了の確認が来ないまま [DISCONNECT_WAIT_MS] を過ぎたら先へ進む。 */
    private fun onFailingOverTimeout(s: FailoverState.FailingOver): FailoverState {
        if (clock.nowMs() - s.startedAtMs < DISCONNECT_WAIT_MS) return s
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
     */
    private fun onConnectTimeout(s: FailoverState.Connecting): FailoverState {
        val timeoutMs = configOf(s.groupId).connectTimeoutSec * 1_000L
        if (clock.nowMs() - s.startedAtMs < timeoutMs) return s
        return failOver(s.groupId, s.candidateIndex, alreadyDown = false)
    }

    private fun onExhaustedRetry(s: FailoverState.Exhausted): FailoverState {
        if (clock.nowMs() < s.retryAtMs) return s
        val group = groupOf(s.groupId) ?: return FailoverState.Idle

        // 再試行時は認証失敗による除外を維持したまま先頭から試す。
        // 次に枯渇したときの attempt を1つ進める。ここでリセットしてはならない
        // （リセットすると2回目の枯渇でも attempt が 0 に戻り、バックオフが伸びない）。
        exhaustionAttempt = s.attempt + 1
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
         * 諦めて次候補へ進むほうがマシである（次候補の起動が既存コア側で
         * もう一度 `killVPNThread` を走らせる）。
         */
        const val DISCONNECT_WAIT_MS = 3_000L
    }
}
