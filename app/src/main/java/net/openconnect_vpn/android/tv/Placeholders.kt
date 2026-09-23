package net.openconnect_vpn.android.tv

import androidx.compose.runtime.Composable
import net.openconnect_vpn.android.failover.GroupStore

// Task 8 完了時に削除する（プレースホルダーは各タスクで本実装に置き換わる）。
// ProfileEditScreen は Task 6 で ProfileEditScreen.kt に実装済み。

@Composable
fun GroupEditScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    editingGroupId: String?,
    onDone: () -> Unit,
) {
    // Task 7 で実装する
}

@Composable
fun SettingsScreen(groupStore: GroupStore, onDone: () -> Unit) {
    // Task 8 で実装する
}
