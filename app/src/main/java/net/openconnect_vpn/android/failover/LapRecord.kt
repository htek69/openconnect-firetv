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
     * 順位付け: 中央値の昇順 → 差が [jitterToleranceMs] 未満なら「有意に違わない」
     * と見なしてばらつきの小さい方 → それも同じなら [order] で先の方。
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

        var best = entries.first()
        for (e in entries.drop(1)) {
            if (betterThan(e, best, jitterToleranceMs)) best = e
        }
        return best.uuid
    }

    private class Entry(
        val uuid: String,
        /** [order] での位置。同値のときの決定的な順位付けに使う。 */
        val index: Int,
        val median: Long,
        val spread: Long,
    )

    /**
     * 仕様書 §2-A の順位付け: 中央値の昇順 → 差が [jitterToleranceMs] 未満なら
     * 「有意に違わない」と見なしてばらつきの小さい方 → それも同じなら [Entry.index]
     * で先の方。
     *
     * **新しい定数を導入しないこと。** 「有意に違うか」の尺度は
     * [SlowLinkSettings.degradedJitterMs] が既に与えている。
     */
    private fun betterThan(a: Entry, b: Entry, jitterToleranceMs: Int): Boolean {
        val diff = kotlin.math.abs(a.median - b.median)
        if (diff >= jitterToleranceMs) return a.median < b.median
        if (a.spread != b.spread) return a.spread < b.spread
        return a.index < b.index
    }

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
