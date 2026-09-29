package net.openconnect_vpn.android.failover

/** 遅さの判定に使う閾値。既定値の根拠は仕様書 2 の実測。 */
data class SlowLinkThresholds(
    /** 速度を均す窓の長さ。 */
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
 * 判定は仕様書 4-2 のとおり、**窓（[SlowLinkThresholds.windowSec]）で均した**速度が
 * 受信は閾値未満・送信は閾値超であること。窓の中のサンプルを保持し、いちばん古い
 * サンプルといちばん新しいサンプルのバイト数の差を、その2点の経過時間で割った
 * 平均速度を条件に当てる。
 *
 * 裁定R20: 以前は5秒ごとの小区間すべてが閾値を下回ることを要求し、一度でも
 * 上振れすると数え直していた。仕様書 §4-2 の「均した値」はそういう意味ではなく、
 * その読みでは §2-2 で実測したようにばらつく回線——まさに本機能が対象にしている
 * 候補——で永久に成立しない恐れがあった。窓の平均を採ると、動画プレイヤーが
 * 区間ごとにまとめて取りに来る（結果として小区間が上下する）遅い回線でも
 * 判定できる。累計カウンタの差を取るので、途中のティックが飛んでも平均は
 * 正しい（欠けた区間のバイト数もカウンタに含まれている）。
 *
 * 判定が真になる条件は3つある。
 *
 * 1. **窓が埋まっていること。** 最古と最新の差が [SlowLinkThresholds.windowSec]
 *    未満のあいだは、まだ窓ぶんの証拠が無いので常に偽である（部分的な窓から
 *    判定を出さない）。
 * 2. **証拠が新しいこと。** 最古と最新の差が窓の [STALE_SPAN_FACTOR] 倍を超えたら
 *    偽に戻す。採取が止まったあと（`/proc/net/dev` が読めなくなった等）に
 *    古い判定が生き残り、何分も前の証拠で切り替わるのを防ぐためである。
 * 3. **平均が両方の条件を満たすこと。**
 *
 * 累計値が前回より小さくなった場合（再接続でインターフェースが作り直された等）は
 * 差分に意味が無いので窓を捨て、そのサンプルを新しい基準にする。
 */
class SlowLinkDetector(private val thresholds: SlowLinkThresholds) {

    private class Sample(val atMs: Long, val bytes: IfaceBytes)

    /**
     * 窓の中のサンプル。時刻の昇順。[onSample] が窓から出たものを落とすので
     * 大きさは「窓の長さ ÷ ティック間隔」程度（既定の 60 秒 / 5 秒なら 13 個）に
     * 収まる。窓の外側の1つだけは残す（[onSample] の説明を参照）。
     */
    private val samples = ArrayDeque<Sample>()

    private val windowMs: Long get() = thresholds.windowSec * 1000L

    fun reset() {
        samples.clear()
    }

    /**
     * 採取した累計バイト数を1つ足す。[atMs] は巻き戻らない時計の値であること
     * （[FailoverService] の `clock` の KDoc 参照）。
     *
     * 窓から出たサンプルは落とすが、**窓の始点をまたぐ1つだけは残す。**
     * これを落とすと、ティックの間隔が窓の長さを割り切らない限り最古と最新の差が
     * 窓の長さに届かず、判定が永久に成立しなくなる（実機のティックは 5000ms
     * ぴったりでは来ない）。残したぶん平均する区間は窓より1ティックぶん長くなる
     * 可能性があるが、それは「窓で均した値」の範囲であり、条件2（古すぎる窓を
     * 捨てる）が上限を押さえる。
     */
    fun onSample(atMs: Long, bytes: IfaceBytes) {
        val newest = samples.lastOrNull()
        if (newest != null &&
            (bytes.rxBytes < newest.bytes.rxBytes || bytes.txBytes < newest.bytes.txBytes)
        ) {
            // カウンタが巻き戻った。差分が負になる（＝負の速度や巨大な速度を
            // 作る）ので、これまでの窓を捨てて基準を取り直す。
            samples.clear()
            samples.addLast(Sample(atMs, bytes))
            return
        }
        if (newest != null && atMs <= newest.atMs) {
            // 時刻が進んでいない（同じ時刻の重複サンプル等）。窓に足しても
            // 情報が増えず、経過時間 0 での割り算の種になるだけなので捨てる。
            return
        }
        samples.addLast(Sample(atMs, bytes))

        val cutoffMs = atMs - windowMs
        while (samples.size > 1 && samples[1].atMs <= cutoffMs) {
            samples.removeFirst()
        }
    }

    fun isSlow(): Boolean {
        val oldest = samples.firstOrNull() ?: return false
        val newest = samples.lastOrNull() ?: return false
        val spanMs = newest.atMs - oldest.atMs
        // 窓が埋まっていない（0 の場合を含む。ここで割り算の分母を保証する）。
        if (spanMs <= 0 || spanMs < windowMs) return false
        // 採取が止まっているあいだの古い判定を生き残らせない。
        if (spanMs > windowMs * STALE_SPAN_FACTOR) return false

        val dRx = newest.bytes.rxBytes - oldest.bytes.rxBytes
        val dTx = newest.bytes.txBytes - oldest.bytes.txBytes
        // onSample が巻き戻りを弾くので窓の中では起こらないが、負の速度を
        // 作らないための保険として残す。
        if (dRx < 0 || dTx < 0) return false

        // kbps は「バイト数 * 8 / 経過ミリ秒」で出る。**ここに `* 1000 / 1000` を
        // 書き足さないこと。** 以前は `dRx * 8 * 1000 / spanMs / 1000` と書いていたが、
        // 非負の整数では両者は**厳密に等しい**（a = q*b + r, 0 <= r < b とおくと
        // floor(a*1000/b) = 1000q + floor(1000r/b) で 0 <= floor(1000r/b) <= 999 なので、
        // さらに 1000 で割ると繰り上がり無しで q に戻る）。等しいのに `* 1000` は
        // 中間結果を1000倍するので、桁あふれまでの余裕を3桁ぶん捨てていた
        // （デルタが Long.MAX_VALUE / 8000 ≒ 1.15PB を超えると積があふれ、
        // 受信・送信の速度が無意味な値になって誤検知・取りこぼしの両方を作る）。
        // この書き換えは式を減らしただけで判定は変えない——等価であることは
        // `SlowLinkDetectorPropertyTest` の P9 が、旧式と新式を
        // あふれない域の全入力で突き合わせて押さえている。
        val rxKbps = dRx * 8 / spanMs
        val txKbps = dTx * 8 / spanMs
        return rxKbps < thresholds.slowRxKbps && txKbps > thresholds.demandTxKbps
    }

    private companion object {
        /**
         * 窓が「新しい証拠」と言える上限（窓の長さの何倍まで許すか）。
         *
         * 実機のティックは画面点灯中 5 秒・消灯中 30 秒で、間隔がぶれたり
         * 数回飛んだりしても最古と最新の差は窓（60 秒）の2倍には届かない。
         * 逆に採取そのものが止まれば差はいくらでも広がるので、そこを境にする。
         */
        const val STALE_SPAN_FACTOR = 2
    }
}
