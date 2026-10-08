package net.openconnect_vpn.android.failover

/**
 * 候補の名前列から「いまの VPN のインターフェース名」を決める。決められなければ null。
 *
 * **なぜ固定名（`tun0`）ではだめなのか。** Android は VPN を張り直すたびに新しい
 * `tun` 番号を割り当て、**古いものは `DOWN` のまま `/proc/net/dev` に残る**。
 * 実機（Fire TV Stick 4K / Android 7.1.2）では再接続を重ねた結果
 * `tun0` / `tun1` / `tun3` が `DOWN`、`tun4` だけが `UP` という状態になり、
 * 固定名で測っていた以前の実装は**死んだ `tun0` を永久に測り続けていた**。
 * 差分が常に 0 なので送信側の条件（`SlowLinkThresholds.demandTxKbps`）で落ち、
 * 速度低下による切替が二度と起きなかった（`docs/DECISIONS.md` 項目15）。
 *
 * **曖昧なら測らない。** 名前が2つ以上見つかる状況（VPN が同時に複数生きている）
 * では、どちらのトンネルを見るべきか決められない。ここで片方を選ぶと「利用者が
 * 見ているのとは別のトンネル」を根拠に切り替えうるので null を返す。
 * **呼び出し側は null を「遅い」と解釈してはならない**——「測れない」は
 * 「遅い」ではない（`FailoverService.sampleThroughput` の約束）。切替が遅れる側
 * ＝安全側に倒す。
 *
 * **名前の形は決めつけない。** `tun` で始まるかどうかは OS の都合であり、
 * ここで絞ると将来の命名で壊れる。前後の空白だけ落として同一性を見る。
 */
fun selectVpnInterfaceName(candidates: List<String?>): String? =
    candidates
        .asSequence()
        .filterNotNull()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
        .singleOrNull()
