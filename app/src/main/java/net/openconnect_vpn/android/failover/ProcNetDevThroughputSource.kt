package net.openconnect_vpn.android.failover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * 裁定R21: 読み取りは [TcpHealthProbe] と同じ形で内部から `Dispatchers.IO` へ
 * 逃がす。呼び出し元のティックループはメインスレッドに固定されており
 * （[FailoverService] の `scope` の KDoc）、そこで同期ファイル読みを行うと
 * Compose TV の UI と同じスレッドを毎ティック止めてしまう。procfs の読み取りは
 * 普通はミリ秒未満だが、カーネル側のロックを取るうえ上限が無く、対象は
 * 2017年の Fire TV Stick である。
 */
class ProcNetDevThroughputSource(private val iface: String = "tun0") : ThroughputSource {
    override suspend fun read(): IfaceBytes? = withContext(Dispatchers.IO) {
        runCatching { parseProcNetDev(File("/proc/net/dev").readText(), iface) }.getOrNull()
    }
}
