package net.openconnect_vpn.android.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.preference.PreferenceManager
import net.openconnect_vpn.android.MainActivity
import net.openconnect_vpn.android.core.OpenVpnService
import net.openconnect_vpn.android.core.VPNConnector
import net.openconnect_vpn.android.failover.GroupStore
import net.openconnect_vpn.android.failover.PrefsKeyValueStore

class TvMainActivity : ComponentActivity() {

    /**
     * 裁定88（Medium 1）: 旧 UI（`MainActivity` → `VPNProfileList` →
     * `ConnectionEditorActivity` / `TokenImportActivity`）は接続先を作成・削除する
     * 経路を持っており、そこは既存 Java なので [ProfileRepository] を通らない
     * （＝[GroupStore.bumpProfileGeneration] が進まない）。旧 UI は
     * [SettingsScreen] の「詳細設定とログ（従来の画面）」から1操作で開けるため、
     * そこで接続中の接続先を削除すると裁定86（H1）の事故——削除済み
     * プロファイルのトンネルが `Healthy` を名乗って残り続ける——がそのまま
     * 再現する。
     *
     * 旧画面の起動はこのアクティビティが行う Kotlin 側の処理なので、既存 Java を
     * 変更せずに閉じられる: 旧画面を開いたことを覚えておき、**戻ってきたときに
     * 1回だけ世代を進める**。旧画面で何が起きたか（作成・削除・何もしなかった）は
     * **推測しない**。戻り時に必ず1回進めれば、`FailoverService` が
     * `ProfileManager.getProfiles()` を引き直して最新の UUID 集合で
     * `loadGroups` をやり直すので、旧画面で何をされていてもエンジンの一覧は
     * 追いつく（そのあとの処理は裁定72a に合流する）。
     *
     * 復帰1回につき1回しか進まないので再読込ストームにはならない（既定 prefs の
     * リスナは `FailoverService` の1本だけで、`reloadGroupsAndProbeTarget` は
     * prefs を書かないのでフィードバックループも無い）。
     */
    private var visitedLegacyUi = false

    private lateinit var groupStore: GroupStore

    /**
     * 裁定92: 既存コアの認証ダイアログ（`AuthFormHandler` / `CertWarningDialog` など
     * `UserDialog` 派生）を**この画面に描画させる**ための `OpenVpnService` への bind。
     *
     * `OpenVpnService.promptUser()` は `mDialog` を立てて `wakeUpActivity()` を
     * 呼ぶだけで、Activity を起動しない。実際に描画されるのは、サービスに bind した
     * Activity が `startActiveDialog(this)` を呼んだときだけである
     * （`OpenVpnService.startActiveDialog` は `mDialog != null && mDialogContext == null`
     * のときに `mDialog.onStart(context)` する）。`TvMainActivity` は
     * `VPNConnector` を使っておらず `startActiveDialog` も呼んでいなかったため、
     * `mActivityConnections == 0` のまま `notification_input_needed` の通知が
     * 出るだけだった。**Fire TV には通知シェードが無い**（裁定58）ので、これは
     * 実質不可視であり、利用者から見ると「パスワードを聞かれないまま次の候補へ
     * 切り替わる」ことになる（実機で確認: 削除→再登録した myvpn には
     * `FORMDATA-*` も `ACCEPTED-CERT-*` も無く、旧画面から同じ接続先に繋ぐと
     * `Certificate warning` ダイアログが出た＝コアは正しく入力を要求している）。
     *
     * 形は旧 `MainActivity`（:85-88, :113-130）と同一にしてある。`VPNConnector` は
     * `ACTION_VPN_STATUS` のブロードキャストを受けるたびに `onUpdate` を呼ぶので、
     * ダイアログが後から立ち上がっても次の状態通知で拾われる（`promptUser` は
     * `mDialog` を立ててから `wakeUpActivity()` = ブロードキャストを出す順序なので、
     * bind 済みならこの経路で必ず1回は描画の機会が来る）。
     *
     * 裁定39 との関係: `VPNConnector` は `BIND_AUTO_CREATE` で bind するため、
     * この画面が前面にある間は `stopService` ではサービスが破棄されない。
     * しかし切断経路は既に `OpenConnectVpnController.disconnect()` =
     * `startService(ACTION_STOP_VPN)` に置き換わっており（裁定39）、bind の有無に
     * 依存しない。裁定44（消灯で切断）も同じ経路を使う。
     *
     * ここでダイアログの内容（資格情報）に触れたりログへ出したりはしない。
     * 描画先を与えるだけである。
     */
    private var vpnConnector: VPNConnector? = null

    // 裁定80（裁定78 の判定基準の差し替え。詳細は LongPressKeyUpFilter の KDoc 参照）:
    // このアプリは単一 Activity で画面は Compose のコンポーザブルが入れ替わるだけなので、
    // 長押しの DOWN も UP も同じ Activity が受け取り、「対応する DOWN を見ていない UP」
    // （裁定78 の孤児判定）では検出できなかった。差し替えた判定基準は「この押下は
    // 既に長押しとして消費されたか」。長押しハンドラ側（HomeScreen の各 onLongClick）が
    // onLongPressConsumed() を呼んで印を付け、続く ACTION_UP はその印を見て捨てるか
    // 素通しするかを決める。
    // 選択キーは KEYCODE_DPAD_CENTER だけでなく、Fire TV では KEY_KPENTER(96) が
    // KEYCODE_ENTER 系として届くことがあるため、ENTER / NUMPAD_ENTER も対象に含める。
    private val longPressKeyUpFilter = LongPressKeyUpFilter(
        trackedKeyCodes = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        ),
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> longPressKeyUpFilter.onKeyDown(event.keyCode)
            KeyEvent.ACTION_UP -> {
                if (longPressKeyUpFilter.shouldConsumeUp(event.keyCode)) {
                    // 長押しとして既に消費された押下の UP。super に渡さず、ここで捨てる。
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 裁定88（Medium 1）: 旧 UI を開く。戻ってきたときに世代を進めるための印を
     * 先に立ててから `startActivity` する（詳細は [visitedLegacyUi]）。
     */
    private fun openLegacyUi() {
        visitedLegacyUi = true
        startActivity(Intent(this, MainActivity::class.java))
    }

    /**
     * 裁定92: ダイアログの描画先として `OpenVpnService` に bind する（[vpnConnector]）。
     * 裁定88（Medium 1）: 旧 UI から戻ってきたら世代を1回進める
     * （[bumpProfileGenerationIfBackFromLegacyUi]）。
     *
     * 世代更新に `onResume` を使う理由: 旧 UI は `MainActivity` から `VPNProfileList` /
     * `ConnectionEditorActivity` / `TokenImportActivity` へ進む複数画面の集合で
     * あり、「どの画面がいつ終わるか」を当てにできない。`ActivityResult` は
     * `MainActivity` が finish したときしか届かず、ホーム経由で戻ってきた場合を
     * 拾えない。この Activity が再び前面に来たという事実だけを使うのが、
     * 旧画面の内部を推測しない最も単純な条件である。
     *
     * 印を先に false へ戻すので、旧 UI を1回開くごとに1回しか進まない
     * （TV UI 内での通常の `onResume`——起動直後・設定画面からの復帰・
     *  VPN 許可ダイアログからの復帰など——では進まない）。
     */
    override fun onResume() {
        super.onResume()

        // 裁定92: ダイアログの描画先になる（詳細は [vpnConnector]）。
        // onPause で必ず解除するので、ここで毎回作り直すのは旧 MainActivity と
        // 同じ形である。
        vpnConnector = object : VPNConnector(this, true) {
            override fun onUpdate(service: OpenVpnService) {
                service.startActiveDialog(this@TvMainActivity)
            }
        }

        bumpProfileGenerationIfBackFromLegacyUi()
    }

    /** 裁定88（Medium 1）: 旧 UI から戻ってきたときの1回だけの世代更新。 */
    private fun bumpProfileGenerationIfBackFromLegacyUi() {
        if (!visitedLegacyUi) return
        visitedLegacyUi = false
        groupStore.bumpProfileGeneration()
    }

    /**
     * 裁定92: 旧 `MainActivity.onPause`（:126-130）と同じ順序で解除する。
     * `stopActiveDialog()` はこの Activity が持っていたダイアログを
     * `onStop` して `mDialogContext` を手放す（`mDialog` 自体はサービスに残るので、
     * 次に前面へ戻ったときに `startActiveDialog` で再描画される）。
     * `unbind()` が `updateActivityRefcount(-1)` を行う。
     */
    override fun onPause() {
        vpnConnector?.let {
            it.stopActiveDialog()
            it.unbind()
        }
        vpnConnector = null
        super.onPause()
    }

    /**
     * 裁定92: 念のための保険。通常は `onPause` が必ず先に走って
     * [vpnConnector] を null にしているので、ここは何もしない。
     * 二重に `unbind()` しないよう null チェックに任せる
     * （`updateActivityRefcount(-1)` を2回走らせると `mActivityConnections` が
     * 負になり、`updateNotification` の判定が狂う）。
     */
    override fun onDestroy() {
        vpnConnector?.let {
            it.stopActiveDialog()
            it.unbind()
        }
        vpnConnector = null
        super.onDestroy()
    }

    /**
     * 裁定88（Medium 1）: 旧 UI を表示している間にこの Activity が破棄されても
     * （メモリ不足・設定変更）、戻ってきたときの世代更新を落とさない。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_VISITED_LEGACY_UI, visitedLegacyUi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 裁定88（Medium 1）: 旧 UI 滞在中に破棄された場合の復元（[visitedLegacyUi]）。
        visitedLegacyUi = savedInstanceState?.getBoolean(STATE_VISITED_LEGACY_UI) == true

        // 裁定64: Activity（this）を渡すと ProfileManager の静的 mContext に
        // 破棄済みの Activity が残り続ける（ProfileRepository の全メソッドが
        // ProfileManager.init(context) を呼ぶため）。applicationContext は
        // プロセスと寿命が一致するので安全。ProfileManager.init が使うのは
        // getSharedPreferences と getApplicationInfo().dataDir だけで、
        // Activity 固有の機能は使わない。
        // 裁定86（H1）: ProfileRepository は接続先の作成・削除のたびに
        // GroupStore.bumpProfileGeneration() を進める（既定 SharedPreferences の
        // キーが変わることで、稼働中の FailoverService が既存の再読込経路で
        // グループを読み直す）。そのため同じ GroupStore インスタンスを渡す
        // ——2つ作っても書き先の既定 prefs は同じだが、保存先が1つであることを
        // 構造で示しておく。
        groupStore = GroupStore(
            PrefsKeyValueStore(PreferenceManager.getDefaultSharedPreferences(this)),
        )
        val profiles = ProfileRepository(applicationContext, groupStore)

        setContent {
            TvTheme {
                var screen by remember { mutableStateOf<TvScreen>(TvScreen.Home) }

                // 裁定61: サブ画面から Back を押すとアプリごと終了していた
                // （R4「すべてリモコンで操作」に反する）。Home 以外を表示している間は
                // Back を Home への遷移に割り当てる。Home では既定動作（アプリ終了）
                // のままでよい。
                BackHandler(enabled = screen != TvScreen.Home) {
                    screen = TvScreen.Home
                }

                when (val current = screen) {
                    TvScreen.Home -> HomeScreen(
                        profiles = profiles,
                        groupStore = groupStore,
                        onNavigate = { screen = it },
                        // 裁定80: HomeScreen の各 onLongClick ハンドラの先頭で必ずこれを
                        // 通すこと（詳細は HomeScreen の onLongPressConsumed の KDoc 参照）。
                        onLongPressConsumed = longPressKeyUpFilter::onLongPressConsumed,
                    )

                    is TvScreen.EditProfile -> ProfileEditScreen(
                        profiles = profiles,
                        editingUuid = current.uuid,
                        onDone = { screen = TvScreen.Home },
                    )

                    is TvScreen.EditGroup -> GroupEditScreen(
                        profiles = profiles,
                        groupStore = groupStore,
                        editingGroupId = current.groupId,
                        onDone = { screen = TvScreen.Home },
                    )

                    TvScreen.Settings -> SettingsScreen(
                        groupStore = groupStore,
                        onDone = { screen = TvScreen.Home },
                        // 裁定88（Medium 1）: 旧 UI の起動はこのアクティビティが
                        // 引き受ける（戻り時に世代を進めるため。visitedLegacyUi の
                        // KDoc 参照）。
                        onOpenLegacyUi = ::openLegacyUi,
                    )
                }
            }
        }
    }

    private companion object {
        /** 裁定88（Medium 1）: [visitedLegacyUi] の保存キー。 */
        const val STATE_VISITED_LEGACY_UI = "visited_legacy_ui"
    }
}
