package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettingsStepper] は裁定74以降、範囲・刻み幅の純粋ロジックだけを持つ
 * （初期値取得・保存は [net.openconnect_vpn.android.failover.GroupStore] の
 * グローバル設定スロットに一本化した。そちらのテストは `GroupStoreTest` にある）。
 */
class SettingsStepperTest {

    // --- clampProbeInterval / stepProbeInterval ---

    @Test
    fun `プローブ間隔は範囲内ならそのまま`() {
        assertEquals(30, SettingsStepper.clampProbeInterval(30))
        assertEquals(SettingsStepper.PROBE_INTERVAL_MIN_SEC, SettingsStepper.clampProbeInterval(SettingsStepper.PROBE_INTERVAL_MIN_SEC))
        assertEquals(SettingsStepper.PROBE_INTERVAL_MAX_SEC, SettingsStepper.clampProbeInterval(SettingsStepper.PROBE_INTERVAL_MAX_SEC))
    }

    @Test
    fun `プローブ間隔は下限未満なら下限に丸める`() {
        assertEquals(SettingsStepper.PROBE_INTERVAL_MIN_SEC, SettingsStepper.clampProbeInterval(0))
        assertEquals(SettingsStepper.PROBE_INTERVAL_MIN_SEC, SettingsStepper.clampProbeInterval(-100))
    }

    @Test
    fun `プローブ間隔は上限を超えたら上限に丸める`() {
        assertEquals(SettingsStepper.PROBE_INTERVAL_MAX_SEC, SettingsStepper.clampProbeInterval(9_999))
    }

    @Test
    fun `プローブ間隔を1段階増減すると刻み幅ぶん動く`() {
        assertEquals(40, SettingsStepper.stepProbeInterval(30, +1))
        assertEquals(20, SettingsStepper.stepProbeInterval(30, -1))
    }

    @Test
    fun `プローブ間隔は下限で－を押しても下限のまま`() {
        assertEquals(
            SettingsStepper.PROBE_INTERVAL_MIN_SEC,
            SettingsStepper.stepProbeInterval(SettingsStepper.PROBE_INTERVAL_MIN_SEC, -1),
        )
    }

    @Test
    fun `プローブ間隔は上限で＋を押しても上限のまま`() {
        assertEquals(
            SettingsStepper.PROBE_INTERVAL_MAX_SEC,
            SettingsStepper.stepProbeInterval(SettingsStepper.PROBE_INTERVAL_MAX_SEC, +1),
        )
    }

    // --- clampFailureThreshold / stepFailureThreshold ---

    @Test
    fun `失敗閾値は範囲内ならそのまま`() {
        assertEquals(3, SettingsStepper.clampFailureThreshold(3))
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MIN, SettingsStepper.clampFailureThreshold(SettingsStepper.FAILURE_THRESHOLD_MIN))
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MAX, SettingsStepper.clampFailureThreshold(SettingsStepper.FAILURE_THRESHOLD_MAX))
    }

    @Test
    fun `失敗閾値は下限未満なら下限に丸める`() {
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MIN, SettingsStepper.clampFailureThreshold(0))
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MIN, SettingsStepper.clampFailureThreshold(-5))
    }

    @Test
    fun `失敗閾値は上限を超えたら上限に丸める`() {
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MAX, SettingsStepper.clampFailureThreshold(999))
    }

    @Test
    fun `失敗閾値を1段階増減すると刻み幅ぶん動く`() {
        assertEquals(4, SettingsStepper.stepFailureThreshold(3, +1))
        assertEquals(2, SettingsStepper.stepFailureThreshold(3, -1))
    }

    @Test
    fun `失敗閾値は下限で－を押しても下限のまま`() {
        assertEquals(
            SettingsStepper.FAILURE_THRESHOLD_MIN,
            SettingsStepper.stepFailureThreshold(SettingsStepper.FAILURE_THRESHOLD_MIN, -1),
        )
    }

    @Test
    fun `失敗閾値は上限で＋を押しても上限のまま`() {
        assertEquals(
            SettingsStepper.FAILURE_THRESHOLD_MAX,
            SettingsStepper.stepFailureThreshold(SettingsStepper.FAILURE_THRESHOLD_MAX, +1),
        )
    }

    // --- 最悪ケース／最良ケースの検知遅延（報告書に書く数値の裏付け） ---

    @Test
    fun `両端の積が報告した検知遅延と一致する`() {
        assertEquals(600, SettingsStepper.PROBE_INTERVAL_MAX_SEC * SettingsStepper.FAILURE_THRESHOLD_MAX)
        assertEquals(10, SettingsStepper.PROBE_INTERVAL_MIN_SEC * SettingsStepper.FAILURE_THRESHOLD_MIN)
    }
}
