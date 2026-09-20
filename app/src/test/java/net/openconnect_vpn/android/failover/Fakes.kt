package net.openconnect_vpn.android.failover

/** 時刻を手動で進められる Clock。 */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** 到達性を固定できる HealthProbe。 */
class FakeHealthProbe(var reachable: Boolean = true) : HealthProbe {
    var probeCount: Int = 0
        private set

    override suspend fun probe(target: ProbeTarget, timeoutMs: Int): Boolean {
        probeCount++
        return reachable
    }
}

/** connect/disconnect の呼び出しを記録する VpnController。 */
class FakeVpnController : VpnController {
    private val _connectCalls = mutableListOf<String>()
    val connectCalls: List<String> get() = _connectCalls

    var disconnectCalls: Int = 0
        private set

    var nextResult: ConnectResult = ConnectResult.Started

    override fun connect(uuid: String): ConnectResult {
        _connectCalls.add(uuid)
        return nextResult
    }

    override fun disconnect() {
        disconnectCalls++
    }
}

/** 下層ネットワークの有無を切り替えられる NetworkGate。 */
class FakeNetworkGate(var available: Boolean = true) : NetworkGate {
    override fun hasUnderlyingNetwork(): Boolean = available
}

/** メモリ上の KeyValueStore。 */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
