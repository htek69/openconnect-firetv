package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowLinkSettingsStoreTest {

    @Test
    fun `未保存なら既定値を返す`() {
        val s = GroupStore(InMemoryKeyValueStore()).loadSlowLinkSettings()
        assertFalse(s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }

    @Test
    fun `保存した値を読み戻せる`() {
        val g = GroupStore(InMemoryKeyValueStore())
        g.saveSlowLinkSettings(SlowLinkSettings(enabled = true, slowRxKbps = 1500))
        val s = g.loadSlowLinkSettings()
        assertTrue(s.enabled)
        assertEquals(1500, s.slowRxKbps)
    }

    @Test
    fun `壊れた値なら既定値に戻す`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("slow_link_settings_v1", "{ not json")
        val s = GroupStore(kv).loadSlowLinkSettings()
        assertFalse(s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }
}
