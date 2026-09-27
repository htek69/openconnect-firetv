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
import net.openconnect_vpn.android.failover.SlowLinkSettings
import net.openconnect_vpn.android.failover.SlowLinkThresholds

/**
 * 裁定R24: 速度を均す窓の長さ（秒）。この画面の説明文が出す「検知までの時間」を
 * 判定器の既定値そのものから取るための参照で、数字を画面側に書き写さない。
 * 窓の長さは利用者に変えさせない定数（仕様書 5）であり、`FailoverService` も
 * `SlowLinkThresholds` の既定値のまま判定器を作るため、ここで既定値を読むのが
 * 実際に効いている値である。
 */
private val SLOW_LINK_WINDOW_SEC = SlowLinkThresholds().windowSec

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
    var slowLinkEnabled by remember { mutableStateOf(storedSlowLink.enabled) }
    var slowRxKbps by remember {
        mutableStateOf(SettingsStepper.clampSlowRxKbps(storedSlowLink.slowRxKbps))
    }

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

        // 仕様書 6-1: 何を見て切り替えるか（VPN トンネルの受信速度）と、
        // 低ビットレート再生の誤判定リスクの両方に触れる。ソフトを弱めた
        // 「注意書き」ではなく、有効化するかどうかを利用者が判断するための
        // 警告として書く。
        Text(
            "VPN 経由の受信速度を見て、下の値を下回り続けたら「遅い」とみなし" +
                "接続先を切り替えます。音声のみの再生や低画質の動画は受信速度が" +
                "下がるのが正常なため、遅いと誤判定することがあります。",
            style = MaterialTheme.typography.bodySmall,
        )

        // 見た目・操作は GroupEditScreen の「自動切替」トグルと同じ:
        // Card 全体をボタンとして扱い、現在値をラベルの ON/OFF で表す
        // （tv-material に Switch 相当が無いため、この画面のほかのトグルも
        // この形で統一してある）。OFF のときも下のステッパーは操作でき、
        // 「保存」を押せば値は書き込まれる（あとで ON にしたときに効く）。
        Card(onClick = { slowLinkEnabled = !slowLinkEnabled }) {
            Text(
                if (slowLinkEnabled) "速度低下で切り替える: ON" else "速度低下で切り替える: OFF",
                modifier = Modifier.padding(20.dp),
            )
        }

        TvStepperRow(
            label = "受信速度の下限",
            value = slowRxKbps,
            valueText = "${slowRxKbps}kbps",
            onDecrement = { slowRxKbps = SettingsStepper.stepSlowRxKbps(slowRxKbps, -1) },
            onIncrement = { slowRxKbps = SettingsStepper.stepSlowRxKbps(slowRxKbps, +1) },
        )

        // 裁定R24: 上の死活側（「最短 X秒・最長 Y秒でダウンを検知します」）と同じ
        // 体裁で、速度側の検知までの時間も書く。窓の長さは利用者が変えられない
        // 定数（仕様書 5）なので、数字はその定数から出して二重管理にしない。
        // 「約60秒」と書かないと、有効にして20秒待った利用者が壊れていると
        // 判断する（切替そのものの時間が後ろに付くことも書く）。
        Text(
            "速度は${SLOW_LINK_WINDOW_SEC}秒ぶんを均して判断するため、" +
                "遅くなってから「遅い」と判定するまで約${SLOW_LINK_WINDOW_SEC}秒かかります。" +
                "実際に別の接続先で見られるようになるのは、そこに切替そのものの時間が" +
                "加わったあとです。",
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
                    groupStore.saveSlowLinkSettings(
                        SlowLinkSettings(
                            enabled = slowLinkEnabled,
                            slowRxKbps = slowRxKbps,
                        ),
                    )
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
     * 「速度低下で切り替える」の受信速度下限（[SlowLinkSettings.slowRxKbps]）の
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
    const val SLOW_RX_KBPS_MIN = 250
    const val SLOW_RX_KBPS_MAX = 5000
    const val SLOW_RX_KBPS_STEP = 250

    fun clampSlowRxKbps(value: Int): Int =
        value.coerceIn(SLOW_RX_KBPS_MIN, SLOW_RX_KBPS_MAX)

    /** [steps] は +1（右／＋）または -1（左／－）を渡す想定。刻み幅は [SLOW_RX_KBPS_STEP]。 */
    fun stepSlowRxKbps(current: Int, steps: Int): Int =
        clampSlowRxKbps(current + steps * SLOW_RX_KBPS_STEP)
}
