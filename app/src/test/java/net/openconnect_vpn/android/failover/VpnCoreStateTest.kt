package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnCoreStateTest {

    @Test
    fun `既存コアの STATE 値と 1対1 で対応する`() {
        assertEquals(VpnCoreState.Authenticating, VpnCoreState.fromCoreInt(1))
        assertEquals(VpnCoreState.UserPrompt, VpnCoreState.fromCoreInt(2))
        assertEquals(VpnCoreState.Authenticated, VpnCoreState.fromCoreInt(3))
        assertEquals(VpnCoreState.Connecting, VpnCoreState.fromCoreInt(4))
        assertEquals(VpnCoreState.Connected, VpnCoreState.fromCoreInt(5))
        assertEquals(VpnCoreState.Disconnected, VpnCoreState.fromCoreInt(6))
    }

    @Test
    fun `未知の値は null`() {
        assertNull(VpnCoreState.fromCoreInt(0))
        assertNull(VpnCoreState.fromCoreInt(7))
        assertNull(VpnCoreState.fromCoreInt(-1))
    }
}
