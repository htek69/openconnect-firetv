package net.openconnect_vpn.android.failover

import java.util.concurrent.atomic.AtomicInteger

/**
 * 裁定93: 「いま人が認証ダイアログに答えられる状態か」を数えるだけの純粋な計数器。
 *
 * 判定の実体は `OpenVpnService.mActivityConnections > 0`
 * （＝`UserDialog` を描画できる Activity が bind されている）である。しかし
 * `mActivityConnections` は private で公開アクセサが無く、**既存 Java は変更しない**
 * という制約があるため、そこを直接読むことはできない。裁定92 でダイアログの
 * 描画先になったのは `TvMainActivity` だけであり、その Activity は我々の Kotlin
 * なので、同じ出来事（bind して `startActiveDialog` を呼べる状態にある／ない）を
 * こちら側で数える。裁定92 の修正により、この値は「TV 画面が前面にある」と一致する。
 *
 * 既知の抜け穴（報告書にも記載）: 旧 UI（`MainActivity` ほか）が前面にある間も
 * `mActivityConnections > 0` になりダイアログは出せるが、旧 UI は既存 Java なので
 * ここを叩けず、この計数器は 0 のままになる。その場合の振る舞いは裁定93 の修正
 * 前と同じ（無人と見なして10秒で除外）であり、回帰ではない。
 */
class DialogHostTracker {

    /**
     * Activity のライフサイクル（メインスレッド）から書き、`FailoverService` の
     * dispatch（Ruling 16 によりこちらもメインスレッドに固定）から読む。
     * 実質単一スレッドだが、この型自体は呼び出し規律を前提にしたくないので
     * アトミックに数える。
     */
    private val count = AtomicInteger(0)

    /** ダイアログを描画できる Activity が bind された。 */
    fun onHostAttached() {
        count.incrementAndGet()
    }

    /**
     * ダイアログを描画できる Activity が離れた。
     *
     * 0 を下回らせない。負に落ちると、その後 `onHostAttached` が来ても
     * [attached] が false のままになり、裁定93 の保護が黙って死ぬ。
     */
    fun onHostDetached() {
        count.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** 人が答えられる（＝描画先がある）か。 */
    val attached: Boolean get() = count.get() > 0
}

/**
 * 裁定93: [DialogHostTracker] のプロセス内唯一のインスタンス。
 *
 * `TvMainActivity`（書き手）と `FailoverService`（読み手）は同一プロセスで動く
 * （`AndroidManifest.xml` のどこにも `android:process` が無いことは裁定58 で
 * 確認済み）ので、プロセス内の共有で足りる。[FailoverStateHolder] と同じ理由で
 * object にしてある。
 *
 * 書くのは `TvMainActivity` の `onResume`/`onPause` だけ、読むのは
 * `FailoverService` が `FailoverController` に渡す供給関数だけにすること。
 */
object DialogHostRegistry {
    val tracker = DialogHostTracker()
}
