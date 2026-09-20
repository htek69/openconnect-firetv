package net.openconnect_vpn.android.failover

/**
 * 全候補が枯渇したあとの再試行間隔（仕様書 7.2）。
 * 30秒から倍々に伸ばし、10分で打ち止めにする。
 */
object Backoff {

    private const val BASE_MS = 30_000L
    private const val MAX_MS = 600_000L

    fun delayMsForAttempt(attempt: Int): Long {
        if (attempt <= 0) return BASE_MS
        // シフト量を制限してオーバーフローを避ける
        val shift = attempt.coerceAtMost(30)
        val delay = BASE_MS shl shift
        return if (delay <= 0L || delay > MAX_MS) MAX_MS else delay
    }
}
