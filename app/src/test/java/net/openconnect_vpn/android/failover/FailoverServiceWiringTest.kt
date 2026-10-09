package net.openconnect_vpn.android.failover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailoverService] の配線を**ソースの並びで**固定する試験。
 *
 * `FailoverService` は Android に依存するため単体試験では動かせない。しかし
 * 次の順序・置換は、**1行の入れ替えで静かに壊れ、しかもコンパイルも他の試験も通る**。
 * 振る舞いで固定できない以上、並びで固定するほかない。壊れやすい試験であることは
 * 承知の上で、落ちたときに「何を守っているか」「どうすればよいか」が分かるよう、
 * すべての表明に理由と対処を書く。
 *
 * - `dispatch` は `controller.handle` を `syncSlowLinkCandidate` より先に呼ぶ。
 *   逆順でも離れる tick の読み取りは壊れないが（`FailoverControllerLapRttWiringTest` に
 *   記録）、測り直しが次の dispatch まで遅れ、前の候補の証拠が窓に残る。
 * - `sampleThroughput` の3つの `reset` は意味が違う。張り替えは両方、読み取り失敗は
 *   受信だけ（`PathQualityDetector.resetThroughput` の KDoc、裁定17）。
 * - ティックループは `sampleThroughput()` を `dispatch(Tick)` より先に呼ぶ
 *   （サービス側の注釈どおり、コンパイルも他の試験も強制しない）。
 * - プローブ結果は `onProbe` を `dispatch(ProbeResult)` より先に渡す。
 * - プローブ結果は `runGuardedProbe` 経由で、測っているあいだに候補が変われば捨てる。
 *
 * 並びが崩れれば文字列検索の結果（位置・個数）が変わって落ちる。逆に、
 * **意味を変えない書き方の変更でも落ちうる**——その場合は、落ちた表明が守る性質を
 * 読んだうえで、**試験側を新しい書き方に合わせて直せばよい**（性質が保たれて
 * いることの確認は、落ちた表明のメッセージと上の一覧で足りる）。
 */
class FailoverServiceWiringTest {

    /**
     * コメント行を除いたソース。波括弧の対応もこれで数える（コメントの中の波括弧に
     * 惑わされない）。文字列リテラルの中の波括弧は釣り合っているものだけを前提にする。
     */
    private val code: String = run {
        val rel = "src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt"
        // Gradle の単体試験の作業ディレクトリはモジュール（app）だが、IDE はリポジトリ
        // 直下のことがあるので両方を試す。
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("FailoverService.kt が見つからない（cwd=${File(".").absolutePath}）。" +
                "ファイルを移したなら、この試験の rel を新しい場所に直す")
        f.readText(Charsets.UTF_8).lines()
            .filterNot {
                val t = it.trim()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    /** [signature] の行から、波括弧の対応が閉じるところまで（[code] の上で数える）。 */
    private fun body(signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(
            "$signature がソースに見つからない。関数名・引数を変えたなら、この試験の署名を直す",
            start >= 0,
        )
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
        }
        error("$signature の閉じ括弧が見つからない。文字列リテラルやコメントでない波括弧が" +
            "釣り合っていないか確認する")
    }

    private fun count(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun `dispatch は handle を測り直しより先に呼ぶ`() {
        val d = body("private fun dispatch(event: FailoverEvent)")
        val handle = d.indexOf("controller.handle(event)")
        val sync = d.indexOf("syncSlowLinkCandidate()")
        assertTrue("dispatch に controller.handle(event) が無い。呼び方を変えたなら試験を直す", handle >= 0)
        assertTrue("dispatch に syncSlowLinkCandidate() が無い。呼び方を変えたなら試験を直す", sync >= 0)
        assertTrue(
            "測り直しは handle が状態を変えた結果に反応する。先に置くと、状態を変えた" +
                "dispatch のぶんが次の dispatch まで遅れ、前の候補の証拠が窓に残る。" +
                "syncSlowLinkCandidate() を controller.handle(event) の後ろへ戻す",
            handle < sync,
        )
    }

    @Test
    fun `sampleThroughput の読み取り失敗は受信の窓だけ、張り替えは両方を捨てる`() {
        // 関数の本体**全体**を見る。区間を `if (iface == null)` から切り出すと、その手前に
        // ある全捨て（reset）を見落とす——裁定36: 以前は「張り替え」の分岐が先にあり、
        // 名前が解決できない tick（null != "tunN"）でそちらが走って、この試験は
        // 名前どおりの性質が偽のまま通っていた。
        val b = body("private suspend fun sampleThroughput()")

        val ifaceNull = b.indexOf("if (iface == null)")
        val ifaceChanged = b.indexOf("if (iface != lastVpnIface)")
        val bytesNull = b.indexOf("if (bytes == null)")
        assertTrue(
            "sampleThroughput の3つの分岐は「名前が解決できない」「張り替え」「バイト数が読めない」の" +
                "順に並べる。名前が解決できない tick を先に処理しないと、張り替えの分岐が先に走って" +
                "全捨て（reset）になり、受信だけを捨てる性質（裁定17）が失われる。順序を戻す",
            ifaceNull in 0 until ifaceChanged && ifaceChanged < bytesNull,
        )

        val full = "pathQualityDetector.reset()"
        val partial = "pathQualityDetector.resetThroughput()"

        // 本体全体での個数: 全捨ては張り替えの1回だけ、受信だけの破棄は失敗の2分岐。
        assertEquals(
            "全捨て reset() は張り替えの1か所だけにする。増やすと、測れなかった tick で" +
                "応答時間の証拠を捨てる経路ができる（裁定17）",
            1, count(b, full),
        )
        assertEquals(
            "受信だけを捨てる resetThroughput() は、名前が解決できない・バイト数が読めない の2か所",
            2, count(b, partial),
        )

        // 名前が解決できない分岐は、全捨てに到達する前に、受信だけを捨てて return する。
        val nullBlock = b.substring(ifaceNull, ifaceChanged)
        val bytesBlock = b.substring(bytesNull)
        val changedBlock = b.substring(ifaceChanged, bytesNull)
        assertTrue(
            "名前が解決できない tick は resetThroughput() して return する。全捨てへ落とさない",
            nullBlock.contains(partial) && nullBlock.contains("return"),
        )
        assertFalse("名前が解決できない tick で全捨て reset() をしてはならない（裁定17・36）", nullBlock.contains(full))
        assertTrue(
            "本体の最初の全捨て reset() は、名前が解決できない分岐より後（張り替えの分岐）にある。" +
                "前にあると null の tick でも走る",
            b.indexOf(full) > ifaceNull + nullBlock.length - 1,
        )
        assertTrue("バイト数を読めない tick は resetThroughput() する", bytesBlock.contains(partial))
        assertFalse("バイト数を読めない tick で全捨てをしてはならない（裁定17）", bytesBlock.contains(full))
        assertTrue("張り替えは測る対象が変わるので両方の窓を捨てる。reset() をここに戻す", changedBlock.contains(full))
        assertFalse("張り替えで resetThroughput() だと前の対象の応答時間が残る。reset() にする", changedBlock.contains(partial))

        // 覚えている名前（lastVpnIface）は null の tick で更新しない。更新すると、同じトンネルの
        // 名前が解決できたとき張り替えと取り違えて、応答時間の窓まで捨てる（裁定36）。
        assertFalse(
            "名前が解決できない分岐で lastVpnIface を更新してはならない。同じトンネルの名前が" +
                "戻ったとき張り替えと取り違える。この更新を消す",
            nullBlock.contains("lastVpnIface"),
        )
        assertTrue("張り替えの分岐が lastVpnIface を更新する", changedBlock.contains("lastVpnIface = iface"))
    }

    @Test
    fun `ティックループは採取を Tick より先に行う`() {
        // 裁定43 の深いスリープ復帰の直後に、古い判定が新鮮なサンプルより先に読まれるのを
        // 防ぐ順序（サービス側の注釈）。間にコメントしか挟まない前提で隣接を見る。
        assertTrue(
            "ティックループで sampleThroughput() は dispatch(FailoverEvent.Tick) の直前に置く。" +
                "逆順だと、停止前の古い判定が新鮮なサンプルより先に読まれ、何もしていなかった" +
                "時間を根拠に切り替えうる。呼び出しの順序を戻す（間に別の処理を足したなら、" +
                "この試験の正規表現を直す）",
            Regex("""sampleThroughput\(\)\s+dispatch\(FailoverEvent\.Tick\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `プローブ結果は onProbe を ProbeResult の dispatch より先に渡す`() {
        val onProbe = code.indexOf("pathQualityDetector.onProbe(clock.nowMs(), outcome)")
        val dispatch = code.indexOf("dispatch(FailoverEvent.ProbeResult(outcome.reachable))")
        assertTrue("onProbe の呼び出しが見つからない。呼び方を変えたなら試験を直す", onProbe >= 0)
        assertTrue("dispatch(ProbeResult) の呼び出しが見つからない。呼び方を変えたなら試験を直す", dispatch >= 0)
        assertTrue(
            "ProbeResult が切替を起こすと、その dispatch の測り直しが先に走り、このサンプルが" +
                "次の候補の窓へ入る。onProbe を dispatch(ProbeResult) の前に戻す",
            onProbe < dispatch,
        )
    }

    @Test
    fun `プローブは候補が変わったら結果を捨てるガードを通る`() {
        val guardCall = code.indexOf("runGuardedProbe(")
        val onProbe = code.indexOf("pathQualityDetector.onProbe(clock.nowMs(), outcome)")
        assertTrue(
            "プローブ結果は runGuardedProbe 経由で適用する。測っているあいだに候補が入れ替わると、" +
                "別の候補の結果で Verifying が昇格し、応答時間が別の窓へ混ざる。ガードを戻す",
            guardCall >= 0,
        )
        assertTrue(
            "ガードの鍵は probeGuardKey(controller.state)（接続完了時刻を含む鍵）にする。" +
                "測り直しの鍵 slowLinkCandidateKey だと、Healthy から同じ添字へ再接続した" +
                "Verifying の昇格を捨てられない。鍵を戻す",
            code.contains("candidateKey = { probeGuardKey(controller.state) }"),
        )
        assertFalse(
            "slowLinkCandidateKey にガード用の接続完了時刻を足してはならない。足すと Verifying → " +
                "Healthy の昇格で判定器が空になる。ガード側は probeGuardKey に分けてある",
            body("private fun slowLinkCandidateKey()").contains("connectedAtMs"),
        )
        assertTrue(
            "onProbe はガードの apply の中（runGuardedProbe の後ろ）にある。外へ出すと" +
                "結果を捨てても窓へ入ってしまう。apply の中へ戻す",
            onProbe > guardCall,
        )
    }

    @Test
    fun `旧判定器への参照が残らず、新しい供給関数が渡っている`() {
        assertFalse("slowLinkDetector の参照が残っている。pathQualityDetector へ置き換える", code.contains("slowLinkDetector"))
        assertFalse("SlowLinkDetector( の生成が残っている。PathQualityDetector へ置き換える", code.contains("SlowLinkDetector("))
        assertTrue(
            "プローブは probeTimed(…, PROBE_ATTEMPTS) で呼ぶ（応答時間を採るため）",
            code.contains("probe.probeTimed(probeTarget, currentProbeTimeoutMs(), PROBE_ATTEMPTS)"),
        )
        assertTrue(
            "FailoverController へ slowLinkSettingsProvider を渡す（一周後の許容幅と再開の間隔）",
            code.contains("slowLinkSettingsProvider = { slowLinkSettings }"),
        )
        assertTrue(
            "FailoverController へ slowLinkProvider を渡す（判定器の結果で切り替える）。" +
                "{ false } にすると機能が丸ごと死ぬのに、他の試験は通ってしまう",
            code.contains("slowLinkProvider = { slowLinkSettings.enabled && pathQualityDetector.isDegraded() }"),
        )
        assertTrue(
            "トンネルを持たない状態（鍵が null）で測った応答時間は onProbe へ渡さない（裁定40）。" +
                "ガードは null == null を通すので、素の下層ネットワークの応答時間が窓に入る",
            Regex("""if \(probeGuardKey\(controller\.state\) != null\) \{\s+pathQualityDetector\.onProbe""")
                .containsMatchIn(code),
        )
        assertTrue(
            "FailoverController へ lapRttProvider を渡す（一周の記録が応答時間を読む）",
            code.contains("lapRttProvider = { pathQualityDetector.rttSamples() }"),
        )
    }
}
