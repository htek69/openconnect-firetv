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

    fun handle(event: FailoverEvent) {
        state = when (event) {
            is FailoverEvent.UserConnectGroup -> onUserConnect(event.groupId)
            is FailoverEvent.VpnStateChanged -> onVpnState(event.state)
            is FailoverEvent.ProbeResult -> onProbeResult(event.reachable)
            else -> state
        }
    }

    /** 呼び出し側がいまプローブを打つべきかの判定。 */
    fun shouldProbeNow(): Boolean {
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
        val group = groupOf(groupId) ?: return FailoverState.Idle
        return startCandidate(group, index = 0)
    }

    private fun startCandidate(group: FailoverGroup, index: Int): FailoverState {
        val uuid = group.memberUuids.getOrNull(index) ?: return FailoverState.Idle
        vpn.connect(uuid)
        return FailoverState.Connecting(
            groupId = group.id,
            candidateIndex = index,
            startedAtMs = clock.nowMs(),
        )
    }

    private fun onVpnState(core: VpnCoreState): FailoverState {
        val s = state
        return when {
            core == VpnCoreState.Connected && s is FailoverState.Connecting ->
                FailoverState.Verifying(
                    groupId = s.groupId,
                    candidateIndex = s.candidateIndex,
                    connectedAtMs = clock.nowMs(),
                )

            else -> s
        }
    }

    private fun onProbeResult(reachable: Boolean): FailoverState {
        return when (val s = state) {
            is FailoverState.Verifying ->
                if (reachable) {
                    FailoverState.Healthy(
                        groupId = s.groupId,
                        candidateIndex = s.candidateIndex,
                        consecutiveFailures = 0,
                        lastProbeAtMs = clock.nowMs(),
                    )
                } else {
                    s
                }

            is FailoverState.Healthy ->
                s.copy(
                    consecutiveFailures = if (reachable) 0 else s.consecutiveFailures + 1,
                    lastProbeAtMs = clock.nowMs(),
                )

            else -> s
        }
    }

    private fun groupOf(groupId: String): FailoverGroup? = groups.firstOrNull { it.id == groupId }

    private fun configOf(groupId: String): FailoverConfig =
        groupOf(groupId)?.config ?: FailoverConfig()
}
