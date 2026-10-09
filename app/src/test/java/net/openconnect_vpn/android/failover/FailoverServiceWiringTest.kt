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
 * 次の2つは、**1行の入れ替えや置換で静かに壊れ、しかもコンパイルも他の試験も通る**。
 * 振る舞いで固定できない以上、並びで固定するほかない。壊れやすい試験であることは
 * 承知の上で、落ちたときに「何を守っているか」が分かるよう失敗メッセージに理由を書く。
 *
 * - `dispatch` は `controller.handle`（`lapRttProvider` を読み、状態を変える側）を
 *   `syncSlowLinkCandidate`（応答時間も捨てる側）より先に呼ぶ。逆順でも離れる tick の
 *   読み取りは壊れないが（`FailoverControllerLapRttWiringTest` に記録）、測り直しが
 *   次の dispatch まで遅れ、前の候補の証拠が窓に残る。
 * - `sampleThroughput` の3つの `reset` は意味が違う。張り替えは両方、読み取り失敗は
 *   受信だけ（`PathQualityDetector.resetThroughput` の KDoc、裁定17）。
 *
 * 並びが崩れれば文字列検索の結果（位置・個数）が変わって落ちる。逆に、
 * **書き方を整えただけの変更でも落ちうる**——その場合は試験側を直せばよい。
 */
class FailoverServiceWiringTest {

    private val source: String = run {
        val rel = "src/main/java/net/openconnect_vpn/android/failover/FailoverService.kt"
        // Gradle の単体試験の作業ディレクトリはモジュール（app）だが、IDE はリポジトリ
        // 直下のことがあるので両方を試す。
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("FailoverService.kt が見つからない（cwd=${File(".").absolutePath}）")
        f.readText(Charsets.UTF_8)
    }

    /** [signature] の行から、波括弧の対応が閉じるところまで。 */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature がソースに見つからない", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("$signature の閉じ括弧が見つからない")
    }

    /** コメント行を除く（KDoc や行コメントの中の言及に惑わされない）。 */
    private fun code(text: String): String = text.lines()
        .filterNot {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun count(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun `dispatch は handle を測り直しより先に呼ぶ`() {
        val d = code(body("private fun dispatch(event: FailoverEvent)"))
        val handle = d.indexOf("controller.handle(event)")
        val sync = d.indexOf("syncSlowLinkCandidate()")
        assertTrue("dispatch に controller.handle(event) が無い", handle >= 0)
        assertTrue("dispatch に syncSlowLinkCandidate() が無い", sync >= 0)
        assertTrue(
            "測り直しは handle が状態を変えた結果に反応する。先に置くと、状態を変えた" +
                "dispatch のぶんが次の dispatch まで遅れ、前の候補の証拠が窓に残る",
            handle < sync,
        )
    }

    @Test
    fun `sampleThroughput の読み取り失敗は受信の窓だけ、張り替えは両方を捨てる`() {
        val b = code(body("private suspend fun sampleThroughput()"))

        val ifaceChanged = b.indexOf("if (iface != lastVpnIface)")
        val ifaceNull = b.indexOf("if (iface == null)")
        val bytesNull = b.indexOf("if (bytes == null)")
        assertTrue(
            "3つの分岐がこの順に並んでいない",
            ifaceChanged in 0 until ifaceNull && ifaceNull < bytesNull,
        )

        val full = "pathQualityDetector.reset()"
        val partial = "pathQualityDetector.resetThroughput()"

        // 3つの分岐それぞれの区間に、意味に合う方がちょうど1回ずつ出る。
        val changedBlock = b.substring(ifaceChanged, ifaceNull)
        val nullBlock = b.substring(ifaceNull, bytesNull)
        val bytesBlock = b.substring(bytesNull)
        assertEquals("張り替えは両方の窓を捨てる", 1, count(changedBlock, full))
        assertFalse(changedBlock.contains(partial))
        assertEquals("名前を解決できない tick は受信だけ", 1, count(nullBlock, partial))
        assertFalse("名前を解決できない tick で応答時間を捨ててはならない", nullBlock.contains(full))
        assertEquals("バイト数を読めない tick は受信だけ", 1, count(bytesBlock, partial))
        assertFalse("バイト数を読めない tick で応答時間を捨ててはならない", bytesBlock.contains(full))
    }

    @Test
    fun `旧判定器への参照が残らず、新しい供給関数が渡っている`() {
        val c = code(source)
        assertFalse(c.contains("slowLinkDetector"))
        assertFalse(c.contains("SlowLinkDetector("))
        assertTrue(c.contains("probe.probeTimed(probeTarget, currentProbeTimeoutMs(), PROBE_ATTEMPTS)"))
        assertTrue(c.contains("slowLinkSettingsProvider = { slowLinkSettings }"))
        assertTrue(c.contains("lapRttProvider = { pathQualityDetector.rttSamples() }"))
    }
}
