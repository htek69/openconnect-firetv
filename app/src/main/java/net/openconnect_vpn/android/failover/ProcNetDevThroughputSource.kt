package net.openconnect_vpn.android.failover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `/proc/net/dev` から**いまの VPN のインターフェース**の累計バイト数を読む。
 *
 * コアの `VPNStats` ではなくカーネルのカウンタを読むのは、**既存 Java を変更せずに
 * 済む**ためである（サービスへの bind も要らない）。測るのは tun インターフェース上の
 * バイト数で、これは利用者がトンネル越しに実際にやり取りした量そのものである。
 *
 * 読めない場合（ファイルが無い・権限が無い・名前が決まらない・その名前の行が
 * 無い・列が足りない）はすべて null になる。切断中は tun インターフェースそのものが存在しないので、
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
class ProcNetDevThroughputSource(
    /**
     * 測る対象のインターフェース名を**参照のたびに**返す供給関数。null なら測らない。
     *
     * **既定値を置かないこと。** 以前は `iface: String = "tun0"` という既定があり、
     * 呼び出し側がそれに任せていたために、再接続で番号が進んだ実機では死んだ
     * `tun0` を永久に測り続けていた（`docs/DECISIONS.md` 項目15）。既定を無くせば、
     * 「どれを測るか」を決めずに使うことがコンパイル時に不可能になる。
     *
     * 供給関数の形にしているのは、トンネルが張り替わるたびに名前が変わるからで、
     * 構築時に1度解決して保持すると同じ欠陥に戻る（裁定72 の `groupsProvider` と
     * 同じ理由——参照のたびに最新を読む）。
     */
    private val ifaceProvider: () -> String?,
) : ThroughputSource {
    override suspend fun read(): IfaceBytes? = withContext(Dispatchers.IO) {
        val iface = ifaceProvider() ?: return@withContext null
        runCatching { parseProcNetDev(File("/proc/net/dev").readText(), iface) }.getOrNull()
    }
}
