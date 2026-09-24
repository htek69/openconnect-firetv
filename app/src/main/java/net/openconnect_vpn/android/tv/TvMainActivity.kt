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
                    )
                }
            }
        }
    }
}
