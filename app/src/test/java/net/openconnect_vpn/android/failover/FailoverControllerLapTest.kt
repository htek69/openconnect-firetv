package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 仕様書 §2-A（一周後の行先）と §2-B（再開）。
 *
 * **旧仕様 §4-5 は「停止する」とだけ書いて、どこで停止するかを書いていなかった。**
 * 実装は前方にしか進まないため最後のメンバーに居座っていた（実機で確認）。
 *
 * 各テストは「**間違った実装でも通ってしまわないか**」を問うて書いてある。特に:
 * - 「一周が止まった」だけの表明は、最良へ戻る実装でも最後に居座る実装でも通る。
 *   だから**どの接続先へ向かったか**（`vpn.connectCalls.last()`）まで見る
 * - 「時間が経ったら切り替わった」は、判定がまだ真だったから切り替わっただけかも
 *   しれない。再開が働いたことは、**判定を偽にしたまま時間を進めて何も起きず、
 *   そのあと判定を真にして初めて新しい一周が始まる**ことで示す
 */
class FailoverControllerLapTest {

    private lateinit var clock: FakeClock
    private lateinit var vpn: FakeVpnController
    private lateinit var network: FakeNetworkGate
    private lateinit var controller: FailoverController

    private var degraded = false
    private var settings = SlowLinkSettings(enabled = true)
    private var lapRtt: List<Long> = emptyList()
    private var needsFirstLogin: Set<String> = emptySet()
    private var members: List<String> = listOf("uuid-a", "uuid-b", "uuid-c")

    private val group
        get() = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = members,
            autoFailoverEnabled = true,
            config = FailoverConfig(),
        )

    @Before
    fun setUp() {
        clock = FakeClock(1_000L)
        vpn = FakeVpnController()
        network = FakeNetworkGate(available = true)
        degraded = false
        settings = SlowLinkSettings(enabled = true)
        lapRtt = emptyList()
        needsFirstLogin = emptySet()
        members = listOf("uuid-a", "uuid-b", "uuid-c")
        controller = FailoverController(
            groupsProvider = { listOf(group) },
            clock = clock,
            vpn = vpn,
            network = network,
            needsFirstLoginProvider = { uuid -> uuid in needsFirstLogin },
            slowLinkProvider = { degraded },
            slowLinkSettingsProvider = { settings },
            lapRttProvider = { lapRtt },
        )
    }

    /** [uuid] を `Healthy` まで進める（猶予も越える）。 */
    private fun toHealthy(uuid: String) {
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connecting, uuid = uuid))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Connected, uuid = uuid))
        clock.advance(16_000L)
        controller.handle(FailoverEvent.ProbeResult(reachable = true))
        assertTrue(controller.state is FailoverState.Healthy)
    }

    /** `FailingOver` から切断確認を与えて次候補を `Healthy` まで進める。 */
    private fun confirmAndAdvance(failed: String, next: String) {
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = failed))
        assertEquals(next, vpn.connectCalls.last())
        toHealthy(next)
    }

    private fun samples(rtt: Long, n: Int = 5): List<Long> = List(n) { rtt }

    /** 現候補を離れる tick。その候補の応答時間を [rtt] として与える。 */
    private fun tickWith(rtt: List<Long>) {
        lapRtt = rtt
        controller.handle(FailoverEvent.Tick)
    }

    /**
     * a -> b -> c と進み、c で一周が止まるところまで流す（c の tick まで）。
     * 各候補の応答時間は引数で与える。判定は真のまま。
     */
    private fun walkToLastAndStop(a: List<Long>, b: List<Long>, c: List<Long>) {
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        degraded = true
        tickWith(a)
        confirmAndAdvance("uuid-a", "uuid-b")
        tickWith(b)
        confirmAndAdvance("uuid-b", "uuid-c")
        tickWith(c)
    }

    // ------------------------------------------------------------ §2-A 行先

    @Test
    fun `一周したら応答時間が最良だった候補へ戻る`() {
        // a=400, b=100(最良), c=500。最後のメンバー c に居座る実装なら、ここで
        // FailingOver に入らず Healthy のまま。最良へ戻る実装だけが b へ向かう。
        walkToLastAndStop(samples(400), samples(100), samples(500))

        assertTrue(
            "最良へ戻る切替が始まるはず（実際: ${controller.state}）",
            controller.state is FailoverState.FailingOver,
        )
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-b", vpn.connectCalls.last())
        val connecting = controller.state as FailoverState.Connecting
        assertEquals(1, connecting.candidateIndex)
    }

    @Test
    fun `最良へ戻る切替は一周の予算が尽きていても行われ、一度きりである`() {
        // メンバー3件なので予算は2。a->b->c で使い切った後にも戻る切替は行われる
        // （予算の門に塞がれない）。そして戻った先で判定が真のまま続いても、
        // 二度目は起きない。
        walkToLastAndStop(samples(100), samples(400), samples(500))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-a", vpn.connectCalls.last())  // a が最良
        toHealthy("uuid-a")

        val connects = vpn.connectCalls.size
        val disconnects = vpn.disconnectCalls
        repeat(5) {
            clock.advance(30_000L)
            tickWith(samples(900))
        }
        assertEquals("二度目の切替があってはならない", connects, vpn.connectCalls.size)
        assertEquals(disconnects, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `戻り先が現候補と同じなら切り替えない`() {
        walkToLastAndStop(samples(400), samples(400), samples(100))  // c が最良
        // 無駄な切断を作らない。ただ止めるだけで、状態も接続も動かない。
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(3, vpn.connectCalls.size)
        val disconnects = vpn.disconnectCalls

        // 止まったので、判定が真のままでも以後は何も起きない。
        clock.advance(60_000L)
        tickWith(samples(900))
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(3, vpn.connectCalls.size)
        assertEquals(disconnects, vpn.disconnectCalls)
    }

    @Test
    fun `どの候補もサンプルが足りなければグループの先頭へ戻る`() {
        // 現候補 c は最後。「先頭」と「居座る」を区別するため、c から a へ向かうことを見る。
        walkToLastAndStop(listOf(10L, 20L), listOf(10L, 20L), listOf(10L, 20L))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-a", vpn.connectCalls.last())
    }

    @Test
    fun `サンプルが足りない候補は、記録上いちばん速くても比較から外れる`() {
        // b は 2 サンプルしか無い（最速に見える）。比較に入れてしまう実装は b へ戻る。
        // 正しくは、5 サンプルある a(700) と c(300) だけを比べ、c（現候補）が最良。
        walkToLastAndStop(samples(700), listOf(50L, 50L), samples(300))
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(3, vpn.connectCalls.size)
    }

    @Test
    fun `応答時間は離れる瞬間の一度だけ貯める（tick ごとに貯めない）`() {
        // a は窓に 3 サンプルしか無い（最速だが不足）。tick のたびに貯める実装は、
        // 判定が偽のあいだの tick で 3 を何度も足して 5 を超え、a を適格にしてしまい、
        // 最後に a へ戻る。離れる瞬間の一度だけなら 3 のまま不足で、a は外れる。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        lapRtt = listOf(10L, 10L, 10L)
        repeat(4) { controller.handle(FailoverEvent.Tick) }  // 判定は偽
        degraded = true
        controller.handle(FailoverEvent.Tick)
        confirmAndAdvance("uuid-a", "uuid-b")
        tickWith(samples(200))
        confirmAndAdvance("uuid-b", "uuid-c")
        tickWith(samples(300))

        // 適格なのは b(200) と c(300)。差は 100 で許容(150)未満なので同等、
        // ばらつきは同じ 0、順序で b。
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `判定が偽なら一周の予算が尽きていても離れない`() {
        // 最後のメンバーに着いたが、そこは健全。一周が尽きたという理由だけで
        // 離れてはならない（「遅いときだけ動く」が機能の前提）。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        degraded = true
        tickWith(samples(100))
        confirmAndAdvance("uuid-a", "uuid-b")
        tickWith(samples(400))
        confirmAndAdvance("uuid-b", "uuid-c")

        degraded = false  // c は健全
        tickWith(samples(500))
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(3, vpn.connectCalls.size)

        // 一周はまだ止まっていない。のちに c が劣化したら、そのとき戻る。
        degraded = true
        tickWith(samples(500))
        assertTrue(controller.state is FailoverState.FailingOver)
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-a", vpn.connectCalls.last())
    }

    @Test
    fun `最良でも初回ログインが未了の候補へは無人で戻らない`() {
        // a が最良だが、認証情報が無い（人が居なければ成功しえない）。無人の経路で
        // 起動してはならないので、選べる中の最良 b へ向かう。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        degraded = true
        tickWith(samples(100))
        confirmAndAdvance("uuid-a", "uuid-b")
        tickWith(samples(200))
        confirmAndAdvance("uuid-b", "uuid-c")
        needsFirstLogin = setOf("uuid-a")  // a の認証情報が無くなった（想定）
        tickWith(samples(300))

        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-b", vpn.connectCalls.last())
    }

    @Test
    fun `速度起因の切替を一度もしていなければ、最後の候補でも戻らない`() {
        // 死活で最後の候補まで来ただけで、速度による一周は始まっていない。比べる相手が
        // 無いので、「サンプル不足なら先頭へ」の規則を適用して a（死活で見限った相手）
        // へ戻ってはならない。
        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        toHealthy("uuid-a")
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        confirmAndAdvance("uuid-a", "uuid-b")
        repeat(3) {
            clock.advance(31_000L)
            controller.handle(FailoverEvent.ProbeResult(reachable = false))
        }
        confirmAndAdvance("uuid-b", "uuid-c")

        val connects = vpn.connectCalls.size
        val disconnects = vpn.disconnectCalls
        degraded = true
        tickWith(samples(300))
        assertTrue(controller.state is FailoverState.Healthy)
        assertEquals(connects, vpn.connectCalls.size)
        assertEquals(disconnects, vpn.disconnectCalls)
    }

    // ------------------------------------------------------------ 戻り先の退避

    /**
     * 戻る切替が始まってから切断確認が届くまでに、選んだ行き先が使えなくなった場合。
     * a=700 / b=100(最良) / c=700 で c に着いて戻る切替が始まる。ここで b が
     * 使えなくなると、**b から前方へ探す実装は c（いま離れたばかりの遅い候補）へ
     * 戻る**。製品は先頭から探し直し、無人で起動してよい最初のメンバー a へ向かう。
     */
    @Test
    fun `戻り先が切断待ちのあいだに初回ログイン未了になったら、前方へ流れず先頭から探す`() {
        walkToLastAndStop(samples(700), samples(100), samples(700))
        assertTrue(controller.state is FailoverState.FailingOver)

        needsFirstLogin = setOf("uuid-b")  // 行き先が無人で起動できなくなった
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))

        val landed = vpn.connectCalls.last()
        assertEquals("uuid-a", landed)
        assertTrue(
            "離れた c（位置2）より前方へ着いてはならない",
            members.indexOf(landed) < members.indexOf("uuid-c"),
        )
        assertEquals(0, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    @Test
    fun `戻り先が切断待ちのあいだにグループから外されたら、前方へ流れず先頭から探す`() {
        walkToLastAndStop(samples(700), samples(100), samples(700))
        assertTrue(controller.state is FailoverState.FailingOver)

        members = listOf("uuid-a", "uuid-c")  // 行き先 b が一覧から消えた
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))

        val landed = vpn.connectCalls.last()
        assertEquals("uuid-a", landed)
        assertTrue(members.indexOf(landed) < members.indexOf("uuid-c"))
        assertEquals(0, (controller.state as FailoverState.Connecting).candidateIndex)
    }

    // ------------------------------------------------------------ §2-B 再開

    @Test
    fun `rearmAfterLapMin が 0 なら、いくら経っても再開しない`() {
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 0)
        walkToLastAndStop(samples(100), samples(400), samples(500))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")  // a へ戻って健全、判定は真のまま

        val connects = vpn.connectCalls.size
        repeat(3) {
            clock.advance(24L * 60 * 60 * 1000)  // 24時間ずつ
            tickWith(samples(900))
        }
        // 0 を「すぐ再開」と取り違える実装は、記録を消して a から前へ進み、ここで
        // FailingOver に入る（判定が真のままなので）。
        assertEquals("再開してはならない", connects, vpn.connectCalls.size)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `経過しても再開だけでは切り替わらない（判定が偽のあいだは静か）`() {
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 30)
        walkToLastAndStop(samples(100), samples(400), samples(500))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")

        degraded = false  // 状況が改善した
        val connects = vpn.connectCalls.size
        val disconnects = vpn.disconnectCalls
        clock.advance(31L * 60 * 1000)
        tickWith(samples(50))
        assertEquals("判定が偽なら何も起きない", connects, vpn.connectCalls.size)
        assertEquals(disconnects, vpn.disconnectCalls)
        assertTrue(controller.state is FailoverState.Healthy)
    }

    @Test
    fun `再開は一周の記録を消す（消えたあとで判定が真になれば新しい一周が始まる）`() {
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 30)
        walkToLastAndStop(samples(100), samples(400), samples(500))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")

        // まだ経っていない（29分）: 判定が真でも止まったまま。
        val connects = vpn.connectCalls.size
        clock.advance(29L * 60 * 1000)
        tickWith(samples(900))
        assertEquals("経過前は再開しない", connects, vpn.connectCalls.size)

        // 経過後、まず判定が偽の tick で記録だけが消える（何も起きない）。
        clock.advance(2L * 60 * 1000)
        degraded = false
        tickWith(samples(900))
        assertEquals(connects, vpn.connectCalls.size)

        // そのあとで判定が真になる。記録が消えていなければ「止まっている」ので動かない。
        // 動いたなら、再開が記録を消していた証拠である。
        degraded = true
        tickWith(samples(900))
        assertTrue(
            "再開後に判定が真なら新しい一周が始まるはず（実際: ${controller.state}）",
            controller.state is FailoverState.FailingOver,
        )
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-a"))
        assertEquals("uuid-b", vpn.connectCalls.last())  // 前方へ（新しい一周）
    }

    @Test
    fun `再開は前の一周の応答時間も消す`() {
        // 一周目: a=900, b=100(最良), c=900 → b へ戻る。
        // 再開後の二周目は b から始まり c で止まる。b は 3 サンプル(不足)、c は 5。
        // 二周目の記録だけで比べれば c だけが適格で c が最良 → c に留まる。
        // 一周目の b の 5 サンプルが残っていれば b が 8 サンプルで適格になり、b へ戻る。
        settings = SlowLinkSettings(enabled = true, rearmAfterLapMin = 30)
        walkToLastAndStop(samples(900), samples(100), samples(900))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        assertEquals("uuid-b", vpn.connectCalls.last())
        toHealthy("uuid-b")

        clock.advance(31L * 60 * 1000)
        degraded = false
        tickWith(samples(100))  // 記録が消える

        degraded = true
        tickWith(listOf(100L, 100L, 100L))  // b を離れる（二周目。3 サンプル）
        confirmAndAdvance("uuid-b", "uuid-c")
        tickWith(samples(500))  // c で止まる

        assertTrue(
            "二周目の記録だけなら c が最良で、留まるはず（実際: ${controller.state}）",
            controller.state is FailoverState.Healthy,
        )
        assertEquals(5, vpn.connectCalls.size)
    }

    // ------------------------------------------------------------ 既存の解除経路

    @Test
    fun `利用者の接続は一周の記録を消す`() {
        walkToLastAndStop(samples(400), samples(400), samples(100))  // c に留まって止まる
        assertTrue(controller.state is FailoverState.Healthy)

        controller.handle(FailoverEvent.UserConnectGroup("g1"))
        controller.handle(FailoverEvent.VpnStateChanged(VpnCoreState.Disconnected, uuid = "uuid-c"))
        toHealthy("uuid-a")
        degraded = true
        tickWith(samples(400))
        assertTrue(
            "止まったままなら動かない。動くのは記録が消えていたから（実際: ${controller.state}）",
            controller.state is FailoverState.FailingOver,
        )
    }
}
