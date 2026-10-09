package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 範囲外の値が判定に届かないこと（読込経路）と、範囲の定義そのもの。
 *
 * 守っているもの: 別の版が書いた、あるいは手で書き換えた JSON が
 * `degradedJitterMs: 0` を運んできても、「ばらつきがしきい値を超える」が
 * ほとんど常に真にならないこと（不当な切替の防止）。`ignoreUnknownKeys` が
 * 弾くのは未知の項目であって、範囲外の値ではない。
 */
class SlowLinkSettingsCoercionTest {

    private fun load(json: String): SlowLinkSettings {
        val kv = InMemoryKeyValueStore()
        kv.putString("slow_link_settings_v1", json)
        return GroupStore(kv).loadSlowLinkSettings()
    }

    // ---- 範囲の定義（仕様書 §4-A の表の転記。表からずれたらここで落ちる）----

    @Test
    fun `範囲は仕様書 4-A の表のとおり`() {
        assertEquals(50, SlowLinkBounds.DEGRADED_RTT_MS_MIN)
        assertEquals(2000, SlowLinkBounds.DEGRADED_RTT_MS_MAX)
        assertEquals(50, SlowLinkBounds.DEGRADED_JITTER_MS_MIN)
        assertEquals(1000, SlowLinkBounds.DEGRADED_JITTER_MS_MAX)
        assertEquals(0, SlowLinkBounds.RX_FLOOR_KBPS_MIN)
        assertEquals(500, SlowLinkBounds.RX_FLOOR_KBPS_MAX)
        assertEquals(0, SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN)
        assertEquals(240, SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX)
    }

    @Test
    fun `既定値は範囲の中にあり coerced で変わらない`() {
        // 既定値が範囲の外だと、何も保存していない利用者の設定が読込のたびに書き換わる
        assertEquals(SlowLinkSettings(), SlowLinkSettings().coerced())
    }

    // ---- coerced() 単体 ----

    @Test
    fun `範囲外の項目は端へ収まり、端そのものは動かない`() {
        val low = SlowLinkSettings(
            rxFloorKbps = -1, degradedRttMs = -5, degradedJitterMs = 0, rearmAfterLapMin = -1,
        ).coerced()
        assertEquals(0, low.rxFloorKbps)
        assertEquals(50, low.degradedRttMs)
        assertEquals(50, low.degradedJitterMs)
        assertEquals(0, low.rearmAfterLapMin)

        val high = SlowLinkSettings(
            rxFloorKbps = 501, degradedRttMs = 2001, degradedJitterMs = 1001, rearmAfterLapMin = 241,
        ).coerced()
        assertEquals(500, high.rxFloorKbps)
        assertEquals(2000, high.degradedRttMs)
        assertEquals(1000, high.degradedJitterMs)
        assertEquals(240, high.rearmAfterLapMin)

        val edge = SlowLinkSettings(
            rxFloorKbps = 500, degradedRttMs = 50, degradedJitterMs = 1000, rearmAfterLapMin = 240,
        )
        assertEquals("端そのものは範囲内", edge, edge.coerced())
    }

    @Test
    fun `Int の両端でも範囲に収まる`() {
        val lo = SlowLinkSettings(
            rxFloorKbps = Int.MIN_VALUE, degradedRttMs = Int.MIN_VALUE,
            degradedJitterMs = Int.MIN_VALUE, rearmAfterLapMin = Int.MIN_VALUE,
        ).coerced()
        assertEquals(SlowLinkBounds.RX_FLOOR_KBPS_MIN, lo.rxFloorKbps)
        assertEquals(SlowLinkBounds.DEGRADED_RTT_MS_MIN, lo.degradedRttMs)
        assertEquals(SlowLinkBounds.DEGRADED_JITTER_MS_MIN, lo.degradedJitterMs)
        assertEquals(SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN, lo.rearmAfterLapMin)
        val hi = SlowLinkSettings(
            rxFloorKbps = Int.MAX_VALUE, degradedRttMs = Int.MAX_VALUE,
            degradedJitterMs = Int.MAX_VALUE, rearmAfterLapMin = Int.MAX_VALUE,
        ).coerced()
        assertEquals(SlowLinkBounds.RX_FLOOR_KBPS_MAX, hi.rxFloorKbps)
        assertEquals(SlowLinkBounds.DEGRADED_RTT_MS_MAX, hi.degradedRttMs)
        assertEquals(SlowLinkBounds.DEGRADED_JITTER_MS_MAX, hi.degradedJitterMs)
        assertEquals(SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX, hi.rearmAfterLapMin)
    }

    @Test
    fun `enabled と slowRxKbps には触れない`() {
        // slowRxKbps の範囲は従来どおり設定画面の表示側が持つ。coerced が勝手に
        // 動かすと、保存→読込の往復で値が変わる（GroupStorePropertyTest が見張る）
        val s = SlowLinkSettings(enabled = true, slowRxKbps = 7, degradedJitterMs = 0)
        val c = s.coerced()
        assertEquals(true, c.enabled)
        assertEquals(7, c.slowRxKbps)
    }

    // ---- 本番の読込経路 ----

    /**
     * 「coerced を呼ばない読込」はここで落ちる。ばらつきのしきい値 0 は、
     * 判定に届けば「ばらつきがしきい値を超える」をほぼ常に真にする。
     */
    @Test
    fun `読込では、ばらつきのしきい値 0 は下限へ収まる`() {
        val s = load("""{"enabled":true,"degradedJitterMs":0}""")
        assertEquals(SlowLinkBounds.DEGRADED_JITTER_MS_MIN, s.degradedJitterMs)
        assertEquals("他の項目は巻き込まない", true, s.enabled)
    }

    @Test
    fun `読込では、負の応答時間や範囲外の値は端へ収まる`() {
        val s = load(
            """{"degradedRttMs":-5,"degradedJitterMs":99999,"rxFloorKbps":-1,"rearmAfterLapMin":9999}""",
        )
        assertEquals(SlowLinkBounds.DEGRADED_RTT_MS_MIN, s.degradedRttMs)
        assertEquals(SlowLinkBounds.DEGRADED_JITTER_MS_MAX, s.degradedJitterMs)
        assertEquals(SlowLinkBounds.RX_FLOOR_KBPS_MIN, s.rxFloorKbps)
        assertEquals(SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX, s.rearmAfterLapMin)
    }

    @Test
    fun `読込では、範囲内の値は1つも変わらずに往復する`() {
        // 既定値と違う、かつ範囲の内側の値。coerced が既定値に戻したり丸めたり
        // する実装（あるいは項目を書き落とす実装）はここで落ちる
        val saved = SlowLinkSettings(
            enabled = true,
            slowRxKbps = 1500,
            rxFloorKbps = 100,
            degradedRttMs = 300,
            degradedJitterMs = 200,
            rearmAfterLapMin = 60,
        )
        val g = GroupStore(InMemoryKeyValueStore())
        g.saveSlowLinkSettings(saved)
        assertEquals(saved, g.loadSlowLinkSettings())
    }

    @Test
    fun `読込では、未知の項目と範囲外の値が同居しても、前者は読み飛ばし後者は収める`() {
        val s = load("""{"demandTxKbps":5,"degradedJitterMs":0,"rxFloorKbps":75}""")
        assertEquals(SlowLinkBounds.DEGRADED_JITTER_MS_MIN, s.degradedJitterMs)
        assertEquals(75, s.rxFloorKbps)
    }
}
