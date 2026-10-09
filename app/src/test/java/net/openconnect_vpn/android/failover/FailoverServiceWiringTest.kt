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
        val b = body("private suspend fun sampleThroughput()")

        val ifaceChanged = b.indexOf("if (iface != lastVpnIface)")
        val ifaceNull = b.indexOf("if (iface == null)")
        val bytesNull = b.indexOf("if (bytes == null)")
        assertTrue(
            "sampleThroughput の3つの分岐（張り替え・名前が解決できない・バイト数が読めない）が" +
                "この順に並んでいない。並べ替えたなら、この試験の区間の切り方を直す",
            ifaceChanged in 0 until ifaceNull && ifaceNull < bytesNull,
        )

        val full = "pathQualityDetector.reset()"
        val partial = "pathQualityDetector.resetThroughput()"

        // 3つの分岐それぞれの区間に、意味に合う方がちょうど1回ずつ出る。
        val changedBlock = b.substring(ifaceChanged, ifaceNull)
        val nullBlock = b.substring(ifaceNull, bytesNull)
        val bytesBlock = b.substring(bytesNull)
        assertEquals(
            "張り替えは測る対象が変わるので両方の窓を捨てる。reset() をここに戻す",
            1, count(changedBlock, full),
        )
        assertFalse(
            "張り替えで resetThroughput() だと前の対象の応答時間が残る。reset() にする",
            changedBlock.contains(partial),
        )
        assertEquals(
            "名前を解決できない tick は受信の窓だけを捨てる（裁定17）。resetThroughput() にする",
            1, count(nullBlock, partial),
        )
        assertFalse(
            "名前を解決できない tick で応答時間を捨ててはならない（裁定17）。resetThroughput() にする",
            nullBlock.contains(full),
        )
        assertEquals(
            "バイト数を読めない tick は受信の窓だけを捨てる（裁定17）。resetThroughput() にする",
            1, count(bytesBlock, partial),
        )
        assertFalse(
            "バイト数を読めない tick で応答時間を捨ててはならない（裁定17）。resetThroughput() にする",
            bytesBlock.contains(full),
        )
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
            "ガードの鍵は slowLinkCandidateKey（測り直しと同じ鍵）にする。別の鍵にすると、" +
                "測り直しが反応する入れ替わりとガードが反応する入れ替わりがずれる",
            code.contains("candidateKey = ::slowLinkCandidateKey"),
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
            "FailoverController へ lapRttProvider を渡す（一周の記録が応答時間を読む）",
            code.contains("lapRttProvider = { pathQualityDetector.rttSamples() }"),
        )
    }
}
