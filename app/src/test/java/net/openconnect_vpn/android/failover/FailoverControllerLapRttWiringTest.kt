package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 7 の配線: [FailoverController] の `lapRttProvider` に本物の
 * [PathQualityDetector.rttSamples] を繋いだとき、一周の記録が**離れる候補の**
 * 応答時間を受け取ること。
 *
 * **この試験が届く範囲と届かない範囲。** [FailoverService] は Android に依存するので
 * 単体試験では動かせない。ここでは `dispatch`（`handle` → 測り直し）と
 * `sampleThroughput` の読み取り失敗分岐を、サービスと同じ順序・同じ呼び出しで
 * 再現する小さな進行役（[dispatch]・[readFailureTick]）を使う。進行役が呼ぶ
 * [PathQualityDetector] と [FailoverController] は**本物**なので、判定器側の約束
 * （`reset` は応答時間も捨てる／`resetThroughput` は残す）が崩れれば落ちる。
 * ただし**サービスの行そのもの**は、ここでは固定できない。そちらは
 * [FailoverServiceWiringTest] がソースの並びで固定する。
 *
 * 各試験は「間違った実装でも通ってしまわないか」を問うて書いてある。
 * 「最良へ戻る」だけの表明は、最良が先頭と同じ配置だと、証拠が空で先頭へ戻る実装と
 * 区別できない。だから**最良は先頭ではない b** に置き、向かった先
 * （`vpn.connectCalls.last()`）まで見る。
 */
class FailoverControllerLapRttWiringTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController
    private lateinit var detector: PathQualityDetector

    private var degraded = false
    private val settings = SlowLinkSettings(enabled = true)

    private val group = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = listOf("uuid-a", "uuid-b", "uuid-c"),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    /** サービスの `lastSlowLinkCandidateKey` と同じ役割。 */
    private var lastKey: String? = null

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        degraded = false
        lastKey = null
        detector = PathQualityDetector(settings)
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            slowLinkProvider = { degraded },
            slowLinkSettingsProvider = { settings },
            lapRttProvider = { detector.rttSamples() },
        )
    }

    /** `FailoverService.slowLinkCandidateKey` と同じ写し。 */
    private fun key(): String? = when (val s = controller.state) {
        is FailoverState.Verifying -> "${s.groupId}#${s.candidateIndex}"
        is FailoverState.Healthy -> "${s.groupId}#${s.candidateIndex}"
        else -> null
    }

    /**
     * `FailoverService.dispatch` と同じ順序: **`handle` が先、測り直しが後**。
     * 順序を入れ替えた版は [dispatchResetFirst]（対照）。
     */
    private fun dispatch(event: FailoverEvent) {
        controller.handle(event)
        val k = key()
        if (k != lastKey) {
            lastKey = k
            detector.reset()
        }
    }

    /**
     * 対照: 測り直しを `handle` の前に置いた版（サービスがやってはならない順序）。
     * 測り直しは鍵の変化にだけ反応するので、**直前の dispatch の結果**にしか効かない。
     */
    private fun dispatchSyncFirst(event: FailoverEvent) {
        val k = key()
        if (k != lastKey) {
            lastKey = k
            detector.reset()
        }
        controller.handle(event)
    }

    /** 対照: 読む側の直前に、鍵と無関係に両方の窓を捨てる（裁定17 が防ぐ事故の再現）。 */
    private fun dispatchUnconditionalReset(event: FailoverEvent) {
        detector.reset()
        controller.handle(event)
    }

    /**
     * `sampleThroughput` の「名前を解決できない」「`/proc/net/dev` を読めない」分岐。
     * 呼ぶ関数は本番と同じ [PathQualityDetector.resetThroughput]。
     */
    private fun readFailureTick() {
        detector.resetThroughput()
    }

    private fun toHealthy(uuid: String) {
        dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = uuid))
        dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = uuid))
        clock.advance(16_000L)
        dispatch(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    private fun confirmAndAdvance(failed: String, next: String) {
        assertTrue(controller.state is FailoverState.FailingOver)
        dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = failed))
        assertEquals(next, vpn.connectCalls.last())
        toHealthy(next)
    }

    /** いまの候補について、プローブ1周期ぶん（5サンプル）を判定器へ渡す。 */
    private fun probeCycle(rtt: Long) {
        detector.onProbe(clock.nowMs(), ProbeOutcome(reachable = true, rttMs = List(5) { rtt }))
    }

    /**
     * a -> b -> c と進み、c で一周が止まる tick まで流す。各候補は離れる前にプローブ
     * 1周期を受け取り、[beforeDeparture] を挟んでから tick する。
     */
    private fun walk(
        rttA: Long, rttB: Long, rttC: Long,
        tickDispatch: (FailoverEvent) -> Unit = ::dispatch,
        beforeDeparture: () -> Unit = {},
    ) {
        dispatch(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        degraded = true

        probeCycle(rttA); beforeDeparture(); clock.advance(5_000L)
        tickDispatch(FailoverEvent.Tick)
        confirmAndAdvance("uuid-a", "uuid-b")

        probeCycle(rttB); beforeDeparture(); clock.advance(5_000L)
        tickDispatch(FailoverEvent.Tick)
        confirmAndAdvance("uuid-b", "uuid-c")

        probeCycle(rttC); beforeDeparture(); clock.advance(5_000L)
        tickDispatch(FailoverEvent.Tick)
    }

    private fun assertReturnsTo(expected: String, message: String) {
        assertTrue(
            "最良へ戻る切替が始まるはず（実際: ${controller.state}）",
            controller.state is FailoverState.FailingOver,
        )
        dispatch(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals(message, expected, vpn.connectCalls.last())
    }

    // ---------------------------------------------------------------- 本体

    @Test
    fun `離れる候補の応答時間が一周の記録に載り、最良の b へ戻る`() {
        // a=400, b=100(最良), c=500。証拠が記録に届かなければ先頭の a へ戻る。
        walk(400, 100, 500)
        assertReturnsTo("uuid-b", "最良は b（先頭ではない）")
    }

    @Test
    fun `バイト数が読めなかった tick をまたいでも、離れる候補の応答時間は記録に載る`() {
        // 守るもの（裁定17）: 読み取り失敗1回で応答時間の窓を捨てない。
        // 各候補の離脱の直前に失敗 tick を挟む。resetThroughput を reset に戻すと
        // 窓が空になり、記録は誰のサンプルも持たず、先頭の a へ戻って落ちる。
        walk(400, 100, 500, beforeDeparture = ::readFailureTick)
        assertReturnsTo("uuid-b", "失敗 tick を挟んでも最良は b")
    }

    @Test
    fun `対照 失敗 tick で両方の窓を捨てる実装だと、証拠が失われて先頭へ戻る`() {
        // 上の試験が「何も判別しない」ものでないことの確認。b が最良なのに a へ
        // 向かうことで、窓の取りこぼしが実際に行き先を変える（＝ここで守っている
        // ものが、実害のあるものだ）ことを示す。
        walk(400, 100, 500, beforeDeparture = { detector.reset() })
        assertReturnsTo("uuid-a", "証拠が無ければグループの先頭")
    }

    @Test
    fun `対照 読む直前に両方の窓を無条件に捨てると、証拠が空になり先頭へ戻る`() {
        // 失敗 tick の取り違え（裁定17）と同じ害を、サンプリングの経路を介さずに
        // 再現する。離れる tick の直前に窓が空だと、b が最良なのに a へ向かう。
        walk(400, 100, 500, tickDispatch = ::dispatchUnconditionalReset)
        assertReturnsTo("uuid-a", "読む前に空なら記録は空")
    }

    @Test
    fun `離れる側の読み取りは、測り直しを handle の前に置いても壊れない（順序が守るのは別のもの）`() {
        // 正直な記録。測り直しは鍵の変化にしか反応しないので、`handle` の前に
        // 置いても離れる tick の読み取りは無傷で、最良の b へ戻れる。つまり
        // 「handle が先」はこの読み取りを守っているのではない。守っているのは
        // 次の試験（測り直しが遅れないこと）で、順序の固定は FailoverServiceWiringTest。
        walk(400, 100, 500, tickDispatch = ::dispatchSyncFirst)
        assertReturnsTo("uuid-b", "鍵の変化に反応する測り直しなら読み取りは無傷")
    }

    @Test
    fun `対照 測り直しを handle の前に置くと、離れた直後の窓に前の候補の証拠が残る`() {
        // 順序（handle が先、測り直しが後）が実際に守っているもの: 状態を変えた
        // dispatch の中で測り直しまで済むこと。逆順だと次の dispatch まで遅れ、
        // その間の採取が前の候補のものとして窓に残る。
        dispatch(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        degraded = true
        probeCycle(400)
        clock.advance(5_000L)
        dispatchSyncFirst(FailoverEvent.Tick)
        assertTrue(controller.state is FailoverState.FailingOver)
        assertEquals("遅れた測り直しは a の証拠を残す", 5, detector.rttSamples().size)
    }

    // ---------------------------------------------------------------- 測り直し

    @Test
    fun `離れた直後に測り直され、次の候補の窓へ前の候補の応答時間は混じらない`() {
        dispatch(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        degraded = true
        probeCycle(400)
        assertEquals(5, detector.rttSamples().size)
        clock.advance(5_000L)
        dispatch(FailoverEvent.Tick)
        // 離れる tick の中で記録へ写したあと、測り直しで空になっている（reset は
        // 応答時間も捨てる）。残っていれば b の最初の判定に a の値が混じる。
        assertTrue(controller.state is FailoverState.FailingOver)
        assertTrue(detector.rttSamples().isEmpty())
        confirmAndAdvance("uuid-a", "uuid-b")
        assertTrue(detector.rttSamples().isEmpty())
    }
}
