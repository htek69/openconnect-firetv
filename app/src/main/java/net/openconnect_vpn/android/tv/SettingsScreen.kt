package net.openconnect_vpn.android.tv

import android.text.InputType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.failover.FailoverService
import net.openconnect_vpn.android.failover.GroupStore
import net.openconnect_vpn.android.failover.ProbeSchedule
import net.openconnect_vpn.android.failover.ProbeTarget
import net.openconnect_vpn.android.failover.SlowLinkBounds
import net.openconnect_vpn.android.failover.SlowLinkSettings

/**
 * 設定画面。疎通確認の宛先・間隔・失敗閾値、VPN 許可の取得・切断、
 * そして既存のスマホ UI（切り分け用）への入口をまとめる。
 *
 * 裁定1（ブリーフからの訂正）: このプロジェクトの依存グラフには
 * `androidx.compose.material3`（`OutlinedTextField` を含む）が無く、`tv-material`
 * 経由でも入ってこない（Task 6 で判明済み）。自由入力が要る宛先ホストは
 * Task 6 の [TvFieldRow] をそのまま再利用する。3つ目のテキスト入力実装は作らない。
 *
 * 裁定2（裁定73）: プローブ間隔と失敗閾値は小さな整数であり、TV でこれをテキスト欄に
 * すると「30 を 60 にしたいだけ」でソフトキーボードを開いてグリッドを操作して
 * 閉じる羽目になる。D-pad の左右（ここでは明示的な－／＋の2枚の `Card`）で
 * 増減する行にし、現在値を常に表示する。値の範囲・刻み幅は [SettingsStepper] を
 * 参照。
 *
 * 裁定3: プローブ宛先はテキストが要る（ホスト名／IP とポート）。ホスト欄は
 * [TvFieldRow] を使う。ソフトキーボードの実機挙動は Task 6 の時点で未確認のまま
 * であり、ここで別実装を作って回避しない。
 *
 * ## プローブ間隔・失敗閾値の永続化について（裁定74）
 *
 * `FailoverConfig` はデータ上グループ単位（`FailoverGroup.config`）に持たせて
 * いるが、それを個別に編集させる UI は無く、この画面はプローブ間隔・失敗閾値を
 * 「アプリ全体で1つの設定」として見せている。ストアの形が UI の見せ方と食い違うと
 * 「設定画面が表示した値がどのグループにも効いていない」という嘘が生まれる
 * （実際、保存済みの全グループへ書き込む前の版はこの嘘を持っていた——設定変更後に
 * 新しいグループを作ると、そのグループだけコンパイル時既定値のまま取り残された）。
 *
 * そこでこの画面は [GroupStore.loadProbeSchedule] / [GroupStore.saveProbeSchedule]
 * が持つグローバルな1本の設定（[ProbeSchedule]）だけを読み書きし、グループには
 * 一切触れない。[GroupStore.loadGroups] 側がこの設定を読み込んだ**すべての**
 * グループの `config` に適用するため（新しく作られたグループも含む）、
 * `FailoverController` / `FailoverService` は変更不要で今まで通り
 * `FailoverGroup.config` だけを読めばよい。保存は `saveProbeSchedule` が書く
 * キーも Task 6.5（裁定48/72）の再読込対象に含めてあるので、既存のシグナリング
 * 経路のまま稼働中のサービスに反映される。
 */
@Composable
fun SettingsScreen(
    groupStore: GroupStore,
    onDone: () -> Unit,
    onOpenLegacyUi: () -> Unit,
) {
    val context = LocalContext.current

    val storedTarget = remember { groupStore.loadProbeTarget() }
    var host by remember { mutableStateOf(storedTarget.host) }
    var port by remember { mutableStateOf(storedTarget.port.toString()) }

    val storedSchedule = remember { groupStore.loadProbeSchedule() }
    var probeIntervalSec by remember {
        mutableStateOf(SettingsStepper.clampProbeInterval(storedSchedule.probeIntervalSec))
    }
    var failureThreshold by remember {
        mutableStateOf(SettingsStepper.clampFailureThreshold(storedSchedule.failureThreshold))
    }

    val storedSlowLink = remember { groupStore.loadSlowLinkSettings() }
    // 6項目を1つの [SlowLinkSettings] として持つ。以前は enabled と slowRxKbps を
    // 別々の状態に取り出し、保存時に2項目だけで組み直していたため、保存のたびに
    // 残りの項目（改訂で足した4項目）が既定値へ黙って戻っていた。1つの値の
    // `copy` で更新し、保存はその値をそのまま渡せば、項目が増えても書き落とせない。
    // 読込側（GroupStore.loadSlowLinkSettings）が数値5項目すべてを範囲へ収めて返すので
    // （裁定30）、ここで改めて収めない。
    var slowLink by remember { mutableStateOf(storedSlowLink) }

    var message by remember { mutableStateOf<String?>(null) }

    val requestConsent = rememberVpnConsentLauncher()

    // 裁定85（実機で発見）: この Column は fillMaxSize() でスクロールを持たず、
    // 1920x1080 実機では内容が画面の高さを超えた分がそのまま描画されない
    // ことが uiautomator dump で確認された（「設定を保存」「VPN の許可を
    // 取得する」「VPN を切断する」「詳細設定とログ（従来の画面）」「戻る」の
    // 5つの文字列が dump に一切現れず、D-pad で降りるとバウンディングボックス
    // [0,0][0,0] の位置だけにフォーカスが移った＝レイアウトされていない）。
    // 裁定82（GroupEditScreen）と同じ壊れ方だが、あちらと違いこの画面の
    // 内容はすべて固定長（可変長のリストを持たない）なので、LazyColumn +
    // weight(1f) の構成に変える理由が無い。単純に
    // Modifier.verticalScroll(rememberScrollState()) を外側の Column に
    // 与えるだけで足りる。Compose はフォーカス移動に追従してこの
    // ScrollState を自動でスクロールするため、D-pad で下へ降りていけば
    // 上の5つのボタン・カードは順にフォーカスが乗った時点で可視領域内に
    // 入る。裁定70（テキスト欄そのものに初期フォーカスを持たせない）には
    // 触れていない——初期フォーカスは以前と変わらず宛先ホストの
    // TvFieldRow（requestInitialFocus = true）のままで、verticalScroll は
    // スクロール可能にするだけでフォーカスの初期位置やフォーカス探索の
    // 経路には影響しない。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium)

        Text(
            "疎通確認の宛先。VPN 経由でここに TCP 接続できるかで、接続先が生きているかを判定します。",
            style = MaterialTheme.typography.bodySmall,
        )

        // 裁定70/71 と同じ理由でこの画面全体の初期フォーカスもテキスト欄ではなく
        // 「行」に置く。画面全体でここだけ requestInitialFocus = true。
        TvFieldRow(
            value = host,
            onValueChange = { host = it; message = null },
            label = "宛先ホスト",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            modifier = Modifier.fillMaxWidth(),
            requestInitialFocus = true,
        )

        TvFieldRow(
            value = port,
            onValueChange = { port = it; message = null },
            label = "宛先ポート",
            inputType = InputType.TYPE_CLASS_NUMBER,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "疎通確認の間隔と、接続先が死んでいると判定するまでの連続失敗回数。" +
                "積（間隔 × 回数）が長いほど、死んだトンネルに気づくのが遅れます。",
            style = MaterialTheme.typography.bodySmall,
        )

        TvStepperRow(
            label = "プローブ間隔",
            value = probeIntervalSec,
            valueText = "${probeIntervalSec}秒",
            onDecrement = { probeIntervalSec = SettingsStepper.stepProbeInterval(probeIntervalSec, -1) },
            onIncrement = { probeIntervalSec = SettingsStepper.stepProbeInterval(probeIntervalSec, +1) },
        )

        TvStepperRow(
            label = "失敗閾値（連続失敗でダウンとみなす回数）",
            value = failureThreshold,
            valueText = "${failureThreshold}回",
            onDecrement = {
                failureThreshold = SettingsStepper.stepFailureThreshold(failureThreshold, -1)
            },
            onIncrement = {
                failureThreshold = SettingsStepper.stepFailureThreshold(failureThreshold, +1)
            },
        )

        Text(
            "現在の設定では、最短 ${probeIntervalSec}秒・最長 " +
                "${probeIntervalSec * failureThreshold}秒でダウンを検知します。",
            style = MaterialTheme.typography.bodySmall,
        )

        // 仕様書 6-1: 何を見て切り替えるか、と誤判定のリスクの両方に触れる。ソフトを
        // 弱めた「注意書き」ではなく、有効化するかどうかを利用者が判断するための
        // 警告として書く。
        // 最終再レビューの指摘7（FIX 2）: 裁定R20（窓全体の平均で判定）以降、
        // 2026-10-09: **実験機能として据え置くことを利用者が決めた。**
        // 7分半の待機中（誰も何も見ていない）に、端末自身のバックグラウンド通信の
        // 前後で「受信が細く送信が太い」窓が成立し、候補を6件歩き切る誤判定が
        // 実機で起きた（`docs/MANUAL-TEST.md` 節 10-5-1、`docs/DECISIONS.md` 項目17）。
        //
        // **この文言を弱めないこと。** 以前は低ビットレート再生（仕様書 §6-1）
        // だけを警告していたが、**待機中にも起きることが実測された**。
        // ON にするかどうかを決めるのは利用者であり、その判断に必要な事実は
        // 「誤判定されやすい」ではなく「**何も見ていないときにも切り替わった**」
        // である。原因は閾値の値ではなく、送信速度を「人が使おうとしている」の
        // 代用にしている設計そのものなので（項目17）、文言を直す前に仕様へ戻る
        // 必要がある。それが済むまでこの警告は残す。
        //
        // 裁定31（文言の改訂）: 判定を応答時間とそのばらつきに改めたので（仕様書
        // §2、受信速度は帯の条件として残る）、「受信速度を見て、下の値を下回り続けたら」
        // という旧文の説明は事実でなくなった。「弱めないこと」が守るのは、実験機能で
        // あること・実機で誤判定が実測されたこと・OFF のままを勧めることであって、
        // もう無い判定基準の説明ではない。そこで書き直した。**4点を必ず載せる:**
        // (1) いま何を判定するか、(2) 待機中の誤判定が実機で実際に起きたこと、
        // (3) それを直すために基準を変えたが**変更後は実機で確認していないこと**、
        // (4) 通常は OFF を勧めること。(3) は省かない——`docs/DECISIONS.md` 項目15 の
        // 規律（「直した」を「動くと確かめた」と読ませない）。実機で確かめたら
        // (3) の文を、確かめた事実を書いた文に差し替えること。**ただし (4) の手前の
        // 「遅延とビットレートは別物…見え方と一致しない場合がある」の文は、実機で確かめた
        // あとも残す。** 仕様書 §3「新しく残る既知の限界」の3つ目で、確認では消えない
        // 恒久の限界である（応答時間が悪い経路でも、見ているものは問題なく見えていることが
        // ある）。OFF を勧める理由は「未確認」だけではなく、この限界も含む。
        Text(
            "【実験機能】往復の遅さと、そのばらつきの両方が基準を超える状態が続いたときに、" +
                "接続先を切り替えます（受信速度が下限と上限のあいだにあるときだけ判定します）。" +
                "実機では、以前の判定（受信速度だけを見る方式）が、何も再生していない待機中にも" +
                "端末の裏側の通信で「遅い」と誤判定し、接続先を次々に切り替えてしまうことが" +
                "確認されています。この誤判定を直すために判定の基準を変えましたが、" +
                "変えたあとの動作はまだ実機で確認できていません。" +
                "また、確認が済んだあとも、往復が遅い経路で見ているものが問題なく再生されていることはあり、" +
                "遅延とビットレートは別物なので、切り替える判断が実際の見え方と一致しない場合があります。" +
                "これらの理由から、通常は OFF のままお使いください。",
            style = MaterialTheme.typography.bodySmall,
        )

        // 見た目・操作は GroupEditScreen の「自動切替」トグルと同じ:
        // Card 全体をボタンとして扱い、現在値をラベルの ON/OFF で表す
        // （tv-material に Switch 相当が無いため、この画面のほかのトグルも
        // この形で統一してある）。OFF のときも下のステッパーは操作でき、
        // 「保存」を押せば値は書き込まれる（あとで ON にしたときに効く）。
        // ラベルは「速度低下」ではなく「経路品質の低下」（仕様書 §4）。判定は受信速度の
        // 平均ではなく、往復の遅さとそのばらつきを見るようになったため、「速度」と
        // 書くと実態と合わない。
        Card(onClick = { slowLink = slowLink.copy(enabled = !slowLink.enabled) }) {
            Text(
                if (slowLink.enabled) "経路品質の低下で切り替える: ON" else "経路品質の低下で切り替える: OFF",
                modifier = Modifier.padding(20.dp),
            )
        }

        TvStepperRow(
            label = "受信速度の上限（これを下回ると「遅い」側）",
            value = slowLink.slowRxKbps,
            valueText = "${slowLink.slowRxKbps}kbps",
            onDecrement = {
                slowLink = slowLink.copy(slowRxKbps = SettingsStepper.stepSlowRxKbps(slowLink.slowRxKbps, -1))
            },
            onIncrement = {
                slowLink = slowLink.copy(slowRxKbps = SettingsStepper.stepSlowRxKbps(slowLink.slowRxKbps, +1))
            },
        )

        // 仕様書 §4-A: 4項目すべてを利用者が変更できる。既定値は1台の端末・1つの宛先・
        // 1つの時間帯の実測から引いたもので（改訂版 §3-2 の限界）、環境が違えば意味の
        // ある値も違う。各項目を動かしたときに何が起きるかは SlowLinkBounds の KDoc。
        TvStepperRow(
            label = "往復の遅さの上限（中央値がこれを超えると「経路が遅い」）",
            value = slowLink.degradedRttMs,
            valueText = "${slowLink.degradedRttMs}ms",
            onDecrement = {
                slowLink = slowLink.copy(degradedRttMs = SettingsStepper.stepDegradedRttMs(slowLink.degradedRttMs, -1))
            },
            onIncrement = {
                slowLink = slowLink.copy(degradedRttMs = SettingsStepper.stepDegradedRttMs(slowLink.degradedRttMs, +1))
            },
        )

        TvStepperRow(
            label = "ばらつきの上限（最大と最小の差がこれを超えると成立）",
            value = slowLink.degradedJitterMs,
            valueText = "${slowLink.degradedJitterMs}ms",
            onDecrement = {
                slowLink = slowLink.copy(
                    degradedJitterMs = SettingsStepper.stepDegradedJitterMs(slowLink.degradedJitterMs, -1),
                )
            },
            onIncrement = {
                slowLink = slowLink.copy(
                    degradedJitterMs = SettingsStepper.stepDegradedJitterMs(slowLink.degradedJitterMs, +1),
                )
            },
        )

        TvStepperRow(
            label = "使っていると見なす受信の下限（0 は下限なし）",
            value = slowLink.rxFloorKbps,
            valueText = "${slowLink.rxFloorKbps}kbps",
            onDecrement = {
                slowLink = slowLink.copy(rxFloorKbps = SettingsStepper.stepRxFloorKbps(slowLink.rxFloorKbps, -1))
            },
            onIncrement = {
                slowLink = slowLink.copy(rxFloorKbps = SettingsStepper.stepRxFloorKbps(slowLink.rxFloorKbps, +1))
            },
        )

        TvStepperRow(
            label = "一周したあとの再開までの時間（0 は再開しない）",
            value = slowLink.rearmAfterLapMin,
            valueText = if (slowLink.rearmAfterLapMin == 0) "再開しない" else "${slowLink.rearmAfterLapMin}分",
            onDecrement = {
                slowLink = slowLink.copy(
                    rearmAfterLapMin = SettingsStepper.stepRearmAfterLapMin(slowLink.rearmAfterLapMin, -1),
                )
            },
            onIncrement = {
                slowLink = slowLink.copy(
                    rearmAfterLapMin = SettingsStepper.stepRearmAfterLapMin(slowLink.rearmAfterLapMin, +1),
                )
            },
        )

        // 仕様書 §4-A「画面に出す導出文」。上の死活側（「最短 X秒・最長 Y秒でダウンを
        // 検知します」）と同じ作法で、設定値から結果の文を出す。数字も窓の長さも
        // SettingsText が設定と定数から導くので、ここに書き写さない（窓の長さは
        // 呼び出し側で渡さない）。以前ここにあった「速度は60秒ぶんを均して…」は
        // 速度の平均で判定していた時代の文で、いまの判定の説明としては誤りなので
        // 置き換えた。
        Text(
            SettingsText.pathQualitySummary(slowLink),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "「遅い」と判定されてから実際に別の接続先で見られるようになるのは、" +
                "そこに切替そのものの時間が加わったあとです。",
            style = MaterialTheme.typography.bodySmall,
        )

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Button(onClick = {
            val portValue = port.toIntOrNull()
            when {
                host.isBlank() -> message = "宛先ホストを入力してください"
                portValue == null || portValue !in 1..65535 ->
                    message = "ポートは 1〜65535 で指定してください"

                else -> {
                    groupStore.saveProbeTarget(ProbeTarget(host.trim(), portValue))
                    groupStore.saveProbeSchedule(
                        ProbeSchedule(
                            probeIntervalSec = probeIntervalSec,
                            failureThreshold = failureThreshold,
                        ),
                    )
                    // 6項目すべてを持つ [slowLink] をそのまま渡す。ここで項目を
                    // 取り出して `SlowLinkSettings(...)` を組み直さないこと——
                    // 組み直すと書き落とした項目が既定値へ戻る（この画面で実際に
                    // 起きた欠陥）。試験が、この画面に組み直しが無いことをソースで見る。
                    groupStore.saveSlowLinkSettings(slowLink)
                    // 裁定86（M4）: 以前は「次回のサービス起動から反映されます」と
                    // 出していたが、これは裁定48/72/74 以前の文言が取り残された
                    // ものであり、同じファイルの KDoc（上）と実装の両方に矛盾して
                    // いた。saveProbeTarget / saveProbeSchedule が書くキーはどちらも
                    // GroupStore.isReloadTriggerKey に含まれており、
                    // PrefsKeyValueStore は apply() で書くのでリスナはメインスレッドで
                    // 即座に発火し、稼働中のサービスが reloadGroupsAndProbeTarget() で
                    // 読み直す。利用者に不要な再起動・強制停止を促していた。
                    message = "保存しました。すぐに反映されます"
                }
            }
        }) {
            Text("設定を保存")
        }

        // 裁定86（L4）: 許可済みのときは許可画面が出ない（それが正常）。
        // 以前はそれを伝える手段が無く、押しても完全な無反応だったため
        // 「壊れている」ように見えた。
        Card(onClick = {
            message = if (requestConsent()) {
                null
            } else {
                "すでに許可されています"
            }
        }) {
            Text("VPN の許可を取得する", modifier = Modifier.padding(20.dp))
        }

        Card(onClick = { FailoverService.disconnect(context) }) {
            Text("VPN を切断する", modifier = Modifier.padding(20.dp))
        }

        // 裁定88（Medium 1）: 旧 UI は接続先の作成・削除の経路を持つ（既存 Java な
        // ので ProfileRepository を通らず、世代カウンタが進まない）。この Card から
        // 1操作で開けるため、裁定86（H1）の事故がこの経路に残っていた。起動を
        // TvMainActivity に委ね、旧 UI から戻ってきた時点で世代を1回進める
        // （TvMainActivity.visitedLegacyUi の KDoc 参照）。ここで startActivity を
        // 直接呼ばないのは、戻ってきたことを観測できる場所が Activity 側しか
        // 無いためである。
        Card(onClick = onOpenLegacyUi) {
            Column(Modifier.padding(20.dp)) {
                Text("詳細設定とログ（従来の画面）")
                Text(
                    "接続できないときの切り分けに使います",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(onClick = onDone) {
            Text("戻る")
        }
    }
}

/**
 * ラベル・現在値・－／＋ の3要素からなる、D-pad だけで完結する数値入力行（裁定73）。
 * `GroupEditScreen`（Task 7）の▲▼と同じ操作モデル: 左右で－／＋の `Card` へ
 * フォーカスを移し、決定で実行する。値そのものを表示する部分はフォーカスを
 * 持たない（押しても何も起きないボタンを D-pad ナビゲーションの経路に混ぜない）。
 */
@Composable
private fun TvStepperRow(
    label: String,
    value: Int,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(onClick = onDecrement) {
                Text("－", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            Text(valueText, style = MaterialTheme.typography.titleMedium)
            Card(onClick = onIncrement) {
                Text("＋", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    }
}

/**
 * プローブ間隔・失敗閾値のステッパーが使う範囲・刻み幅の純粋ロジック（裁定73）。
 * Android に依存しないので Robolectric 無しでテストできる。
 *
 * 範囲の選び方: 積（[PROBE_INTERVAL_MAX_SEC] × [FAILURE_THRESHOLD_MAX]）が
 * 「利用者が明らかにおかしいと分かる」組み合わせを作れないよう、両端を
 * 個別に抑える設計にした（積そのものを制約するより、行を見ただけで両端の値が
 * 分かるほうが TV では発見しやすい）。
 *
 * **最悪ケース（両方を最大にした場合）: 120秒 × 5回 = 600秒（10分）気づくまで
 * かかる。** この2つの定数のどちらかを広げるときは、この10分という数字が
 * 一緒に動くことを忘れないこと。
 *
 * 最良ケース（両方を最小にした場合）: 10秒 × 1回 = 10秒で気づく
 * （1回のプローブ失敗だけで即ダウン扱いになるので、瞬断を拾って過敏に切り替わる
 * リスクとの引き換えではある。既定値 30秒・3回 = 90秒はその中間）。
 */
object SettingsStepper {
    const val PROBE_INTERVAL_MIN_SEC = 10
    const val PROBE_INTERVAL_MAX_SEC = 120
    const val PROBE_INTERVAL_STEP_SEC = 10

    const val FAILURE_THRESHOLD_MIN = 1
    const val FAILURE_THRESHOLD_MAX = 5
    const val FAILURE_THRESHOLD_STEP = 1

    fun clampProbeInterval(value: Int): Int =
        value.coerceIn(PROBE_INTERVAL_MIN_SEC, PROBE_INTERVAL_MAX_SEC)

    /** [steps] は +1（右／＋）または -1（左／－）を渡す想定。刻み幅は [PROBE_INTERVAL_STEP_SEC]。 */
    fun stepProbeInterval(current: Int, steps: Int): Int =
        clampProbeInterval(current + steps * PROBE_INTERVAL_STEP_SEC)

    fun clampFailureThreshold(value: Int): Int =
        value.coerceIn(FAILURE_THRESHOLD_MIN, FAILURE_THRESHOLD_MAX)

    fun stepFailureThreshold(current: Int, steps: Int): Int =
        clampFailureThreshold(current + steps * FAILURE_THRESHOLD_STEP)

    /**
     * 「経路品質の低下で切り替える」の受信速度の上限（[SlowLinkSettings.slowRxKbps]）の
     * ステッパーが使う範囲・刻み幅（task-6）。単位は kbps。
     *
     * 範囲の選び方: 仕様書 6-1 は「音声のみの再生や低画質の動画では受信が
     * 1 Mbps を下回るのが正常」と明記している。[SLOW_RX_KBPS_MIN] を
     * これより大きく下げても、実際の動画・音声再生よりさらに細い通信しか
     * 検知できなくなり、切替がほとんど発火しない値になってしまうので実用上の
     * 意味がない。逆に [SLOW_RX_KBPS_MAX] を大きく上げると、通常のビットレートの
     * 再生まで「遅い」と誤判定して切り替えてしまう範囲に踏み込む。
     *
     * **最悪ケース（上限 5000kbps まで上げた場合）:** 数 Mbps 程度の普通の動画
     * 再生でも「遅い」と誤判定され、実際には生きている接続先を延々と
     * 乗り換え続けかねない。この値を上げるのは、仕様書 6-1 の誤判定を
     * 承知のうえで行う操作であることが前提になる。
     *
     * 最良ケース（下限 250kbps のまま）: 低ビットレートの音声のみの再生
     * （仕様書 6-1）程度の通信は「遅い」と判定されにくく、実際に受信がほぼ
     * 止まっている場合だけを拾いやすい。既定値 1000kbps はその中間。
     */
    // 範囲の定義は [SlowLinkBounds] ただ1か所（裁定30。読込経路も同じ範囲を使う）。
    // ここの2つは従来の名前で読めるようにした別名で、数字は持たない。
    const val SLOW_RX_KBPS_MIN = SlowLinkBounds.SLOW_RX_KBPS_MIN
    const val SLOW_RX_KBPS_MAX = SlowLinkBounds.SLOW_RX_KBPS_MAX
    const val SLOW_RX_KBPS_STEP = 250

    fun clampSlowRxKbps(value: Int): Int =
        value.coerceIn(SlowLinkBounds.SLOW_RX_KBPS_MIN, SlowLinkBounds.SLOW_RX_KBPS_MAX)

    /** [steps] は +1（右／＋）または -1（左／－）を渡す想定。刻み幅は [SLOW_RX_KBPS_STEP]。 */
    fun stepSlowRxKbps(current: Int, steps: Int): Int =
        clampSlowRxKbps(current + steps * SLOW_RX_KBPS_STEP)

    // 以下は経路品質の4項目（仕様書 §4-A）。**範囲（上下限）はここに書かない。**
    // [SlowLinkBounds] が持つ唯一の定義を名指す——読込経路（GroupStore）も同じ範囲を
    // 使うので、ここに数字を書き写すと、画面が許す値と読込が通す値が食い違いうる。
    // 一方、刻み幅は UI の都合なのでここに持つ。各項目を動かしたときに何が起きるかは
    // [SlowLinkBounds] の各定数の KDoc。

    const val DEGRADED_RTT_MS_STEP = 50
    const val DEGRADED_JITTER_MS_STEP = 50
    const val RX_FLOOR_KBPS_STEP = 50
    const val REARM_AFTER_LAP_MIN_STEP = 15

    fun clampDegradedRttMs(value: Int): Int =
        value.coerceIn(SlowLinkBounds.DEGRADED_RTT_MS_MIN, SlowLinkBounds.DEGRADED_RTT_MS_MAX)

    /** [steps] は +1（右／＋）または -1（左／－）を渡す想定。刻み幅は [DEGRADED_RTT_MS_STEP]。 */
    fun stepDegradedRttMs(current: Int, steps: Int): Int =
        clampDegradedRttMs(current + steps * DEGRADED_RTT_MS_STEP)

    fun clampDegradedJitterMs(value: Int): Int =
        value.coerceIn(SlowLinkBounds.DEGRADED_JITTER_MS_MIN, SlowLinkBounds.DEGRADED_JITTER_MS_MAX)

    /** 刻み幅は [DEGRADED_JITTER_MS_STEP]。 */
    fun stepDegradedJitterMs(current: Int, steps: Int): Int =
        clampDegradedJitterMs(current + steps * DEGRADED_JITTER_MS_STEP)

    fun clampRxFloorKbps(value: Int): Int =
        value.coerceIn(SlowLinkBounds.RX_FLOOR_KBPS_MIN, SlowLinkBounds.RX_FLOOR_KBPS_MAX)

    /** 刻み幅は [RX_FLOOR_KBPS_STEP]。下げて 0 にすると待機中の誤判定が戻りうる（[SlowLinkBounds.RX_FLOOR_KBPS_MIN]）。 */
    fun stepRxFloorKbps(current: Int, steps: Int): Int =
        clampRxFloorKbps(current + steps * RX_FLOOR_KBPS_STEP)

    fun clampRearmAfterLapMin(value: Int): Int =
        value.coerceIn(SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN, SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX)

    /** 刻み幅は [REARM_AFTER_LAP_MIN_STEP]。 */
    fun stepRearmAfterLapMin(current: Int, steps: Int): Int =
        clampRearmAfterLapMin(current + steps * REARM_AFTER_LAP_MIN_STEP)
}
