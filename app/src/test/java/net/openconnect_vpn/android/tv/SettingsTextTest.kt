package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.PathQualityDetector
import net.openconnect_vpn.android.failover.SlowLinkSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数字の検査は**既定値でやらない。** 既定値で「250 が出る」を見ても、数字を文面に
 * 直書きした実装と設定から導く実装を区別できない。既定値と**ちがう**値を入れ、
 * 入れた値が出て、既定値が**出ない**ことを見る（この対で初めて「導出」と言える）。
 *
 * 非既定値は、どれも他の数字の部分文字列にならず、窓の 60 の部分文字列にもならない
 * ように選んである（333 / 222 / 111 / 77）。たとえば既定の 250 は 50 を含むので、
 * 既定値どうしの `contains` は独立した検査にならない。
 */
class SettingsTextTest {

    private val custom = SlowLinkSettings(
        degradedRttMs = 333,
        degradedJitterMs = 222,
        rxFloorKbps = 111,
        rearmAfterLapMin = 77,
    )

    @Test
    fun `数字は設定値から出て、既定値は出ない`() {
        val t = SettingsText.pathQualitySummary(custom)
        // 出ること。「ms を超え」「kbps を下回」「分で再開」まで含めて、
        // 数字が意図した文脈に入っていることを見る（取り違えの検出）
        assertTrue(t, t.contains("往復が 333 ms を超え"))
        assertTrue(t, t.contains("ばらつきが 222 ms を超える"))
        assertTrue(t, t.contains("受信が 111 kbps を下回"))
        assertTrue(t, t.contains("一周したあと 77 分で再開"))
        // 出ないこと。直書きの実装はここで落ちる
        assertFalse(t, t.contains("250"))
        assertFalse(t, t.contains("150"))
        assertFalse(t, t.contains("50"))
    }

    @Test
    fun `窓の長さは引数から出て、既定の 60 は出ない`() {
        // 窓を 45 にしたとき 45 が出て 60 が出ないこと。文面に 60 を直書きした実装は
        // ここで落ちる。45 は既定値の文面（250 / 150 / 50）のどれの部分文字列でもない
        val t = SettingsText.pathQualitySummary(SlowLinkSettings(), windowSec = 45)
        assertTrue(t, t.contains("状態が 45 秒続いたとき"))
        assertFalse(t, t.contains("60 秒"))
    }

    @Test
    fun `窓の長さの既定は検知器の定数を読む`() {
        // 既定引数が検知器の定数と食い違っていないこと。ただし**これだけでは**
        // 「既定引数に 60 を直書き」を検出できない（値が同じなので）。直書きの検出は
        // 下のソース文面の試験が担い、こちらは既定の配線の健全性を見る
        val t = SettingsText.pathQualitySummary(SlowLinkSettings())
        assertTrue(t, t.contains("状態が ${PathQualityDetector.WINDOW_SEC} 秒続いたとき"))
    }

    /**
     * **振る舞いでは捉えられない最後の穴を、ソースの文面で塞ぐ。**
     * `windowSec: Int = 60` と書いても、定数が今 60 である限り、どの振る舞いの試験も
     * 通る（`const val` は試験から書き換えられない）。しかし `WINDOW_SEC` を 90 にした日に
     * 画面だけが 60 を言い続ける。裁定26 が塞いだ穴の、一段上の再発である。
     * 同じ理由の先例は `FailoverServiceWiringTest`。
     *
     * コメントは除いてから見る（コメントに 60 や WINDOW_SEC と書いてあっても惑わされない）。
     */
    @Test
    fun `窓の長さの既定引数は数字ではなく検知器の定数を名指す`() {
        val rel = "src/main/java/net/openconnect_vpn/android/tv/SettingsText.kt"
        // Gradle の単体試験の作業ディレクトリはモジュール（app）だが、IDE はリポジトリ
        // 直下のことがあるので両方を試す
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("SettingsText.kt が見つからない（cwd=${File(".").absolutePath}）。" +
                "ファイルを移したなら、この試験の rel を新しい場所に直す")
        val code = f.readText(Charsets.UTF_8)
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
        val m = Regex("""windowSec\s*:\s*Int\s*=\s*([^,)\n]+)""").find(code)
        val expr = m?.groupValues?.get(1)?.trim()
        assertTrue(
            "SettingsText.pathQualitySummary の windowSec の既定が `$expr` になっている。" +
                "守っているもの: 画面の文面の窓の長さを PathQualityDetector.WINDOW_SEC の" +
                "ただ一か所から導くこと（数字を書くと、定数を変えたとき画面だけが古い値を言う）。" +
                "引数の名前や形を正当に変えたなら、この試験の正規表現を新しい書き方に直すのが" +
                "正しい対応である。既定を数字に戻すのは正しくない",
            expr == "PathQualityDetector.WINDOW_SEC",
        )
    }

    @Test
    fun `待機中は切り替わらない旨が出る`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings())
        assertTrue(t.contains("待機中は切り替わりません"))
        assertFalse("警告に差し替わっていないこと", t.contains("待機中でも切り替わることがあります"))
    }

    @Test
    fun `受信の下限を 0 にすると行が消えず警告に変わる`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings(rxFloorKbps = 0))
        assertTrue("警告を出すこと", t.contains("待機中でも切り替わることがあります"))
        assertFalse("安心の文を残さないこと", t.contains("待機中は切り替わりません"))
        // 0 のとき「0 kbps を下回っているあいだは判定しません」という嘘の文にならない
        assertFalse("下限の行に落ちていないこと", t.contains("kbps を下回"))
    }

    @Test
    fun `再開しない設定ではそう書く`() {
        val t = SettingsText.pathQualitySummary(SlowLinkSettings(rearmAfterLapMin = 0))
        assertTrue(t.contains("一周したあとは再開しません"))
        assertFalse("再開の文を出さないこと", t.contains("で再開します"))
    }

    @Test
    fun `再開する設定では「再開しても切り替わらない」が出て、再開しないとは書かない`() {
        // 分数そのものの検査は数字のテストが持つ。ここは固定文言だけ
        val t = SettingsText.pathQualitySummary(custom)
        assertTrue(t.contains("再開しても、条件が揃わなければ切り替わりません"))
        assertFalse(t.contains("再開しません"))
    }
}
