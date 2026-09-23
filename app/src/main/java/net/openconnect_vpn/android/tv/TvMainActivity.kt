package net.openconnect_vpn.android.tv

import android.os.Bundle
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
