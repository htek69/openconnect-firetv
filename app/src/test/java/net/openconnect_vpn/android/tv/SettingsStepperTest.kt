package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- 検知遅延の上限そのものを制約する（裁定86 L5 で書き直し） ---

    @Test
    fun `ステッパーで到達できるどの組み合わせでも検知遅延は10分を超えない`() {
        // 裁定86（L5）: 以前は定数2つの積を定数 600 と比べるだけで、
        // 「KDoc に書いた数値と定数が一致する」ことしか言っていなかった
        // （定数を変えれば assert も直すことになり、不変条件になっていない）。
        //
        // 押さえたい仕様上の不変条件は「利用者がステッパーで作れるどの設定でも、
        // ダウン検知までの最長時間が受け入れ可能な範囲に収まること」である。
        // SettingsStepper の KDoc が上限として宣言している10分（600秒）を
        // 到達可能な全組み合わせに対して検査する。どちらかの定数を広げたときに
        // ここが落ちるので、10分という約束を見直す機会が必ず生まれる。
        val intervals = generateSequence(SettingsStepper.PROBE_INTERVAL_MIN_SEC) { current ->
            val next = SettingsStepper.stepProbeInterval(current, +1)
            next.takeIf { it != current }
        }.toList()
        val thresholds = generateSequence(SettingsStepper.FAILURE_THRESHOLD_MIN) { current ->
            val next = SettingsStepper.stepFailureThreshold(current, +1)
            next.takeIf { it != current }
        }.toList()

        // 到達できる値が両端を含めて列挙できていること（刻み幅の取り違えで
        // 1件だけになっていないこと）を先に確かめる。
        assertEquals(SettingsStepper.PROBE_INTERVAL_MAX_SEC, intervals.last())
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MAX, thresholds.last())

        val worst = intervals.maxOf { interval -> thresholds.maxOf { interval * it } }
        assertTrue("最長検知遅延が10分を超えている: ${worst}秒", worst <= 600)
    }

    // --- clampSlowRxKbps / stepSlowRxKbps（task-6） ---

    @Test
    fun `受信速度の下限は範囲内ならそのまま`() {
        assertEquals(1000, SettingsStepper.clampSlowRxKbps(1000))
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MIN, SettingsStepper.clampSlowRxKbps(SettingsStepper.SLOW_RX_KBPS_MIN))
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MAX, SettingsStepper.clampSlowRxKbps(SettingsStepper.SLOW_RX_KBPS_MAX))
    }

    @Test
    fun `受信速度の下限は下限未満なら下限に丸める`() {
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MIN, SettingsStepper.clampSlowRxKbps(0))
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MIN, SettingsStepper.clampSlowRxKbps(-100))
    }

    @Test
    fun `受信速度の下限は上限を超えたら上限に丸める`() {
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MAX, SettingsStepper.clampSlowRxKbps(999_999))
    }

    @Test
    fun `受信速度の下限を1段階増減すると刻み幅ぶん動く`() {
        assertEquals(1250, SettingsStepper.stepSlowRxKbps(1000, +1))
        assertEquals(750, SettingsStepper.stepSlowRxKbps(1000, -1))
    }

    @Test
    fun `受信速度の下限は下限で－を押しても下限のまま`() {
        assertEquals(
            SettingsStepper.SLOW_RX_KBPS_MIN,
            SettingsStepper.stepSlowRxKbps(SettingsStepper.SLOW_RX_KBPS_MIN, -1),
        )
    }

    @Test
    fun `受信速度の下限は上限で＋を押しても上限のまま`() {
        assertEquals(
            SettingsStepper.SLOW_RX_KBPS_MAX,
            SettingsStepper.stepSlowRxKbps(SettingsStepper.SLOW_RX_KBPS_MAX, +1),
        )
    }

    @Test
    fun `受信速度の下限は1段階の刻みでちょうど上限に着地できる`() {
        // 上限がステップの整数倍からずれていると、＋を押していっても上限ちょうどに
        // 止まらず最後の一押しだけ刻み幅が変わる、という不整合が起きる。
        val steps = (SettingsStepper.SLOW_RX_KBPS_MAX - SettingsStepper.SLOW_RX_KBPS_MIN) /
            SettingsStepper.SLOW_RX_KBPS_STEP
        var value = SettingsStepper.SLOW_RX_KBPS_MIN
        repeat(steps) { value = SettingsStepper.stepSlowRxKbps(value, +1) }
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MAX, value)
    }

    @Test
    fun `受信速度の下限はどれだけ増減しても250刻みの格子から外れない`() {
        val values = generateSequence(SettingsStepper.SLOW_RX_KBPS_MIN) { current ->
            val next = SettingsStepper.stepSlowRxKbps(current, +1)
            next.takeIf { it != current }
        }.toList()
        assertTrue(values.all { it % SettingsStepper.SLOW_RX_KBPS_STEP == 0 })
        assertEquals(SettingsStepper.SLOW_RX_KBPS_MAX, values.last())
    }
}
