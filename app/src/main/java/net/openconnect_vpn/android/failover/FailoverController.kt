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
     * 枯渇の世代。次に枯渇したときの Exhausted.attempt になる。
     * `onTick` の再試行で 1 つ進め、復旧（Healthy 到達）とユーザー操作でのみ 0 に戻す。
     */
    private var exhaustionAttempt = 0

    fun handle(event: FailoverEvent) {
        state = when (event) {
            is FailoverEvent.UserConnectGroup -> onUserConnect(event.groupId)
            is FailoverEvent.UserDisconnect -> onUserDisconnect()
            is FailoverEvent.VpnStateChanged -> onVpnState(event.state)
            is FailoverEvent.ProbeResult -> onProbeResult(event.reachable)
            is FailoverEvent.UnderlyingNetworkChanged -> state
            is FailoverEvent.Tick -> onTick()
        }
    }

    /**
     * 呼び出し側がいまプローブを打つべきかの判定。
     * 安全策 S2 により下層ネットワークが無いときは常に false。
     */
    fun shouldProbeNow(): Boolean {
        if (!network.hasUnderlyingNetwork()) return false
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
        return startCandidateFrom(group, fromIndex = 0)
    }

    private fun onUserDisconnect(): FailoverState {
        exhaustionAttempt = 0
        expectingDisconnect = true
        vpn.disconnect()
        return FailoverState.Idle
    }

    /**
     * [fromIndex] 以降で除外されていない最初の候補へ接続する。
     * 候補が無ければ Exhausted に入る。
     */
    private fun startCandidateFrom(group: FailoverGroup, fromIndex: Int): FailoverState {
        var index = fromIndex
        while (index < group.memberUuids.size) {
            val uuid = group.memberUuids[index]
            if (uuid in _excludedUuids) {
                index++
                continue
            }
            currentCandidatePassedAuth = false
            expectingDisconnect = false
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

    private fun onVpnState(core: VpnCoreState): FailoverState {
        val s = state

        // 認証を通過したことを覚えておく（S1 の判定に使う）
        if (core == VpnCoreState.Authenticated || core == VpnCoreState.Connected) {
            currentCandidatePassedAuth = true
        }

        return when {
            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                )

            core == VpnCoreState.Disconnected -> onDisconnected(s)

            else -> s
        }
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

        // S1: 認証を通過せずに落ちたら認証失敗とみなして除外する
        if (!currentCandidatePassedAuth) {
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
                } else {
                    // 猶予期間中の失敗は無視する（S4）
                    s
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
     * [alreadyDown] が false のときはトンネルがまだ生きているので明示的に切断する。
     */
    private fun failOver(groupId: String, failedIndex: Int, alreadyDown: Boolean): FailoverState {
        val group = groupOf(groupId) ?: return FailoverState.Idle
        if (!alreadyDown) {
            expectingDisconnect = true
            vpn.disconnect()
        }
        if (!group.autoFailoverEnabled) return FailoverState.Idle
        return startCandidateFrom(group, fromIndex = failedIndex + 1)
    }

    /** 全候補が枯渇した。指数バックオフ後に先頭から再試行する（仕様書 7.2）。 */
    private fun enterExhausted(groupId: String, attempt: Int): FailoverState =
        FailoverState.Exhausted(
            groupId = groupId,
            attempt = attempt,
            retryAtMs = clock.nowMs() + Backoff.delayMsForAttempt(attempt),
        )

    private fun onTick(): FailoverState {
        val s = state as? FailoverState.Exhausted ?: return state
        if (clock.nowMs() < s.retryAtMs) return s
        val group = groupOf(s.groupId) ?: return FailoverState.Idle

        // 再試行時は認証失敗による除外を維持したまま先頭から試す。
        // 次に枯渇したときの attempt を1つ進める。ここでリセットしてはならない
        // （リセットすると2回目の枯渇でも attempt が 0 に戻り、バックオフが伸びない）。
        exhaustionAttempt = s.attempt + 1
        return startCandidateFrom(group, fromIndex = 0)
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
}
