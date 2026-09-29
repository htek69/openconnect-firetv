package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安全策 S2 のうち、**枯渇後のバックオフ満了による再試行**だけが見ていなかった
 * 「下層ネットワークが無いあいだは起動しない」を固定する。
 *
 * この欠陥は手書きのシナリオでは見つかっておらず（既存の11ファイル・406件は
 * `FailingOver` から前進しないことだけを S2 として固定していた）、
 * 不変条件テスト [FailoverControllerInvariantTest] の I4 が
 * **6手の最小列**で出したものである:
 *
 * ```
 * AutoConnectGroup(g1) → VpnStateChanged(Connecting) → VpnStateChanged(Disconnected)
 * → clock += 30_000 → network.available = false → Tick   // ここで起動してしまっていた
 * ```
 *
 * ここで併せて固定するのは、**門を足したことで行き止まりを作っていないこと**である
 * ——網が戻ったら次の Tick で起動する（`FailoverService` のティックループは
 * `Exhausted` のあいだも回り続け、`retryAtMs` を過ぎたあとは毎 Tick ここを再評価する）。
 * 門だけ足して回復経路が無ければ、無駄な再試行より悪い「永久に `Exhausted`」に
 * なるので、両側を1本のテストで押さえる。
 */
class FailoverControllerExhaustionNetworkTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    /** メンバー1件。先頭候補が落ちれば次候補が無いのでそのまま枯渇する。 */
    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        controller = FailoverController({ listOf(group) }, clock, vpn, network)
    }

    @Test
    fun `網が無いあいだは枯渇後の再試行をしないが網が戻れば次の Tick で起動する`() {
        // 1. uuid-a を起動して落とし、次候補が無いので Exhausted に入る。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        // 裁定31: 実機同様、自分の Connecting を観測してから Disconnected を送る。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected))
        val exhausted = controller.state
        assertTrue(exhausted is FailoverState.Exhausted)
        assertEquals(listOf("uuid-a"), vpn.connectCalls)

        // 2. 下層ネットが落ちる。
        network.available = false
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = false))

        // 3. バックオフ（Backoff.delayMsForAttempt(0) = 30 秒）が満了しても、
        //    網が無いので再試行しない（S2）。何度 Tick を投げても起動しない。
        clock.advance(31_000L)
        repeat(3) { controller.handle(FailoverEvent.Tick) }

        assertEquals("網が無いあいだは候補を起動してはならない", listOf("uuid-a"), vpn.connectCalls)
        // 空回りでバックオフを伸ばしてもいない（attempt も retryAtMs もそのまま）。
        assertEquals(exhausted, controller.state)

        // 4. 網が戻る。実機のティックループは同じ周回で通知を投げてから Tick を
        //    投げるので、ここでも同じ順で与える。
        network.available = true
        controller.handle(FailoverEvent.UnderlyingNetworkChanged(available = true))
        controller.handle(FailoverEvent.Tick)

        // 回復経路: retryAtMs は既に過ぎているので、その場で先頭候補を起動する。
        assertEquals(listOf("uuid-a", "uuid-a"), vpn.connectCalls)
        assertTrue(controller.state is FailoverState.Connecting)
    }
}
