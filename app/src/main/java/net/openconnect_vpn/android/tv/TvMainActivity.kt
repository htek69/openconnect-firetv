package net.openconnect_vpn.android.tv

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
import net.openconnect_vpn.android.failover.GroupStore
import net.openconnect_vpn.android.failover.PrefsKeyValueStore

class TvMainActivity : ComponentActivity() {

    // 裁定78: 長押しで画面遷移する操作（onLongClick）は ACTION_DOWN 中に発火するため、
    // 続く ACTION_UP が行き場を失い、遷移先の画面の初期フォーカス要素
    // （編集モードやダイアログの既定選択）を誤って押してしまう。対応する DOWN を
    // この Activity が見ていない UP は孤児とみなして捨てる。判定ロジック自体は
    // Android に依存しない OrphanKeyUpFilter に切り出してあり、JVM テストで検証できる。
    // 選択キーは KEYCODE_DPAD_CENTER だけでなく、Fire TV では KEY_KPENTER(96) が
    // KEYCODE_ENTER 系として届くことがあるため、ENTER / NUMPAD_ENTER も対象に含める。
    private val orphanKeyUpFilter = OrphanKeyUpFilter(
        trackedKeyCodes = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        ),
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> orphanKeyUpFilter.onKeyDown(event.keyCode)
            KeyEvent.ACTION_UP -> {
                if (orphanKeyUpFilter.shouldConsumeUp(event.keyCode)) {
                    // 孤児 UP。super に渡さず、ここで消費して捨てる。
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 裁定64: Activity（this）を渡すと ProfileManager の静的 mContext に
        // 破棄済みの Activity が残り続ける（ProfileRepository の全メソッドが
        // ProfileManager.init(context) を呼ぶため）。applicationContext は
        // プロセスと寿命が一致するので安全。ProfileManager.init が使うのは
        // getSharedPreferences と getApplicationInfo().dataDir だけで、
        // Activity 固有の機能は使わない。
        val profiles = ProfileRepository(applicationContext)
        val groupStore = GroupStore(
            PrefsKeyValueStore(PreferenceManager.getDefaultSharedPreferences(this)),
        )

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
                    )
                }
            }
        }
    }
}
