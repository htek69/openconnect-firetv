package net.openconnect_vpn.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SettingsScreen.kt` は Compose のファイルで、JVM 単体試験からは動かせない。
 * ここでは**ソースの文面**で、動かして確かめられない2つの約束を固定する。
 * どちらも「正しい実装と間違った実装が、実行時の値では区別できない」ものである。
 *
 * 1. 保存は6項目すべてを運ぶこと（2項目だけで組み直して残りを既定値へ戻す欠陥の再発防止）
 * 2. ステッパーの clamp が範囲の数字を書き写さず、共有の定数を名指すこと
 *
 * ソース文面の検査は壊れやすい（書き方を変えると落ちる）ので、各メッセージに
 * 「守っているもの」と「直し方」を書いた。書き方を正当に変えたなら、この試験の
 * 正規表現を直すのが正しい対応であり、約束そのものを外すのは正しくない。
 */
class SettingsScreenSourceTest {

    private fun screenCode(): String {
        val rel = "src/main/java/net/openconnect_vpn/android/tv/SettingsScreen.kt"
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error(
                "SettingsScreen.kt が見つからない（cwd=${File(".").absolutePath}）。" +
                    "ファイルを移したなら、この試験の rel を新しい場所に直す",
            )
        // コメントの中の記述（この約束を説明する文章自体を含む）を拾わないよう除く
        return f.readText(Charsets.UTF_8)
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }

    /** [open] の位置の `(` に対応する `)` の手前までの引数文字列。 */
    private fun balancedArgs(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        error("括弧が閉じていない")
    }

    @Test
    fun `設定画面は SlowLinkSettings を6項目すべて渡さずに組み直さない`() {
        val code = screenCode()
        val six = listOf(
            "enabled", "slowRxKbps", "rxFloorKbps",
            "degradedRttMs", "degradedJitterMs", "rearmAfterLapMin",
        )
        // loadSlowLinkSettings( / saveSlowLinkSettings( を拾わないよう、直前が識別子の文字でないもの
        val ctor = Regex("""(?<![A-Za-z0-9_])SlowLinkSettings\(""")
        for (m in ctor.findAll(code)) {
            val args = balancedArgs(code, m.range.last)
            val missing = six.filterNot { Regex("""\b$it\s*=""").containsMatchIn(args) }
            assertTrue(
                "SettingsScreen が SlowLinkSettings(...) を組み直しているが、$missing を渡していない。" +
                    "守っているもの: 設定を保存するたびに、渡し忘れた項目が既定値へ黙って戻らないこと" +
                    "（この画面で実際に起きた欠陥。改訂で足した4項目が保存のたびに消えていた）。" +
                    "直し方: 組み直さず、読み込んだ値の copy(...) を保存に渡す",
                missing.isEmpty(),
            )
        }
        assertTrue(
            "SettingsScreen が saveSlowLinkSettings を呼んでいない。保存の経路が消えている",
            code.contains("saveSlowLinkSettings("),
        )
    }

    /**
     * 範囲の数字は `failover.SlowLinkBounds` ただ1か所に置く。
     * ステッパーの clamp が `coerceIn(50, 2000)` のように数字を書き写しても、値は
     * 今の定数と同じなので、**どの値を入れても挙動は変わらない**——実行して確かめる
     * 試験では見分けられない。違いが出るのは、片方の範囲だけを後で変えたとき
     * （画面が許す値と読込が通す値の食い違い。画面で 3000 に設定しても、次の読込で
     * 黙って 2000 になる）で、その時点では誰も気づかない。だから文面で見る。
     */
    @Test
    fun `ステッパーの clamp は範囲の数字を書かず、共有の定数を名指す`() {
        val code = screenCode()
        val targets = mapOf(
            "clampDegradedRttMs" to ("DEGRADED_RTT_MS_MIN" to "DEGRADED_RTT_MS_MAX"),
            "clampDegradedJitterMs" to ("DEGRADED_JITTER_MS_MIN" to "DEGRADED_JITTER_MS_MAX"),
            "clampRxFloorKbps" to ("RX_FLOOR_KBPS_MIN" to "RX_FLOOR_KBPS_MAX"),
            "clampRearmAfterLapMin" to ("REARM_AFTER_LAP_MIN_MIN" to "REARM_AFTER_LAP_MIN_MAX"),
        )
        for ((fn, bounds) in targets) {
            val m = Regex("""fun\s+$fn\s*\(""").find(code)
            assertNotNull("$fn の定義が見つからない（書き方を変えたなら正規表現を直す）", m)
            // 関数の本体は次の空行までの1〜2行
            val body = code.substring(m!!.range.first).substringBefore("\n\n")
            assertTrue(
                "$fn の本体 `$body` が SlowLinkBounds.${bounds.first} を名指していない",
                body.contains("SlowLinkBounds.${bounds.first}"),
            )
            assertTrue(
                "$fn の本体 `$body` が SlowLinkBounds.${bounds.second} を名指していない",
                body.contains("SlowLinkBounds.${bounds.second}"),
            )
            val digits = Regex("""\d+""")
                .findAll(body.replace(Regex("""SlowLinkBounds\.[A-Z_]+"""), "").replace(fn, ""))
                .map { it.value }
                .toList()
            assertEquals(
                "$fn の本体 `$body` に数字 $digits が書かれている。守っているもの: 範囲の" +
                    "定義が SlowLinkBounds ただ1か所であること。数字を書き写すと画面と読込の" +
                    "範囲が食い違いうる。直し方: 数字を消し、定数を名指す",
                emptyList<String>(),
                digits,
            )
        }
    }
}
