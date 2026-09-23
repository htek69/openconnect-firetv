package net.openconnect_vpn.android.tv

import android.net.VpnService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.failover.GroupStore

// Task 8 完了時に削除する（プレースホルダーは各タスクで本実装に置き換わる）。

@Composable
fun ProfileEditScreen(profiles: ProfileRepository, editingUuid: String?, onDone: () -> Unit) {
    // Task 6 で実装する
}

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

/**
 * Task 5 で本実装に差し替える。
 *
 * 裁定57: ランチャーが MainActivity（旧スマホ UI）から外れるため、VPN 許可
 * （VpnService.prepare）を取り直す手段が TV UI 側に無いと詰む可能性がある
 * （Android は VpnService の許可を1つのアプリにしか与えず、別の OpenConnect
 * アプリが後から許可を奪える）。Task 5 を待たずにここへ導線だけ前倒しする。
 * 許可済みかどうかは画面復帰のたびに（ON_RESUME で）確認し直す。
 */
@Composable
fun HomeScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    onNavigate: (TvScreen) -> Unit,
) {
    val context = LocalContext.current
    val requestConsent = rememberVpnConsentLauncher()
    val lifecycleOwner = LocalLifecycleOwner.current
    var needsConsent by remember { mutableStateOf(VpnService.prepare(context) != null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                needsConsent = VpnService.prepare(context) != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("ホーム画面（Task 5 で実装する）", style = MaterialTheme.typography.headlineMedium)

            if (needsConsent) {
                Text(
                    "接続にはこの端末で一度だけ VPN の利用許可が必要です。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { requestConsent() },
                    modifier = Modifier.focusRequester(focusRequester),
                ) {
                    Text("VPN 利用を許可する")
                }
            } else {
                Button(
                    onClick = {},
                    modifier = Modifier.focusRequester(focusRequester),
                ) {
                    Text("準備中")
                }
            }
        }
    }
}
