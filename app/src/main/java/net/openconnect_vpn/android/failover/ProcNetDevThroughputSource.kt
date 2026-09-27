package net.openconnect_vpn.android.failover

import java.io.File

/**
 * `/proc/net/dev` から [iface] の累計バイト数を読む。
 *
 * コアの `VPNStats` ではなくカーネルのカウンタを読むのは、**既存 Java を変更せずに
 * 済む**ためである（サービスへの bind も要らない）。測るのは tun インターフェース上の
 * バイト数で、これは利用者がトンネル越しに実際にやり取りした量そのものである。
 *
 * 読めない場合（ファイルが無い・権限が無い・`iface` の行が無い・列が足りない）は
 * すべて null になる。切断中は tun インターフェースそのものが存在しないので、
 * これは日常的に起きる正常な結果である。呼び出し側は null を「測れない」として
 * 扱い、判定器へ渡してはならない（「測れない」は「遅い」ではない）。
 */
class ProcNetDevThroughputSource(private val iface: String = "tun0") : ThroughputSource {
    override fun read(): IfaceBytes? =
        runCatching { parseProcNetDev(File("/proc/net/dev").readText(), iface) }.getOrNull()
}
