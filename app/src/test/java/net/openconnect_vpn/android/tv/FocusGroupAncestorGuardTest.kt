package net.openconnect_vpn.android.tv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁定88（Low 4）: **ソース検査による番人**。裁定87 の回帰（祖先の
 * `focusProperties` が子孫の `focusGroup()` を無効化して D-pad が死ぬ）に
 * 対して、テストも検出手段も無いまま同種の穴が4度開いたことへの対策である。
 *
 * ## 何を根拠にしているか
 *
 * Compose の `fetchFocusProperties()` は
 * `visitSelfAndAncestors(FocusProperties, untilType = FocusTarget)` で
 * **祖先の `focusProperties` を後から適用する**ため、祖先の指定が子孫の指定に
 * 勝つ。`Modifier.focusGroup()` は `focusProperties { canFocus = false }
 * .focusTarget()` そのものなので、祖先の `Column`/`Row` に
 * `focusProperties { canFocus = true }` が付いていると、ボタン行の
 * `focusGroup()` の `canFocus = false` が打ち消され、**セマンティクスを持たない
 * `Row` 自身にフォーカスが乗る**（実機ではフォーカスが消えたように見え、
 * `uiautomator dump` からもボタンが消える。裁定87 で計測済み）。
 *
 * ## 何を検出するか（意図的に粗い）
 *
 * `app/src/main/java/net/openconnect_vpn/android/tv/` の各 `.kt` を読み、
 *
 * 1. コメント（`//` と `/* */`。KDoc を含む）を取り除いたうえで
 * 2. その**ファイル内に `focusGroup()` の呼び出しがあり**、かつ
 * 3. 同じファイル内のコンテナ（`Column` / `Row` / `LazyColumn` / `LazyRow` /
 *    `Box`）の**引数リストに `focusProperties` が現れる**
 *
 * という組み合わせを失敗にする。どのプロパティを設定しているか（`canFocus` か
 * `down` か）は**見ない**。この組み合わせ自体が「祖先の指定が子孫の
 * `focusGroup()` を打ち消しうる」危険な形であり、成立しているかどうかは
 * Compose の探索規則を追わないと分からない（＝実機で確かめ直す必要がある）
 * ためである。
 *
 * ## 限界（何を見逃すか。依存を増やさない範囲での割り切り）
 *
 * - **同一ファイル内しか見ない。** `focusGroup()` を持つコンポーザブルが
 *   別ファイルにあり、その祖先の `focusProperties` がこのファイルにある、
 *   という組み合わせは見逃す（例: [TvFieldRow] のように呼び出し側が
 *   `modifier` を渡す構造）。
 * - **コンテナ名は上の5つに限る。** `FlowRow` や独自のレイアウトコンポーザブルは
 *   見逃す。
 * - **`focusProperties` がコンテナの引数リストに字面として現れる場合だけ。**
 *   `val m = Modifier.focusProperties { ... }` を経由して
 *   `Column(modifier = m)` と書けば見逃す。
 * - コメントの除去は文字列リテラルを考慮しない単純な走査である
 *   （`"// ..."` のような文字列を含むソースでは誤って削る可能性がある。
 *   現状の `tv/` には無い）。
 * - **祖先・子孫の関係そのものは判定していない。** 同じファイルに両方が
 *   ある、というだけで失敗にする（偽陽性になりうる。実際
 *   `HomeScreen.kt` の `Column` の `focusProperties` は、そのファイルに
 *   `focusGroup()` が1つも無いという理由で成立している——このテストは
 *   その前提が崩れた瞬間に落ちる、という形で見張っている）。
 *
 * つまりこれは完全な静的解析ではなく、「この組み合わせは危険」と
 * ビルド前に気づくための番人である。落ちたときは、`focusProperties` を
 * 祖先から外す（裁定87 の対策のように、そもそも背後のツリーを組み立てない、
 * など）か、実機で D-pad の着地先を測り直すこと。
 */
class FocusGroupAncestorGuardTest {

    @Test
    fun `focusGroup を使うファイルの祖先コンテナに focusProperties を付けない`() {
        val files = tvSourceFiles()
        // 探索そのものが壊れていたら（ディレクトリを見つけられない・0件）
        // このテストは常に緑になってしまうので、先に件数を固定する。
        assertTrue("tv/ の .kt が見つからない（作業ディレクトリの想定が崩れた）", files.size >= 10)

        val violations = mutableListOf<String>()
        for (file in files) {
            val code = stripComments(file.readText())
            if (!code.contains("focusGroup()")) continue
            for (container in containerArgumentLists(code)) {
                if (container.arguments.contains("focusProperties")) {
                    violations.add(
                        "${file.name}: ${container.name}(...) の引数に focusProperties がある" +
                            "（同じファイルに focusGroup() があるため、祖先の指定が" +
                            "子孫の focusGroup() を打ち消しうる。裁定87 参照）:" +
                            " ${container.arguments.replace(Regex("\\s+"), " ").take(120)}",
                    )
                }
            }
        }

        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    /**
     * 上のテストが本当に検出できることを、同じ判定関数に既知の危険な形を
     * 与えて確かめる（裁定87 の回帰そのものの縮小版）。これが無いと
     * 「判定が何も見ていないのに緑」という失敗に気づけない。
     */
    @Test
    fun `裁定87 の形を与えると危険だと判定できる`() {
        val regression = """
            @Composable
            fun Screen() {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = pendingDelete == null },
                ) {
                    Row(modifier = Modifier.focusGroup()) {
                        Button(onClick = {}) { Text("保存") }
                    }
                }
            }
        """.trimIndent()

        val code = stripComments(regression)
        assertTrue(code.contains("focusGroup()"))
        assertTrue(
            containerArgumentLists(code).any {
                it.name == "Column" && it.arguments.contains("focusProperties")
            },
        )
    }

    /** コメントの中の `focusProperties` / `focusGroup()` に反応しないことの確認。 */
    @Test
    fun `コメントの中の記述は判定に使わない`() {
        val commented = """
            // 外側の Column に focusProperties を付けると focusGroup() を打ち消す。
            /* Column(modifier = Modifier.focusProperties { canFocus = true }) */
            fun f() = Unit
        """.trimIndent()

        val code = stripComments(commented)
        assertTrue(code, !code.contains("focusProperties"))
        assertTrue(code, !code.contains("focusGroup()"))
    }

    private data class ContainerCall(val name: String, val arguments: String)

    /**
     * [code] 中のコンテナ呼び出しについて、その**引数リスト**（`(` から
     * 対応する `)` まで）を返す。末尾のコンテンツラムダ（`{ ... }`）は
     * 閉じ括弧より後なので含まれない——つまり中に入れ子で書かれた別の
     * コンポーザブルの `modifier` を誤って拾わない。
     */
    private fun containerArgumentLists(code: String): List<ContainerCall> {
        val result = mutableListOf<ContainerCall>()
        val pattern = Regex("""(^|[^A-Za-z0-9_.])(Column|Row|LazyColumn|LazyRow|Box)\s*\(""")
        for (match in pattern.findAll(code)) {
            val open = code.indexOf('(', startIndex = match.range.last)
            if (open < 0) continue
            var depth = 0
            var i = open
            while (i < code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            if (i >= code.length) continue
            result.add(ContainerCall(match.groupValues[2], code.substring(open + 1, i)))
        }
        return result
    }

    /** `//` 行コメントと `/* */` ブロックコメント（KDoc を含む）を取り除く。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            if (source.startsWith("//", i)) {
                while (i < source.length && source[i] != '\n') i++
            } else if (source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2)
                i = if (end < 0) source.length else end + 2
            } else {
                out.append(source[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * `tv/` の `.kt` を集める。Gradle のユニットテストは作業ディレクトリが
     * モジュール（`app/`）になるが、IDE から実行するとリポジトリ直下になる
     * ことがあるので、両方（と念のため親方向）を探す。
     */
    private fun tvSourceFiles(): List<File> {
        val relative = "src/main/java/net/openconnect_vpn/android/tv"
        var base: File? = File(".").absoluteFile
        val candidates = mutableListOf<File>()
        var hops = 0
        while (base != null && hops < 4) {
            candidates.add(File(base, relative))
            candidates.add(File(base, "app/$relative"))
            base = base.parentFile
            hops++
        }
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: return emptyList()
        return dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
}
