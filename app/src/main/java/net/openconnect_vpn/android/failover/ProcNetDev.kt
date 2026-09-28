package net.openconnect_vpn.android.failover

/** 1つのネットワークインターフェースの累計バイト数。 */
data class IfaceBytes(val rxBytes: Long, val txBytes: Long)

/**
 * 列の区切り（連続する空白）。[parseProcNetDev] は毎ティック呼ばれるので、
 * 行ごとに [Regex] を作り直さずここで1度だけ作る。挙動は同じで、
 * 正規表現の中身を変えていないことがこのファイルの差分で分かるようにしてある。
 */
private val COLUMN_SEPARATOR = Regex("\\s+")

/**
 * `/proc/net/dev` の内容から [iface] の行を探し、受信・送信の累計バイト数を返す。
 *
 * 書式は「`  tun0: <rx bytes> <rx packets> ... <tx bytes> <tx packets> ...`」で、
 * 名前に続くコロンの後に受信側8列・送信側8列が並ぶ。受信バイト数は1列目、
 * 送信バイト数は9列目である。
 *
 * 見つからない場合や列が足りない場合は null を返す。**例外は投げない**。
 * 端末やカーネルで書式が違いうるので、呼び出し側が「測れない」として扱えるようにする。
 */
fun parseProcNetDev(text: String, iface: String): IfaceBytes? {
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        val name = trimmed.substringBefore(':', missingDelimiterValue = "")
        if (name != iface) continue
        val cols = trimmed.substringAfter(':').trim().split(COLUMN_SEPARATOR)
        if (cols.size < 9) return null
        val rx = cols[0].toLongOrNull() ?: return null
        val tx = cols[8].toLongOrNull() ?: return null
        return IfaceBytes(rx, tx)
    }
    return null
}
