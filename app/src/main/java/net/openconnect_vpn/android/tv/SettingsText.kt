package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.PathQualityDetector
import net.openconnect_vpn.android.failover.SlowLinkSettings

/**
 * 仕様書 §4-A「画面に出す導出文」。
 *
 * 既存の「現在の設定では、最短 X秒・最長 Y秒でダウンを検知します」と同じ作法で、
 * **設定値から結果の文を組み立てる。** 文面に定数を二重に書かない。
 *
 * **「待機中は切り替わりません」は必ず出す。** 2026-10-09 の事故
 * （`docs/MANUAL-TEST.md` 節 10-5-1）がこの一文で説明される項目であり、
 * 利用者が受信の下限を 0 にしたときは**行を消すのではなく警告に差し替える。**
 *
 * 純関数。Android・時計・I/O のどれにも触れない。
 */
object SettingsText {

    /**
     * @param windowSec 判定の窓の長さ（秒）。**既定は [PathQualityDetector.WINDOW_SEC]
     * を読む。** 利用者が変える項目ではない（そちらの KDoc）。引数にしてあるのは、
     * 「文面が窓の定数から導かれている」ことをテストで確かめられるようにするため
     * （既定値のまま `60` を直書きした実装と、定数を読む実装は、既定値では区別できない）。
     * 呼び出し側（設定画面）は渡さない。
     */
    fun pathQualitySummary(
        s: SlowLinkSettings,
        windowSec: Int = PathQualityDetector.WINDOW_SEC,
    ): String {
        val head = "いまの設定では、往復が ${s.degradedRttMs} ms を超え、" +
            "かつばらつきが ${s.degradedJitterMs} ms を超える状態が $windowSec 秒続いたときに" +
            "切り替えます。"
        val floor = if (s.rxFloorKbps > 0) {
            "受信が ${s.rxFloorKbps} kbps を下回っているあいだは判定しません" +
                "（待機中は切り替わりません）。"
        } else {
            "受信の下限が 0 なので、何も再生していないときでも判定します" +
                "（待機中でも切り替わることがあります）。"
        }
        val rearm = if (s.rearmAfterLapMin > 0) {
            "一周したあと ${s.rearmAfterLapMin} 分で再開します。" +
                "再開しても、条件が揃わなければ切り替わりません。"
        } else {
            "一周したあとは再開しません。"
        }
        return "$head\n$floor\n$rearm"
    }
}
