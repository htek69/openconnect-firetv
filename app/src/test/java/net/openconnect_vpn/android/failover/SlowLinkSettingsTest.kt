package net.openconnect_vpn.android.failover

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仕様書 §4-A: 保存キー `slow_link_settings_v1` は据え置き（`DECISIONS` 項目7）。
 * **新旧どちらの向きでも落ちないこと**を固定する。
 */
class SlowLinkSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `既定値は仕様書 4-A の表どおり`() {
        val s = SlowLinkSettings()
        assertEquals(false, s.enabled)
        assertEquals(1000, s.slowRxKbps)
        assertEquals(50, s.rxFloorKbps)
        assertEquals(250, s.degradedRttMs)
        assertEquals(150, s.degradedJitterMs)
        assertEquals(0, s.rearmAfterLapMin)
    }

    @Test
    fun `古い版が書いた JSON を読める（新しい項目は既定値になる）`() {
        val old = """{"enabled":true,"slowRxKbps":2000}"""
        val s = json.decodeFromString<SlowLinkSettings>(old)
        assertEquals(true, s.enabled)
        assertEquals(2000, s.slowRxKbps)
        assertEquals(50, s.rxFloorKbps)
        assertEquals(250, s.degradedRttMs)
    }

    @Test
    fun `消えた項目が混じった JSON を読んでも落ちない`() {
        // demandTxKbps は改訂で消えた項目。古い版が書いていても読み飛ばす。
        val old = """{"enabled":true,"slowRxKbps":1000,"demandTxKbps":5}"""
        val s = json.decodeFromString<SlowLinkSettings>(old)
        assertEquals(true, s.enabled)
        assertEquals(1000, s.slowRxKbps)
    }

    @Test
    fun `往復しても一致する`() {
        val s = SlowLinkSettings(
            enabled = true,
            slowRxKbps = 1500,
            rxFloorKbps = 100,
            degradedRttMs = 300,
            degradedJitterMs = 200,
            rearmAfterLapMin = 60,
        )
        assertEquals(s, json.decodeFromString<SlowLinkSettings>(json.encodeToString(s)))
    }
}
