package net.openconnect_vpn.android.tv

import android.net.VpnService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.FailoverService
import net.openconnect_vpn.android.failover.FailoverStateHolder
import net.openconnect_vpn.android.failover.GroupStore

/**
 * 接続先とグループの一覧画面。
 *
 * 裁定58: 表示する接続状態は [FailoverStateHolder] から読む。状態機械そのものの
 * 真実は `FailoverService` 内の `FailoverController` が持ち、ここは
 * その投影を購読して描くだけ（真実を二重に持たない）。
 *
 * 裁定57 の VPN 許可導線（`Placeholders.kt` の仮実装にあった）もここへ引き継ぐ。
 * Android は VpnService の許可を1つのアプリにしか与えず、別の OpenConnect アプリが
 * 後から許可を奪えるため、許可を取り直す手段が無いと詰む。許可済みかどうかは
 * 画面復帰のたび（ON_RESUME）に確認し直す。
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

    if (needsConsent) {
        ConsentRequiredScreen(onRequestConsent = requestConsent)
        return
    }

    var reloadToken by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<HomeRow.ProfileRow?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val profileList = remember(reloadToken) { profiles.list() }
    val groupList = remember(reloadToken) {
        groupStore.loadGroups(profileList.map { it.uuid }.toSet())
    }

    // 裁定58: FailoverService が publish する現在の状態機械の状態を購読する。
    // ここが変われば rows も再計算され、バッジと候補名が実際の接続状況に追従する。
    val failoverState by FailoverStateHolder.state.collectAsStateWithLifecycle()
    // 裁定65（指摘8）: 状態機械が実際に使っているグループのスナップショット。
    // groupList（下の remember、GroupStore から読み直したもの）と世代がずれて
    // いないかを照合するために使う。
    val engineActiveGroup by FailoverStateHolder.activeGroup.collectAsStateWithLifecycle()

    val rows = remember(reloadToken, profileList, groupList, failoverState, engineActiveGroup) {
        val built = HomeRows.build(groupList, profileList, failoverState)
        HomeRows.withTrustworthyMemberNames(built, groupList, engineActiveGroup)
    }

    val firstItemFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("接続先", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // 既存のグループ行は長押しでしか編集画面へ入れず、グループが
                // 1つも無い最初の状態ではそもそも長押しできる行が無い
                // （Task 7）。新規作成への導線をここに独立して置く。
                Card(onClick = { onNavigate(TvScreen.EditGroup(null)) }) {
                    Text("グループを作成", modifier = Modifier.padding(16.dp))
                }
                Card(onClick = { onNavigate(TvScreen.Settings) }) {
                    Text("設定", modifier = Modifier.padding(16.dp))
                }
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows) { row ->
                val isFirst = row === rows.firstOrNull()
                val focusModifier =
                    if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier

                when (row) {
                    is HomeRow.GroupRow -> GroupCard(
                        row = row,
                        modifier = focusModifier,
                        onToggleConnection = {
                            // 裁定63（レビュー指摘5）: None と Retrying（Exhausted）は
                            // 「まだ何もつながっていない／全滅してバックオフ待ち」で
                            // どちらも接続操作が要る。Retrying を切断側に倒すと、
                            // 全滅からの復帰（裁定35 の除外クリア）に UserConnectGroup
                            // が要るにもかかわらずラベルが「切断」になり、利用者は
                            // 実際と違う操作を2回踏まされる。
                            when (row.badge) {
                                ConnectionBadge.None, ConnectionBadge.Retrying ->
                                    FailoverService.connectGroup(context, row.groupId)

                                ConnectionBadge.Connecting,
                                ConnectionBadge.Verifying,
                                ConnectionBadge.Connected,
                                -> FailoverService.disconnect(context)
                            }
                        },
                        onEdit = { onNavigate(TvScreen.EditGroup(row.groupId)) },
                    )

                    is HomeRow.SectionHeader -> Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )

                    is HomeRow.ProfileRow -> ProfileCard(
                        row = row,
                        modifier = focusModifier,
                        onEdit = { onNavigate(TvScreen.EditProfile(row.uuid)) },
                        onDelete = {
                            statusMessage = null
                            pendingDelete = row
                        },
                    )

                    HomeRow.AddProfile -> Card(
                        onClick = { onNavigate(TvScreen.EditProfile(null)) },
                        modifier = focusModifier,
                    ) {
                        Text("＋ 接続先を追加", modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        val warning = HomeRows.groupDeletionWarning(target.uuid, groupList)
        val message = if (warning == null) {
            "「${target.name}」を削除しますか？"
        } else {
            "「${target.name}」を削除しますか？\n\n$warning"
        }
        ConfirmDialog(
            message = message,
            onConfirm = {
                val deleted = profiles.delete(target.uuid)
                pendingDelete = null
                statusMessage = if (deleted) {
                    null
                } else {
                    "「${target.name}」の削除に失敗しました。既に削除されている可能性があります。"
                }
                reloadToken++
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // TV UI で最も多い不具合は「フォーカスがどこにも無い」状態。
    // 画面表示時に必ず先頭項目へフォーカスを置く。
    //
    // 裁定60（レビュー指摘2・High）: 以前は LaunchedEffect(rows) だったため、
    // rows は failoverState が変わるたびに再計算される内容（バッジ）で
    // equals が変わり、接続中はバッジが何度も遷移する分だけこの効果が毎回
    // 再実行されていた。その結果、利用者が2行目以降にフォーカスを合わせていても
    // 接続の進行にあわせて勝手に1行目へ戻り、次に押した決定が意図しない行
    // （＝意図しない接続や切断）に当たっていた。初回表示のときだけ要求するよう、
    // 一度実行したら二度と実行しないガードを立てる。
    //
    // 初回に rows が空（要求先が無い）ケース: 現状 HomeRows.build は
    // HomeRow.AddProfile を必ず末尾に足すため rows が空になることは無いが、
    // 将来 build が変わって空を返しうる場合に備え、rows が空の間は要求せず
    // 「空でなくなった最初の回」まで待つ（didRequestInitialFocus を立てない）。
    var didRequestInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty()) {
        if (didRequestInitialFocus || rows.isEmpty()) return@LaunchedEffect
        didRequestInitialFocus = true
        runCatching { firstItemFocus.requestFocus() }
    }
}

/**
 * 裁定57/58: VPN 許可が無いと `FailoverService` は無人で繋げず、通知を出すだけで
 * 止まる。一覧より前にここで止め、許可を取る手段を必ず提示する。
 * 初期フォーカスはここでも必ず置く。
 */
@Composable
private fun ConsentRequiredScreen(onRequestConsent: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "接続にはこの端末で一度だけ VPN の利用許可が必要です。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onRequestConsent,
                modifier = Modifier.focusRequester(focusRequester),
            ) {
                Text("VPN 利用を許可する")
            }
        }
    }
}

/**
 * 裁定63（レビュー指摘5）: グループ行の決定操作は [ConnectionBadge] に応じて
 * 接続/切断を切り替える。ラベルと実際の動作が必ず一致するよう、対応を
 * ここに一覧化する（状態機械の [FailoverState] とバッジの対応は
 * [HomeRows.badgeFor] を参照）。
 *
 * | バッジ | ラベル | 決定で呼ぶ関数 | 備考 |
 * |---|---|---|---|
 * | [ConnectionBadge.None] | 決定で接続 | `connectGroup` | 何も対象になっていない |
 * | [ConnectionBadge.Connecting] | 決定で切断 | `disconnect` | ダイヤル中 |
 * | [ConnectionBadge.Verifying] | 決定で切断 | `disconnect` | トンネル確立・疎通確認待ち |
 * | [ConnectionBadge.Connected] | 決定で切断 | `disconnect` | 疎通確認済み |
 * | [ConnectionBadge.Retrying] | 決定で再接続 | `connectGroup` | `Exhausted`。裁定35 により `UserConnectGroup` だけが除外集合をクリアして再試行できる |
 *
 * `Retrying`（`Exhausted`）を「切断」側に倒さなかった理由: 何も接続されていない
 * 状態に「切断」ラベルを出すのは事実と矛盾するうえ、全滅からの復帰には
 * どのみち `UserConnectGroup` が要る（`disconnect` を挟むと Idle には戻るが
 * 除外集合はクリアされず、次に「接続」を押しても何も繋がらない: 裁定35b）。
 */
@Composable
private fun GroupCard(
    row: HomeRow.GroupRow,
    modifier: Modifier,
    onToggleConnection: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(onClick = onToggleConnection, onLongClick = onEdit, modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            val auto = if (row.autoFailoverEnabled) "自動切替 ON" else "自動切替 OFF"
            Text(
                "${row.memberCount} 件の候補 · $auto${statusSuffix(row)}",
                style = MaterialTheme.typography.bodySmall,
            )
            // 裁定63: ラベルは実際の動作と一致させる。全状態の対応は
            // GroupCard 直前の KDoc の表を参照。
            val actionHint = when (row.badge) {
                ConnectionBadge.None -> "決定で接続"
                ConnectionBadge.Retrying -> "決定で再接続"
                ConnectionBadge.Connecting,
                ConnectionBadge.Verifying,
                ConnectionBadge.Connected,
                -> "決定で切断"
            }
            Text("$actionHint · 長押しで編集", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * バッジと候補名を1つの文言にまとめる。
 *
 * [HomeRow.GroupRow.memberName] は「接続済み」を意味しない（[HomeRow.GroupRow] の
 * KDoc 参照）。バッジ抜きで名前だけを出すと接続済みだと誤解させるので、
 * 必ずバッジの文言と一緒にしか出さない。
 */
private fun statusSuffix(row: HomeRow.GroupRow): String {
    val badgeText = when (row.badge) {
        ConnectionBadge.None -> return ""
        ConnectionBadge.Connecting -> "接続中"
        ConnectionBadge.Verifying -> "疎通確認中"
        ConnectionBadge.Connected -> "接続済み"
        ConnectionBadge.Retrying -> "再試行待ち"
    }
    val target = row.memberName?.let { " ($it)" } ?: ""
    return " / $badgeText$target"
}

@Composable
private fun ProfileCard(
    row: HomeRow.ProfileRow,
    modifier: Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onEdit, onLongClick = onDelete, modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(row.serverAddress, style = MaterialTheme.typography.bodySmall)
            Text("長押しで削除", style = MaterialTheme.typography.bodySmall)
        }
    }
}
