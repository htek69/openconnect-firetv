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

    /**
     * 旧版（項目が2つだけ）が保存した JSON を、**本番の読込経路**
     * `GroupStore.loadSlowLinkSettings()` で読めることを固定する。
     *
     * この検査を外すと何が壊れるか: もし読込の decode が失敗すると
     * `loadSlowLinkSettings()` は `SlowLinkSettings()` を返す。そのため
     * 利用者が保存した `enabled=true` と `slowRxKbps=2000` は既定値に
     * 黙って戻り、どのテストも落ちない（データ消失）。上の
     * `SlowLinkSettingsTest` は自前の `Json` で読むため、本番の `GroupStore`
     * から `ignoreUnknownKeys` を外しても通ってしまう。この件はそれを補う。
     * 冗長だと判断して消さないこと。
     */
    @Test
    fun `旧版の JSON を本番の読込経路で読める（新項目は既定値）`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("slow_link_settings_v1", """{"enabled":true,"slowRxKbps":2000}""")
        val s = GroupStore(kv).loadSlowLinkSettings()
        assertTrue(s.enabled)
        assertEquals(2000, s.slowRxKbps)
        assertEquals(50, s.rxFloorKbps)
        assertEquals(250, s.degradedRttMs)
        assertEquals(150, s.degradedJitterMs)
        assertEquals(0, s.rearmAfterLapMin)
    }

    /**
     * 改訂で消えた `demandTxKbps` を含む JSON を、**本番の読込経路**で
     * 読み飛ばせることを固定する。
     *
     * この検査を外すと何が壊れるか: `GroupStore` の `json` から
     * `ignoreUnknownKeys = true` を外すと、未知の項目で decode が失敗し、
     * `loadSlowLinkSettings()` は `SlowLinkSettings()` を返す。すると利用者が
     * 保存した `enabled=true` と `slowRxKbps=1000` は既定値（無効・1000）に
     * 黙って戻る。つまり設定が消えるのに、この件以外のテストは落ちない。
     * `assertTrue(s.enabled)` は既定値（false）との区別のために必要である。
     * 「`SlowLinkSettingsTest` と重複する」として消さないこと。
     */
    @Test
    fun `消えた項目が混じった保存値を本番の読込経路で読んでも既定値に戻らない`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("slow_link_settings_v1", """{"enabled":true,"slowRxKbps":1000,"demandTxKbps":5}""")
        val s = GroupStore(kv).loadSlowLinkSettings()
        assertTrue(s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }
}
