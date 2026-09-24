package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStepperTest {

    private fun group(id: String, config: FailoverConfig) = FailoverGroup(
        id = id,
        name = "group-$id",
        memberUuids = listOf("uuid-$id"),
        autoFailoverEnabled = true,
        config = config,
    )

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

    // --- initialProbeIntervalSec / initialFailureThreshold ---

    @Test
    fun `グループが無ければ既定値を初期値にする`() {
        assertEquals(FailoverConfig().probeIntervalSec, SettingsStepper.initialProbeIntervalSec(emptyList()))
        assertEquals(FailoverConfig().failureThreshold, SettingsStepper.initialFailureThreshold(emptyList()))
    }

    @Test
    fun `グループがあれば先頭グループの値を初期値にする`() {
        val groups = listOf(
            group("g1", FailoverConfig(probeIntervalSec = 90, failureThreshold = 4)),
            group("g2", FailoverConfig(probeIntervalSec = 20, failureThreshold = 1)),
        )
        assertEquals(90, SettingsStepper.initialProbeIntervalSec(groups))
        assertEquals(4, SettingsStepper.initialFailureThreshold(groups))
    }

    @Test
    fun `保存済みの値が範囲外でも初期値は範囲内に丸める`() {
        val groups = listOf(group("g1", FailoverConfig(probeIntervalSec = 9_999, failureThreshold = 0)))
        assertEquals(SettingsStepper.PROBE_INTERVAL_MAX_SEC, SettingsStepper.initialProbeIntervalSec(groups))
        assertEquals(SettingsStepper.FAILURE_THRESHOLD_MIN, SettingsStepper.initialFailureThreshold(groups))
    }

    // --- applyProbeSchedule ---

    @Test
    fun `全グループへ同じ間隔と閾値を書き込む`() {
        val groups = listOf(
            group("g1", FailoverConfig(probeIntervalSec = 30, failureThreshold = 3)),
            group("g2", FailoverConfig(probeIntervalSec = 90, failureThreshold = 5)),
        )

        val updated = SettingsStepper.applyProbeSchedule(groups, probeIntervalSec = 60, failureThreshold = 2)

        assertEquals(listOf(60, 60), updated.map { it.config.probeIntervalSec })
        assertEquals(listOf(2, 2), updated.map { it.config.failureThreshold })
    }

    @Test
    fun `applyProbeSchedule は間隔と閾値以外のフィールドを保つ`() {
        val original = FailoverConfig(
            probeIntervalSec = 30,
            probeTimeoutMs = 1_234,
            failureThreshold = 3,
            graceAfterConnectSec = 7,
            connectTimeoutSec = 12,
        )
        val groups = listOf(group("g1", original))

        val updated = SettingsStepper.applyProbeSchedule(groups, probeIntervalSec = 60, failureThreshold = 2)

        val config = updated.single().config
        assertEquals(1_234, config.probeTimeoutMs)
        assertEquals(7, config.graceAfterConnectSec)
        assertEquals(12, config.connectTimeoutSec)
    }

    @Test
    fun `applyProbeSchedule はグループ自体（メンバー・自動切替）を変えない`() {
        val groups = listOf(group("g1", FailoverConfig()))

        val updated = SettingsStepper.applyProbeSchedule(groups, probeIntervalSec = 60, failureThreshold = 2)

        assertEquals(groups.single().id, updated.single().id)
        assertEquals(groups.single().name, updated.single().name)
        assertEquals(groups.single().memberUuids, updated.single().memberUuids)
        assertEquals(groups.single().autoFailoverEnabled, updated.single().autoFailoverEnabled)
    }

    @Test
    fun `applyProbeSchedule は空リストなら空リストを返す`() {
        assertEquals(emptyList<FailoverGroup>(), SettingsStepper.applyProbeSchedule(emptyList(), 60, 2))
    }
}
