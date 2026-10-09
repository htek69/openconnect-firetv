package net.openconnect_vpn.android.failover

/**
 * 仕様書 §2-A / §2-B: 一周のあいだの記録。
 *
 * **メモリ上だけで、永続化しない。** 候補ごとの測定時刻はずれるので
 * （候補A は T+0、候補F は T+6分）、**比較は一周の中だけで行い、一周が
 * 終わったら捨てる。** 横断的に「この候補はいつも良い」を学習するのは
 * 別の話（仕様書 §5 の将来の入口）。
 */
class LapRecord {

    private val rttByUuid = mutableMapOf<String, MutableList<Long>>()
    private var switchCount = 0
    private var stoppedAt: Long? = null

    /** [uuid] の候補について応答時間のサンプルを足す。 */
    fun record(uuid: String, rttMs: List<Long>) {
        if (rttMs.isEmpty()) return
        rttByUuid.getOrPut(uuid) { mutableListOf() }.addAll(rttMs)
    }

    /**
     * 最良の候補。**[order] に居て、サンプルが [minSamples] 以上ある候補だけ**を
     * 比較する。該当が無ければ null（呼び出し側はグループの先頭へ戻す）。
     *
     * 順位付け（仕様書 §2-A、2段。1対1の比較を並べない）:
     * 1. 比較対象の中で最小の中央値を基準とする。
     * 2. 中央値が基準から [jitterToleranceMs] 未満しか離れていない候補を「同等」と見なし、
     *    その中でばらつき（最大−最小）が最小のものを採る。
     * 3. それも同じなら [order] で先の方を採る。
     *
     * 基準は一つだけなので、結果は [order] の並べ方に依存しない（推移的）。
     *
     * **新しい定数を導入しない。** 「有意に違うか」の尺度は
     * [SlowLinkSettings.degradedJitterMs] が既に与えている（呼び出し側が渡す）。
     */
    fun bestUuid(order: List<String>, jitterToleranceMs: Int, minSamples: Int): String? {
        val entries = order.mapIndexedNotNull { index, uuid ->
            val samples = rttByUuid[uuid] ?: return@mapIndexedNotNull null
            if (samples.size < minSamples) return@mapIndexedNotNull null
            val o = ProbeOutcome(reachable = true, rttMs = samples)
            val median = o.medianRttMs ?: return@mapIndexedNotNull null
            val spread = o.spreadMs ?: return@mapIndexedNotNull null
            Entry(uuid, index, median, spread)
        }
        if (entries.isEmpty()) return null

        // 第1段: 基準は比較対象の最小の中央値。
        val anchor = entries.minOf { it.median }
        // 第2段: 基準から許容未満の候補が同等。その中でばらつき最小、同じなら順序で先。
        // 基準そのもの（差0）は、許容値が0以下でも同等に含めて空集合を避ける。
        return entries
            .filter { it.median == anchor || it.median - anchor < jitterToleranceMs }
            .minWith(compareBy<Entry>({ it.spread }, { it.index }))
            .uuid
    }

    private class Entry(
        val uuid: String,
        /** [order] での位置。同値のときの決定的な順位付けに使う。 */
        val index: Int,
        val median: Long,
        val spread: Long,
    )

    fun switches(): Int = switchCount

    fun noteSwitch() {
        switchCount++
    }

    fun stoppedAtMs(): Long? = stoppedAt

    fun noteStopped(atMs: Long) {
        stoppedAt = atMs
    }

    fun clear() {
        rttByUuid.clear()
        switchCount = 0
        stoppedAt = null
    }
}
