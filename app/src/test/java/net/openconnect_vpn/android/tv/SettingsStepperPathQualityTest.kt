package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.SlowLinkBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仕様書 §4-A の表のとおり。上下限で張り付き、刻みどおりに動くこと。
 *
 * 前半の5件は数字を**直書き**している（表の転記）。範囲の定義は
 * [SlowLinkBounds] ただ1か所にあるので、直書きの数字はその定義が表から
 * ずれたときにここで落ちる、という役目を持つ。後半は、ステッパーが範囲の端
 * まで刻みだけで着地できること（刻みと範囲がかみ合っていること）を見る。
 */
class SettingsStepperPathQualityTest {

    @Test
    fun `応答時間の上限は範囲で丸まる`() {
        assertEquals(50, SettingsStepper.clampDegradedRttMs(0))
        assertEquals(2000, SettingsStepper.clampDegradedRttMs(9999))
        assertEquals(250, SettingsStepper.clampDegradedRttMs(250))
    }

    @Test
    fun `応答時間の上限は50刻みで動き上下限で張り付く`() {
        assertEquals(300, SettingsStepper.stepDegradedRttMs(250, +1))
        assertEquals(200, SettingsStepper.stepDegradedRttMs(250, -1))
        assertEquals(2000, SettingsStepper.stepDegradedRttMs(2000, +1))
        assertEquals(50, SettingsStepper.stepDegradedRttMs(50, -1))
    }

    @Test
    fun `ばらつきの上限は50刻みで 50 から 1000`() {
        assertEquals(50, SettingsStepper.clampDegradedJitterMs(0))
        assertEquals(1000, SettingsStepper.clampDegradedJitterMs(9999))
        assertEquals(200, SettingsStepper.stepDegradedJitterMs(150, +1))
    }

    @Test
    fun `受信の下限は 0 を含み 500 まで`() {
        assertEquals(0, SettingsStepper.clampRxFloorKbps(-10))
        assertEquals(500, SettingsStepper.clampRxFloorKbps(9999))
        assertEquals(0, SettingsStepper.stepRxFloorKbps(50, -1))
        assertEquals(100, SettingsStepper.stepRxFloorKbps(50, +1))
    }

    @Test
    fun `再開間隔は 0 を含み 15分刻みで 240 まで`() {
        assertEquals(0, SettingsStepper.clampRearmAfterLapMin(-1))
        assertEquals(240, SettingsStepper.clampRearmAfterLapMin(9999))
        assertEquals(15, SettingsStepper.stepRearmAfterLapMin(0, +1))
        assertEquals(0, SettingsStepper.stepRearmAfterLapMin(15, -1))
    }

    // ---- ここから追加: 範囲の端の扱いと、刻みと範囲のかみ合わせ ----

    private class Item(
        val name: String,
        val min: Int,
        val max: Int,
        val stepWidth: Int,
        val clamp: (Int) -> Int,
        val step: (Int, Int) -> Int,
    )

    private val items = listOf(
        // 裁定30: 従来からある受信速度の上限も、範囲は SlowLinkBounds の1か所に寄せた
        Item(
            "受信速度の上限", SlowLinkBounds.SLOW_RX_KBPS_MIN, SlowLinkBounds.SLOW_RX_KBPS_MAX,
            SettingsStepper.SLOW_RX_KBPS_STEP,
            SettingsStepper::clampSlowRxKbps, SettingsStepper::stepSlowRxKbps,
        ),
        Item(
            "応答時間", SlowLinkBounds.DEGRADED_RTT_MS_MIN, SlowLinkBounds.DEGRADED_RTT_MS_MAX,
            SettingsStepper.DEGRADED_RTT_MS_STEP,
            SettingsStepper::clampDegradedRttMs, SettingsStepper::stepDegradedRttMs,
        ),
        Item(
            "ばらつき", SlowLinkBounds.DEGRADED_JITTER_MS_MIN, SlowLinkBounds.DEGRADED_JITTER_MS_MAX,
            SettingsStepper.DEGRADED_JITTER_MS_STEP,
            SettingsStepper::clampDegradedJitterMs, SettingsStepper::stepDegradedJitterMs,
        ),
        Item(
            "受信の下限", SlowLinkBounds.RX_FLOOR_KBPS_MIN, SlowLinkBounds.RX_FLOOR_KBPS_MAX,
            SettingsStepper.RX_FLOOR_KBPS_STEP,
            SettingsStepper::clampRxFloorKbps, SettingsStepper::stepRxFloorKbps,
        ),
        Item(
            "再開間隔", SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN, SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX,
            SettingsStepper.REARM_AFTER_LAP_MIN_STEP,
            SettingsStepper::clampRearmAfterLapMin, SettingsStepper::stepRearmAfterLapMin,
        ),
    )

    /**
     * 各ステッパーの clamp が [SlowLinkBounds] の端そのものを境にしていること。
     * 端の1つ内側・端・1つ外側を見る。端を含まない（`<` と `<=` の取り違え）や
     * 1ずれた範囲はここで落ちる。
     */
    @Test
    fun `clamp は共有の範囲の端を含み、その外だけを丸める`() {
        for (c in items) {
            assertEquals("${c.name}: 下限そのものは動かない", c.min, c.clamp(c.min))
            assertEquals("${c.name}: 上限そのものは動かない", c.max, c.clamp(c.max))
            assertEquals("${c.name}: 下限の1つ内側は動かない", c.min + 1, c.clamp(c.min + 1))
            assertEquals("${c.name}: 上限の1つ内側は動かない", c.max - 1, c.clamp(c.max - 1))
            assertEquals("${c.name}: 下限の1つ外は下限へ", c.min, c.clamp(c.min - 1))
            assertEquals("${c.name}: 上限の1つ外は上限へ", c.max, c.clamp(c.max + 1))
            assertEquals("${c.name}: Int の下端でも範囲に収まる", c.min, c.clamp(Int.MIN_VALUE))
            assertEquals("${c.name}: Int の上端でも範囲に収まる", c.max, c.clamp(Int.MAX_VALUE))
        }
    }

    /**
     * 下限から＋だけで押し続けると、ちょうど上限に着地し、上限で止まること。
     * 上限が刻みの倍数でないと、D-pad で最後の値に届かない（あるいは届く直前の値で
     * 止まる）ので、刻みと範囲がかみ合っていることを固定する。逆向きも同様。
     */
    @Test
    fun `刻みだけで下限から上限へ、上限から下限へ着地できる`() {
        for (c in items) {
            assertTrue("${c.name}: 刻みは正", c.stepWidth > 0)
            assertEquals("${c.name}: 範囲の幅は刻みの倍数", 0, (c.max - c.min) % c.stepWidth)
            val n = (c.max - c.min) / c.stepWidth
            var v = c.min
            repeat(n) { v = c.step(v, +1) }
            assertEquals("${c.name}: ＋を $n 回で上限", c.max, v)
            assertEquals("${c.name}: 上限でさらに＋しても動かない", c.max, c.step(v, +1))
            repeat(n) { v = c.step(v, -1) }
            assertEquals("${c.name}: －を $n 回で下限", c.min, v)
            assertEquals("${c.name}: 下限でさらに－しても動かない", c.min, c.step(v, -1))
        }
    }
}
