package net.openconnect_vpn.android.tv

import android.content.Intent
import android.text.InputType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import net.openconnect_vpn.android.MainActivity
import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverService
import net.openconnect_vpn.android.failover.GroupStore
import net.openconnect_vpn.android.failover.ProbeTarget

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
 * ## プローブ間隔・失敗閾値の永続化について
 *
 * [FailoverConfig] はデータ上グループ単位（[FailoverGroup.config]）に持たせて
 * いるが、この画面にグループを個別に選ばせる UI は無い（グループ編集画面
 * （Task 7 の `GroupEditScreen`）も config を編集させない。常に既定値
 * [FailoverConfig] のまま作成される）。よってこの画面は「プローブ間隔・失敗閾値は
 * 全グループ共通」という運用を前提に、保存時に**保存済みの全グループへ同じ値を
 * 書き込み** `groupStore.saveGroups(...)` を呼ぶ。これは Task 6.5（裁定48/72）が
 * 既に用意している「`GroupStore` のグループ保存キーが変われば `FailoverService` が
 * 読み直す」経路をそのまま使うだけであり、新しいシグナリング経路は増やさない。
 *
 * 既知の制約: この画面を開いた時点で1件もグループが無ければ、変更を書き込む先が
 * 無いので何も保存されない（既定値 30秒 / 3回のまま何もできない）。また、この
 * 画面で値を変えた**後に** `GroupEditScreen` で新しいグループを作ると、そちらは
 * 常にコンパイル時既定値 [FailoverConfig] で作成されるため、新グループだけ
 * この画面の値から一時的に外れる（次にこの画面を開いて保存し直せば全グループに
 * 揃う）。グループ単位の config 編集 UI か、真にグループ非依存の保存先を
 * `GroupStore` に足す設計変更は Task 8 の範囲外なので行わない（範囲は
 * `SettingsScreen.kt` / `VpnConsent.kt` の作成と `Placeholders.kt` の削除）。
 */
@Composable
fun SettingsScreen(groupStore: GroupStore, onDone: () -> Unit) {
    val context = LocalContext.current

    // グループ一覧はプローブ間隔・失敗閾値の初期値取得と保存の両方に使う。
    // GroupStore.loadGroups は「今も存在するプロファイルの UUID」を渡さないと
    // メンバーを削除済みとみなして落としてしまうため、既存の ProfileRepository
    // （ProfileManager への薄いアダプタ、Task 6 で導入済み）から取得する。
    val knownProfileUuids = remember {
        ProfileRepository(context).list().map { it.uuid }.toSet()
    }
    val storedGroups = remember { groupStore.loadGroups(knownProfileUuids) }

    val storedTarget = remember { groupStore.loadProbeTarget() }
    var host by remember { mutableStateOf(storedTarget.host) }
    var port by remember { mutableStateOf(storedTarget.port.toString()) }

    var probeIntervalSec by remember {
        mutableStateOf(SettingsStepper.initialProbeIntervalSec(storedGroups))
    }
    var failureThreshold by remember {
        mutableStateOf(SettingsStepper.initialFailureThreshold(storedGroups))
    }

    var message by remember { mutableStateOf<String?>(null) }

    val requestConsent = rememberVpnConsentLauncher()

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Button(onClick = {
            val portValue = port.toIntOrNull()
            when {
                host.isBlank() -> message = "宛先ホストを入力してください"
                portValue == null || portValue !in 1..65535 ->
                    message = "ポートは 1〜65535 で指定してください"

                else -> {
                    groupStore.saveProbeTarget(ProbeTarget(host.trim(), portValue))
                    groupStore.saveGroups(
                        SettingsStepper.applyProbeSchedule(
                            groups = storedGroups,
                            probeIntervalSec = probeIntervalSec,
                            failureThreshold = failureThreshold,
                        ),
                    )
                    message = "保存しました。次回のサービス起動から反映されます"
                }
            }
        }) {
            Text("設定を保存")
        }

        Card(onClick = { requestConsent() }) {
            Text("VPN の許可を取得する", modifier = Modifier.padding(20.dp))
        }

        Card(onClick = { FailoverService.disconnect(context) }) {
            Text("VPN を切断する", modifier = Modifier.padding(20.dp))
        }

        Card(onClick = {
            context.startActivity(Intent(context, MainActivity::class.java))
        }) {
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
 * プローブ間隔・失敗閾値のステッパーが使う範囲・刻み幅と、保存/初期値取得の
 * 純粋ロジック（裁定73）。Android に依存しないので Robolectric 無しでテストできる。
 *
 * 範囲の選び方: 積（[PROBE_INTERVAL_MAX_SEC] × [FAILURE_THRESHOLD_MAX]）が
 * 「利用者が明らかにおかしいと分かる」組み合わせを作れないよう、両端を
 * 個別に抑える設計にした（積そのものを制約するより、行を見ただけで両端の値が
 * 分かるほうが TV では発見しやすい）。
 *
 * 最悪ケース（両方を最大にした場合）: 120秒 × 5回 = 600秒（10分）気づくまでかかる。
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

    /** 保存済みグループが無ければ [FailoverConfig] の既定値を使う。 */
    fun initialProbeIntervalSec(groups: List<FailoverGroup>): Int =
        clampProbeInterval(groups.firstOrNull()?.config?.probeIntervalSec ?: FailoverConfig().probeIntervalSec)

    fun initialFailureThreshold(groups: List<FailoverGroup>): Int =
        clampFailureThreshold(groups.firstOrNull()?.config?.failureThreshold ?: FailoverConfig().failureThreshold)

    /**
     * 保存済みの全グループへ同じプローブ間隔・失敗閾値を書き込む。
     * config の他フィールド（probeTimeoutMs 等）とグループ自体（メンバー・
     * 自動切替 ON/OFF）はそのまま保つ。
     */
    fun applyProbeSchedule(
        groups: List<FailoverGroup>,
        probeIntervalSec: Int,
        failureThreshold: Int,
    ): List<FailoverGroup> = groups.map { group ->
        group.copy(
            config = group.config.copy(
                probeIntervalSec = probeIntervalSec,
                failureThreshold = failureThreshold,
            ),
        )
    }
}
