package net.openconnect_vpn.android.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 仕様書 §2-A: 一周の終わりに、応答時間が最良だった候補へ戻す。
 *
 * **なぜ応答時間で比較できるのか。** 応答時間は需要に依存しないので候補をまたいで
 * 比較できる。受信速度は `min(容量, 需要)` なので、候補A を 4 Mbps の動画視聴中に
 * 測り候補E を音声のみで測れば、比較しているのは需要であって候補の良し悪しでは
 * ない。**窓を長くしてもこの交絡は消えない。**
 */
class LapRecordTest {

    private val order = listOf("a", "b", "c")

    @Test
    fun `記録が無ければ最良は null`() {
        val r = LapRecord()
        assertNull(r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `サンプルが minSamples 未満の候補は比較から外す`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 20L, 30L))  // 3 < 5
        assertNull(r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `中央値が小さい候補を選ぶ`() {
        val r = LapRecord()
        r.record("a", listOf(400L, 400L, 400L, 400L, 400L))
        r.record("b", listOf(100L, 100L, 100L, 100L, 100L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `中央値の差が許容未満ならばらつきの小さい方を選ぶ`() {
        val r = LapRecord()
        // 中央値 200 と 250（差 50 < 150）。ばらつきは a=400、b=20。
        r.record("a", listOf(10L, 200L, 200L, 200L, 410L))
        r.record("b", listOf(245L, 250L, 250L, 250L, 265L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `中央値もばらつきも同じならグループの順序で先の方を選ぶ`() {
        val r = LapRecord()
        r.record("c", listOf(100L, 100L, 100L, 100L, 100L))
        r.record("a", listOf(100L, 100L, 100L, 100L, 100L))
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `グループに居ない候補は比較から外す`() {
        val r = LapRecord()
        r.record("zzz", listOf(10L, 10L, 10L, 10L, 10L))
        r.record("b", listOf(500L, 500L, 500L, 500L, 500L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `同じ候補を複数回記録したらサンプルは足し合わせる`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 10L, 10L))
        r.record("a", listOf(10L, 10L))
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    @Test
    fun `切替の回数を数える`() {
        val r = LapRecord()
        assertEquals(0, r.switches())
        r.noteSwitch()
        r.noteSwitch()
        assertEquals(2, r.switches())
    }

    @Test
    fun `止まった時刻を覚える`() {
        val r = LapRecord()
        assertNull(r.stoppedAtMs())
        r.noteStopped(12_345L)
        assertEquals(12_345L, r.stoppedAtMs())
    }

    @Test
    fun `clear で全部消える`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 10L, 10L, 10L, 10L))
        r.noteSwitch()
        r.noteStopped(1L)
        r.clear()
        assertEquals(0, r.switches())
        assertNull(r.stoppedAtMs())
        assertNull(r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    // ---- 自己監査で追加したケース（ブリーフの10件は変更していない） ----

    /** 順位付けの第1段（中央値）が第2段（ばらつき）より先に効くこと。ばらつき先行の誤実装を捕まえる。 */
    @Test
    fun `差が許容以上なら、ばらつきより中央値を優先する`() {
        val r = LapRecord()
        r.record("a", listOf(100L, 100L, 100L, 100L, 400L))  // 中央値 100、ばらつき 300
        r.record("b", listOf(400L, 400L, 400L, 400L, 400L))  // 中央値 400、ばらつき 0
        // 差 300 >= 150 なので中央値で a。ばらつきで決めると b になる。
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /** 「差 >= 許容 なら中央値」の境界。差がちょうど許容値のとき中央値で決める。 */
    @Test
    fun `中央値の差がちょうど許容値なら中央値で決める`() {
        val r = LapRecord()
        r.record("a", listOf(100L, 100L, 100L, 100L, 400L))  // 中央値 100、ばらつき 300
        r.record("b", listOf(250L, 250L, 250L, 250L, 250L))  // 中央値 250、ばらつき 0
        // 差 150 == 許容 150。基準（最小中央値 100）からちょうど許容値なので同等に入らず、a が勝つ。
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /** 第2段（ばらつき）が同じなら、中央値は見ずに順序で決める（許容未満の中央値差は無視）。 */
    @Test
    fun `中央値の差が許容未満でばらつきも同じなら、中央値が小さくても順序の先の方`() {
        val r = LapRecord()
        r.record("a", listOf(200L, 200L, 200L, 200L, 200L))
        r.record("b", listOf(100L, 100L, 100L, 100L, 100L))
        // 差 100 < 150 なので中央値は比べない。ばらつき同じ → 順序で a。
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /** minSamples の境界: ちょうど閾値の候補は含める。 */
    @Test
    fun `サンプルがちょうど minSamples 個の候補は比較に入る`() {
        val r = LapRecord()
        r.record("a", listOf(500L, 500L, 500L, 500L, 500L))
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /** minSamples の境界: 1つ少ない候補は外す（閾値を1ずらす誤実装を捕まえる）。 */
    @Test
    fun `サンプルが minSamples より1つ少ない候補は比較から外す`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 10L, 10L, 10L))  // 4 < 5。含まれると a が勝つ
        r.record("b", listOf(500L, 500L, 500L, 500L, 500L))
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /** minSamples が5に固定されていないこと（全ケースが5なので、固定の誤実装は既存ケースでは見えない）。 */
    @Test
    fun `minSamples は引数の値を使う`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 10L))
        r.record("b", listOf(500L, 500L, 500L))
        // minSamples = 2 なら a も b も対象で、中央値の a が勝つ。5 固定なら両方外れて null。
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 2))
    }

    /** jitterToleranceMs が150に固定されていないこと。かつ境界（差 == 許容）の確認。 */
    @Test
    fun `許容値は引数の値を使う`() {
        val r = LapRecord()
        r.record("a", listOf(10L, 200L, 200L, 200L, 410L))  // 中央値 200、ばらつき 400
        r.record("b", listOf(245L, 250L, 250L, 250L, 265L))  // 中央値 250、ばらつき 20
        // 差 50 == 許容 50 → 中央値で a。150 固定なら差 50 は未満扱いでばらつきの b になる。
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 50, minSamples = 5))
    }

    /** グループの順序は引数の order を使う。英字順や挿入順の誤実装を捕まえる。 */
    @Test
    fun `グループの順序は引数どおりに使う（英字順にしない）`() {
        val r = LapRecord()
        r.record("a", listOf(100L, 100L, 100L, 100L, 100L))
        r.record("c", listOf(100L, 100L, 100L, 100L, 100L))
        // 中央値もばらつきも同じ。order で先の c。英字順なら a になる。
        assertEquals("c", r.bestUuid(listOf("c", "a"), jitterToleranceMs = 150, minSamples = 5))
    }

    // ---- 修正1（推移性）: 2段の規則。仕様 §2-A の「順位付け」に従う ----

    /**
     * 循環の例: a(中央値300, ばらつき0) / b(200, 10) / c(100, 20)、許容150。
     * 2段の規則では最小中央値は c の 100。その許容未満（差 < 150）は b（差100）と c（差0）。
     * ばらつきは b=10 < c=20 なので **b**。走査順に関係なく b でなければならない。
     */
    @Test
    fun `循環する例でも順序を並べ替えても結果は同じ（推移的）`() {
        val perms = listOf(
            listOf("a", "b", "c"),
            listOf("a", "c", "b"),
            listOf("b", "a", "c"),
            listOf("b", "c", "a"),
            listOf("c", "a", "b"),
            listOf("c", "b", "a"),
        )
        for (p in perms) {
            val r = LapRecord()
            r.record("a", listOf(300L, 300L, 300L, 300L, 300L))  // 中央値 300、ばらつき 0
            r.record("b", listOf(195L, 200L, 200L, 200L, 205L))  // 中央値 200、ばらつき 10
            r.record("c", listOf(90L, 100L, 100L, 100L, 110L))   // 中央値 100、ばらつき 20
            assertEquals("order=$p", "b", r.bestUuid(p, jitterToleranceMs = 150, minSamples = 5))
        }
    }

    /**
     * 基準の境界（厳密）: 最小中央値 100 から**ちょうど許容値 150** 離れた候補は同等にならない。
     * ばらつき0 で勝つ可能性があっても、同等集合に入らないので a（ばらつき300、基準そのもの）が勝つ。
     */
    @Test
    fun `基準からちょうど許容値離れた候補は同等とみなさない`() {
        val r = LapRecord()
        r.record("a", listOf(100L, 100L, 100L, 100L, 400L))  // 中央値 100、ばらつき 300
        r.record("b", listOf(250L, 250L, 250L, 250L, 250L))  // 中央値 250（差 150 == 許容）、ばらつき 0
        assertEquals("a", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /**
     * 基準の1つ内側（対照）: 最小中央値 100 から許容値の1つ手前（差 149）の候補は同等になり、
     * ばらつきで勝つ。上の境界ケースとの対で、「同等」の判定が ちょうど の側だけ狂う誤実装を捕まえる。
     */
    @Test
    fun `基準から許容値の1つ手前の候補は同等とみなし、ばらつきで勝つ`() {
        val r = LapRecord()
        r.record("a", listOf(100L, 100L, 100L, 100L, 400L))  // 中央値 100、ばらつき 300
        r.record("b", listOf(249L, 249L, 249L, 249L, 249L))  // 中央値 249（差 149 < 150）、ばらつき 0
        assertEquals("b", r.bestUuid(order, jitterToleranceMs = 150, minSamples = 5))
    }

    /**
     * 基準（最小中央値）は比較対象の中だけで決める。グループ外の候補や、サンプル不足の候補の
     * 低い中央値を基準に数えると、同等集合がずれる。
     */
    @Test
    fun `基準の最小値は比較対象の候補だけから取る`() {
        val r = LapRecord()
        r.record("a", listOf(400L, 400L, 400L, 400L, 400L))  // 中央値 400、ばらつき 0
        r.record("b", listOf(200L, 200L, 200L, 200L, 200L))  // 中央値 200、ばらつき 0
        r.record("c", listOf(90L, 100L, 100L, 100L, 110L))   // 中央値 100、ばらつき 20
        r.record("zzz", listOf(10L, 10L, 10L, 10L, 10L))     // グループ外。基準に数えると基準が 10 になる
        r.record("d", listOf(10L, 10L, 10L, 10L))            // グループ内だが 4 < 5 で不足。基準に数えると同じく誤る
        // 基準は a/b/c の最小 100（c）。同等は b（差100）と c（差0）。a は差300で除外。
        // b のばらつき 0 < c の 20 なので b。基準を 10 にすると同等は c だけになり c が返る。
        assertEquals("b", r.bestUuid(listOf("a", "b", "c", "d"), jitterToleranceMs = 150, minSamples = 5))
    }
}
