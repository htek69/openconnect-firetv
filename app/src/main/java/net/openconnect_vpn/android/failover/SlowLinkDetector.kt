package net.openconnect_vpn.android.failover

/** 遅さの判定に使う閾値。既定値の根拠は仕様書 2 の実測。 */
data class SlowLinkThresholds(
    /** 条件が連続していなければならない長さ。 */
    val windowSec: Int = 60,
    /** これを下回る受信速度を「遅い」とみなす。 */
    val slowRxKbps: Int = 1000,
    /** これを上回る送信速度を「使おうとしている」とみなす。 */
    val demandTxKbps: Int = 5,
)

/**
 * トンネルの累計バイト数の列から「遅い」を判定する。
 *
 * **時計を持たない。** 時刻は [onSample] の引数で受け取るので、Android にも
 * コルーチンにも依存せず JVM の単体テストで完全に固定できる。
 *
 * 判定は仕様書 4-2 のとおり、受信が閾値未満かつ送信が閾値超という状態が
 * [SlowLinkThresholds.windowSec] のあいだ連続したとき。条件が破れたらそこから数え直す。
 *
 * 累計値が前回より小さくなった場合（再接続でインターフェースが作り直された等）は
 * 差分に意味が無いので数え直す。
 */
class SlowLinkDetector(private val thresholds: SlowLinkThresholds) {

    private var lastAtMs: Long? = null
    private var lastBytes: IfaceBytes? = null
    private var slowSinceMs: Long? = null
    private var slowForMs: Long = 0

    fun reset() {
        lastAtMs = null
        lastBytes = null
        slowSinceMs = null
        slowForMs = 0
    }

    fun onSample(atMs: Long, bytes: IfaceBytes) {
        val prevAt = lastAtMs
        val prevBytes = lastBytes
        lastAtMs = atMs
        lastBytes = bytes
        if (prevAt == null || prevBytes == null) return

        val elapsedMs = atMs - prevAt
        if (elapsedMs <= 0) return

        val dRx = bytes.rxBytes - prevBytes.rxBytes
        val dTx = bytes.txBytes - prevBytes.txBytes
        if (dRx < 0 || dTx < 0) {
            slowSinceMs = null
            slowForMs = 0
            return
        }

        val rxKbps = (dRx * 8 * 1000 / elapsedMs / 1000).toInt()
        val txKbps = (dTx * 8 * 1000 / elapsedMs / 1000).toInt()

        if (rxKbps < thresholds.slowRxKbps && txKbps > thresholds.demandTxKbps) {
            if (slowSinceMs == null) slowSinceMs = prevAt
            slowForMs = atMs - (slowSinceMs ?: prevAt)
        } else {
            slowSinceMs = null
            slowForMs = 0
        }
    }

    fun isSlow(): Boolean = slowForMs >= thresholds.windowSec * 1000L
}
