package net.openconnect_vpn.android.failover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 裁定58: [FailoverController] が持つ状態機械の状態を、同一プロセス内の UI へ
 * 読み取り専用で射影する。
 *
 * Fire TV には通知シェードが無い（仕様書 §2.2 / §5.5 により `QSTileService` を
 * この fork から削除済み）。そのため [FailoverService] のフォアグラウンド通知は
 * 利用者から見えず、ホーム画面が接続状態を知る手段が他に無い。かといって
 * UI 側に状態の写しを持たせて自分で計算させると、真実の所在が2つになり
 * 食い違う（計画書が警告する「UI が真実を持つ」問題）。
 *
 * ここが唯一の妥協点になる: 状態機械そのもの（真実）は [FailoverController] が
 * 持ち続け、ここは [FailoverService.dispatch] の直後に流し込まれた値を
 * ただ公開するだけの投影先。書き込むのは [FailoverService] だけで、
 * それ以外（UI を含むどのコードも）は [state] を読むだけにすること。
 *
 * サービスと UI は同一プロセスで動く（`AndroidManifest.xml` のどこにも
 * `android:process` が無いことを確認済み）ため、IPC・シリアライズ・権限面の
 * 考慮は不要で、プロセス内の [MutableStateFlow] で足りる。
 */
object FailoverStateHolder {

    private val mutableState = MutableStateFlow<FailoverState>(FailoverState.Idle)

    /** UI はここだけを読む。真実の所有者は [FailoverController]、ここは写し。 */
    val state: StateFlow<FailoverState> = mutableState

    /** [FailoverService] の dispatch ループからのみ呼ぶこと。 */
    internal fun publish(newState: FailoverState) {
        mutableState.value = newState
    }

    /**
     * [FailoverService.onDestroy] からのみ呼ぶこと。
     * サービスが止まったのに UI が古い接続状態を表示し続けないよう、
     * サービスの生死と投影内容を一致させる。
     */
    internal fun reset() {
        mutableState.value = FailoverState.Idle
    }
}
