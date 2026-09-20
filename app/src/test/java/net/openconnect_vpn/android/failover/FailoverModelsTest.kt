package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverModelsTest {

    @Test
    fun `FailoverConfig の既定値は仕様書のとおり`() {
        val config = FailoverConfig()
        assertEquals(30, config.probeIntervalSec)
        assertEquals(5000, config.probeTimeoutMs)
        assertEquals(3, config.failureThreshold)
        assertEquals(15, config.graceAfterConnectSec)
    }

    @Test
    fun `ProbeTarget の既定値は 1_1_1_1 の 443`() {
        val target = ProbeTarget()
        assertEquals("1.1.1.1", target.host)
        assertEquals(443, target.port)
    }

    @Test
    fun `memberUuids の並び順が優先順位を表す`() {
        val group = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )
        assertEquals("uuid-a", group.memberUuids.first())
        assertEquals(3, group.memberUuids.size)
    }

    @Test
    fun `Idle は初期状態として使える`() {
        val state: FailoverState = FailoverState.Idle
        assertTrue(state is FailoverState.Idle)
    }
}
