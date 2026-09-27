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
    private val mutableLastSlowLinkSwitchGroupId = MutableStateFlow<String?>(null)

    /** UI はここだけを読む。真実の所有者は [FailoverController]、ここは写し。 */
    val state: StateFlow<FailoverState> = mutableState.asStateFlow()

    /**
     * 裁定65（指摘8・Low）: [state] が指す候補（`candidateIndex`）の名前解決に
     * 使うべき、状態機械が実際に使ったグループ（[FailoverService] が保持する
     * `groups` から取り出したもの）。裁定72-fix(F4): `groups` はもう
     * `onCreate` 時点で固定ではなく、`FailoverService.reloadGroupsAndProbeTarget`
     * が SharedPreferences の変更を検知するたびに読み直す。UI 側の
     * `GroupStore` から読んだグループ一覧は編集操作のたびに再読込されうるため、
     * 両者は別々のタイミングで更新される2つのスナップショットであり、世代が
     * ずれることがある。ずれたまま `candidateIndex` を UI 側のグループへ
     * 当てはめると「別の候補が接続済み」と誤表示しうる。対象グループが無ければ
     * null（`Idle` など）。
     */
    val activeGroup: StateFlow<FailoverGroup?> = mutableActiveGroup.asStateFlow()

    /**
     * 裁定R19（裁定R18 を覆す）: 最後に**スループット低下が理由で**切り替えた
     * グループの ID。まだ一度も起きていなければ null。
     *
     * 仕様書 5 は「切り替えた理由を表示する」ことを求め、その目的を
     * 「理由の分からない自動切替は最も嫌われる挙動である」と述べている。
     * 理由そのものは [FailoverState.FailingOver.bySlowLink] にも載っているが、
     * その状態は切断完了の確認まで（実測1〜3秒）しか続かない。この機能が働くのは
     * 利用者が**別のアプリで動画を見ている**あいだなので、その数秒に画面を
     * 見ている確率はほぼ0で、仕様書 5 の目的は満たせない。
     *
     * そこで理由だけをここに残す。[FailoverState] にフィールドを足すのでは
     * ないので、状態機械（真実）の形は変わらない——ここは元から
     * [FailoverService] が持つ投影であり、状態と同じ場所に置くのが最も安い。
     *
     * 寿命は「次に利用者が明示的に接続するまで」。理由を消すのは
     * [clearSlowLinkSwitch] の1か所だけで、[FailoverService.onStartCommand] の
     * `ACTION_CONNECT_GROUP` から呼ぶ（仕様書 4-5 が一周の記録の解除に使う
     * のと同じ「利用者の明示的な操作」であり、時間による自動消去はしない）。
     */
    val lastSlowLinkSwitchGroupId: StateFlow<String?> = mutableLastSlowLinkSwitchGroupId.asStateFlow()

    /** [FailoverService] の dispatch ループからのみ呼ぶこと。 */
    internal fun publish(newState: FailoverState, activeGroup: FailoverGroup?) {
        mutableState.value = newState
        mutableActiveGroup.value = activeGroup
    }

    /**
     * 裁定R19: スループット低下による切替が始まったことを記録する。
     * [FailoverService] の dispatch ループからのみ呼ぶこと。
     *
     * 同じ切替のあいだ（`FailingOver` の数ティック）繰り返し呼ばれるが、
     * [MutableStateFlow] は同じ値の代入では購読者に流さないので冪等である。
     */
    internal fun publishSlowLinkSwitch(groupId: String) {
        mutableLastSlowLinkSwitchGroupId.value = groupId
    }

    /**
     * 裁定R19: 記録した理由を消す。利用者が明示的に接続をやり直したときに
     * [FailoverService.onStartCommand] から呼ぶ。新しい指示が来た時点で、
     * 前回なぜ切り替わったかを表示し続ける意味は無い。
     */
    internal fun clearSlowLinkSwitch() {
        mutableLastSlowLinkSwitchGroupId.value = null
    }

    /**
     * [FailoverService.onDestroy] からのみ呼ぶこと。
     * サービスが止まったのに UI が古い接続状態を表示し続けないよう、
     * サービスの生死と投影内容を一致させる。
     */
    internal fun reset() {
        mutableState.value = FailoverState.Idle
        mutableActiveGroup.value = null
        // 裁定R19: 切替理由も投影の一部なので、サービスの生死に合わせて消す。
        mutableLastSlowLinkSwitchGroupId.value = null
    }
}
