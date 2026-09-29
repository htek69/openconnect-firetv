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
     * 寿命は**切替が作った接続が生きているあいだ**である。消すのは
     * [clearSlowLinkSwitch] の1か所だけで、呼ぶのは次の2つの入口から:
     * [FailoverService.onStartCommand] の `ACTION_CONNECT_GROUP`（利用者の
     * 明示的な接続。仕様書 4-5 が一周の記録の解除に使うのと同じ「利用者の
     * 明示的な操作」）と、[onFailoverStateChanged]（状態が切替の前進を表さなく
     * なったとき。条件の全体はそちらの KDoc）。**時間による自動消去はしない。**
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
     * 最終再レビューの指摘4（FIX 1）: 理由の書き込み・消去を1か所にまとめ、
     * 両分岐を必ず対にする。
     *
     * **[FailoverService.dispatch] の毎回**（＝状態機械へイベントを1つ流すたび）
     * 呼ばれる。「`FailingOver` が新しく始まったとき」だけではない: 状態が同じ
     * `FailingOver` に留まっている数ティックのあいだも、値の変わらない再評価が
     * 繰り返される（[MutableStateFlow] は同じ値の代入を購読者に流さないので
     * 冪等ではあるが、**呼ばれる回数は1回ではない**）。
     *
     * 統合レビューの所見1（medium）: この「毎回呼ばれる」という事実が、以前の
     * この KDoc（「新しく始まるたび」）と食い違っていたために欠陥を1つ許していた。
     * 利用者の明示的な接続が [clearSlowLinkSwitch] で消した理由が、**同じ
     * dispatch の中で**この関数によって書き戻されていたのである
     * （`FailoverController.stopBeforeStarting` の `FailingOver` 分岐は裁定26 の
     * ため同じ状態をそのまま返すので、`bySlowLink = true` が残っていた）。
     * 直したのは状態機械の側で、いまはその分岐が `bySlowLink = false` に落とす
     * ——よって**ここが毎回呼ばれても**、書き戻しは起きない。この関数を
     * 「新しく始まったときだけ」に絞る作りには**していない**（呼ばれた回数を
     * 数える状態をこの投影に持たせないため）。
     *
     * `bySlowLink == true` なら [publishSlowLinkSwitch] で記録するが、
     * それ以外（**死活起因の切替**）では [clearSlowLinkSwitch] で必ず消す。
     * 以前はこの else 側が無く、速度低下による切替のあとに死活起因の切替が
     * 起きても前回の理由が残ったまま——「直前の切替: 速度低下で切替」という
     * 表示が、実際には死活切替であるにもかかわらず居座る誤表示になっていた。
     * 理由が分からない自動切替より、**間違った理由が表示される自動切替の方が
     * 悪い**（仕様書 5 の「理由の分からない自動切替を作らない」の趣旨に反する）。
     *
     * ## 条件は「切替が前進したか」であり、状態の種類の列挙ではない
     *
     * 統合レビューの所見1 の追加審議: FIX 1 で `bySlowLink == true` の意味は
     * **「この切替はこれから次候補へ前進する」**に定まった（保留に横取りされた
     * 時点で `FailoverController.stopBeforeStarting` が false に落とす）。
     * であれば、その `FailingOver` を**前進せずに**抜けた先でも理由は消えるべき
     * である。前進せずに抜ける経路は1つではなく、少なくとも5つある:
     *
     * 1. `UserDisconnect`（`ACTION_DISCONNECT`、および裁定44 の消灯）→ `Idle`
     * 2. `advanceAfterFailingOver` でグループ自体が消えていた → `Idle`
     *    （裁定72-fix(F1)。3秒の窓の中でグループが削除された）
     * 3. `startCandidateFrom` が起動できる候補を見つけられない → `Exhausted`
     *    （窓の中でメンバーが削除された・資格情報が消された・S1 で除外された）
     * 4. `startCandidateFrom` が `NeedsUserConsent` を受けた → `Idle`
     *    （VPN 許可が取り消されていた）
     * 5. 残りの候補がすべて `ConnectResult.Failed` → `Exhausted`
     *
     * これを経路ごとの特例で消していくと、経路が増えるたびに同じ欠陥が戻る。
     * そこで**状態の側で**言い切る: 切替が前進した先は `Connecting` だけであり
     * （`FailingOver` から出る遷移は `advanceAfterFailingOver` の
     * `startCandidateFrom` を通る以外に無い）、`Connecting` から先の
     * `Verifying` / `Healthy` はその接続の続きである。上の5経路はすべて
     * `Idle` か `Exhausted` に着地する。よって:
     *
     * - `Connecting` / `Verifying` / `Healthy`: **触らない。** ここが理由の
     *   表示される寿命そのものである（切替が作った接続が生きているあいだ）。
     * - `Idle` / `Exhausted`: **消す。** 切替は前進しなかった、あるいは
     *   その接続はもう無い。説明すべき接続が画面に無いのに理由だけが残るのは
     *   裁定R19 が禁じた「間違った理由」に当たる。
     *
     * これは裁定R19 の「消去点は1つ」に反しない。あの制約は**書き込みと消去を
     * コードベースに散らさない**ことであり、消す条件をこの1か所で正しく言うのは
     * その趣旨そのものである（新しい消去点は作っていない。時計も使っていない
     * ——見ているのは状態の種類だけである）。
     *
     * 副作用として、**完了した**切替の理由も接続が終われば消える（例: 速度切替の
     * あと利用者が手で切断した、または消灯した）。理由が正しかった場面で寿命が
     * 短くなる向きだが、この向きを選ぶ: 切断後に残った理由は、次に点灯して
     * `AutoConnectGroup` が**先頭の候補から**繋ぎ直したあとの行にも出てしまい、
     * 「今のセッションでは起きていない切替」を説明することになる。
     * 「理由が無い」より「間違った理由」の方が悪い、という裁定R19 の原則に従って
     * 安全側（消す側）に倒す。
     *
     * `when` を網羅（`else` 無し）にしてあるのは、[FailoverState] にケースが
     * 増えたときに**この判断をやり直させる**ためである（`firstLoginRecheckKey`
     * や `withCandidateIndex` と同じ作法）。
     */
    internal fun onFailoverStateChanged(newState: FailoverState) {
        when (newState) {
            is FailoverState.FailingOver ->
                if (newState.bySlowLink) {
                    publishSlowLinkSwitch(newState.groupId)
                } else {
                    clearSlowLinkSwitch()
                }

            // 切替が前進して作った接続。理由が意味を持つのはこのあいだだけ。
            is FailoverState.Connecting,
            is FailoverState.Verifying,
            is FailoverState.Healthy -> Unit

            // 切替は前進しなかった（または、その接続はもう無い）。
            FailoverState.Idle,
            is FailoverState.Exhausted -> clearSlowLinkSwitch()
        }
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
