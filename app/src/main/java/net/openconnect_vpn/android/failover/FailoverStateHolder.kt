package net.openconnect_vpn.android.failover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * それ以外（UI を含むどのコードも）は [state] / [activeGroup] を読むだけにすること。
 *
 * サービスと UI は同一プロセスで動く（`AndroidManifest.xml` のどこにも
 * `android:process` が無いことを確認済み）ため、IPC・シリアライズ・権限面の
 * 考慮は不要で、プロセス内の [MutableStateFlow] で足りる。
 *
 * 裁定65（指摘7・Low）: 書き込み制限は完全ではないが、レビューが具体的に挙げた
 * 抜け穴のうち1つは実際に塞がっている。`.asStateFlow()` は
 * `kotlinx.coroutines.flow.ReadonlyStateFlow`（`MutableStateFlow` の実装クラスとは
 * 別の内部クラス）でラップして返すため、`(state as MutableStateFlow<...>)` は
 * コンパイルは通っても**実行時に `ClassCastException` で落ちる**
 * （`state` を素の `mutableState` のまま公開していた以前の実装では、この
 * キャストで書き込めてしまっていた）。
 *
 * 一方、`internal` が Kotlin では**モジュール**可視であり `:app` 内のどのファイルも
 * [publish]/[reset] を直接呼べてしまう点は残っている。レビューが挙げた
 * 「より良い」修正（書き込み口をサービスが所有するインスタンスにする）は、
 * Android の `Service` がフレームワークから public no-arg コンストラクタで
 * 生成される制約上コンストラクタを private にできず、型レベルでの完全な
 * 封じ込めが作れなかったため見送った。今日時点で規約を破っている呼び出しは
 * 無いことは確認済み（[FailoverService] 以外に publish/reset の呼び出しは無い）。
 */
object FailoverStateHolder {

    private val mutableState = MutableStateFlow<FailoverState>(FailoverState.Idle)
    private val mutableActiveGroup = MutableStateFlow<FailoverGroup?>(null)

    /** UI はここだけを読む。真実の所有者は [FailoverController]、ここは写し。 */
    val state: StateFlow<FailoverState> = mutableState.asStateFlow()

    /**
     * 裁定65（指摘8・Low）: [state] が指す候補（`candidateIndex`）の名前解決に
     * 使うべき、状態機械が実際に使ったグループのスナップショット
     * （[FailoverService] が保持する、`onCreate` 時点で固定された `groups` から
     * 取り出したもの）。UI 側の `GroupStore` から読んだグループ一覧は
     * 編集操作のたびに再読込されうるため世代がずれることがあり、ずれたまま
     * `candidateIndex` を UI 側のグループへ当てはめると「別の候補が接続済み」と
     * 誤表示しうる。対象グループが無ければ null（`Idle` など）。
     */
    val activeGroup: StateFlow<FailoverGroup?> = mutableActiveGroup.asStateFlow()

    /** [FailoverService] の dispatch ループからのみ呼ぶこと。 */
    internal fun publish(newState: FailoverState, activeGroup: FailoverGroup?) {
        mutableState.value = newState
        mutableActiveGroup.value = activeGroup
    }

    /**
     * [FailoverService.onDestroy] からのみ呼ぶこと。
     * サービスが止まったのに UI が古い接続状態を表示し続けないよう、
     * サービスの生死と投影内容を一致させる。
     */
    internal fun reset() {
        mutableState.value = FailoverState.Idle
        mutableActiveGroup.value = null
    }
}
