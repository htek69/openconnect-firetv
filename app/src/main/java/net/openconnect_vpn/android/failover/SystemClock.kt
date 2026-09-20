package net.openconnect_vpn.android.failover

/** 単調増加する時計。端末時刻の変更に影響されない elapsedRealtime を使う。 */
class SystemClock : Clock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}
