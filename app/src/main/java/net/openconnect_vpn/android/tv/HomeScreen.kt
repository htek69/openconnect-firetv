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
import androidx.compose.ui.focus.focusProperties
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
import kotlinx.coroutines.delay
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
    // 裁定80: 長押しで画面遷移・ダイアログ表示する操作は ACTION_DOWN 中に発火し、
    // 続く ACTION_UP が遷移先の初期フォーカス要素を誤って押してしまう
    // （LongPressKeyUpFilter の KDoc 参照）。この画面の onLongClick ハンドラは
    // 必ず [withLongPressConsumed] を経由してこれを呼ぶこと。直接
    // `onLongClick = { ... }` と書いて素通ししないこと。
    onLongPressConsumed: () -> Unit,
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
        // 裁定86（L4）: rememberVpnConsentLauncher は「許可画面を出したか」を
        // 返すようになったが、この画面は needsConsent が true のときだけ出るので
        // （＝必ず未許可）戻り値を見る意味が無い。ラムダで包んで捨てる。
        ConsentRequiredScreen(onRequestConsent = { requestConsent() })
        return
    }

    var reloadToken by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<HomeRow.ProfileRow?>(null) }
    // 「初回ログイン」の確認。削除（pendingDelete）と同じ作法で、実行前に1枚挟む
    // （この操作はいまの VPN 接続を切る）。文面は作れるときだけ作って持たせる
    // （[PendingFirstLogin] の KDoc）。
    var pendingFirstLogin by remember { mutableStateOf<PendingFirstLogin?>(null) }
    // 確認で実行した「初回ログイン」の試行。**確認そのもので立てる**
    // （[FirstLoginAttempt] と HomeRows.firstLoginAttemptInFlight の KDoc）。
    var firstLoginAttempt by remember { mutableStateOf<FirstLoginAttempt?>(null) }
    var firstLoginAttemptSeq by remember { mutableStateOf(0) }
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

    // 初回ログインが未完了の接続先には行に注記を出す（HomeRows.firstLoginNotice）。
    // この判定だけは rows とは別の remember に分け、**failoverState そのものを
    // キーにしない**。判定は prefs の読み直しを伴うのに、Healthy.lastProbeAtMs は
    // 疎通確認ごと（既定30秒）に変わるため、rows と同じキーにすると接続中に
    // ホームを開いているあいだ30秒ごとにメインスレッドで shared_prefs を
    // 走査し続けることになる（裁定16 によりこの経路はメインスレッドに固定
    // されているので、逃がすのではなく回数を減らす）。
    //
    // 引き直す契機は「接続先の一覧が変わったとき」（reloadToken / profileList）と
    // 「接続の節目」（HomeRows.firstLoginRecheckKey。状態の種類・グループ・候補が
    // 変わったとき）だけ。初回ログインが通った直後に注記が消えることは
    // Connecting → Verifying → Healthy の遷移で鍵が変わることで保たれる
    // （詳細は firstLoginRecheckKey の KDoc）。
    //
    // この鍵は「初回ログインの進行中の印」を降ろす判断にも使う
    // （HomeRows.firstLoginAttemptInFlight）。**判定の呼び出し口を増やすことには
    // ならない**: 鍵を1つの val にまとめて両方で読むだけで、needsFirstLogin を
    // 呼ぶのは依然この remember の中だけである。
    val firstLoginRecheckKey = HomeRows.firstLoginRecheckKey(failoverState)
    val needsFirstLoginByUuid = remember(
        reloadToken,
        profileList,
        firstLoginRecheckKey,
    ) {
        profileList.associate { it.uuid to profiles.needsFirstLogin(it.uuid) }
    }

    // 「初回ログイン中」の印を出す接続先。確認で立てた試行がまだ決着していない
    // あいだだけ非 null になる（判定の引き直しは伴わない）。
    val firstLoginInProgressUuid = firstLoginAttempt
        ?.takeIf {
            HomeRows.firstLoginAttemptInFlight(it.groupId, it.startedAtRecheckKey, failoverState)
        }
        ?.uuid

    val rows = remember(
        reloadToken,
        profileList,
        groupList,
        failoverState,
        engineActiveGroup,
        needsFirstLoginByUuid,
        firstLoginInProgressUuid,
    ) {
        val built = HomeRows.build(
            groupList,
            profileList,
            failoverState,
            needsFirstLogin = { uuid -> needsFirstLoginByUuid[uuid] == true },
            firstLoginInProgressUuid = firstLoginInProgressUuid,
        )
        HomeRows.withTrustworthyMemberNames(built, groupList, engineActiveGroup)
    }
    // 裁定84（fix8）: rows のうちフォーカス対象になりうる行のキーだけを並べたもの。
    // 初回フォーカスの対象や、ConfirmDialog を閉じたあとの復帰先
    // （HomeRows.targetKeyAfterConfirmedDelete）を決めるのに使う。
    val focusableKeys = remember(rows) { rows.mapNotNull { it.focusKey() } }

    // 裁定84（fix8）: 行ごとの FocusRequester を focusKey() で引けるようにする
    // キャッシュ。rows はバッジの変化などのたびに新しい List インスタンスとして
    // 作り直されるが、同じ行なら focusKey() は変わらないので、ここに一度作った
    // FocusRequester をそのまま使い続ける（作り直すと、対象を掴んだままの
    // Modifier.focusRequester との対応が切れてしまう）。Compose の状態
    // （mutableStateOf）には乗せない単なるオブジェクトキャッシュなので
    // remember { } だけで十分（このマップ自体の変更が再コンポーズを起こす
    // 必要はない）。
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun focusRequesterFor(key: String): FocusRequester =
        focusRequesters.getOrPut(key) { FocusRequester() }

    // 裁定84（fix8）: ConfirmDialog を「閉じた」という一度きりの出来事にだけ
    // 反応してフォーカスを戻すためのリクエスト。token は、内容が同じリクエストが
    // 連続しても LaunchedEffect のキーが必ず変わる（＝必ず再実行される）ように
    // するためだけの値。rows の変化（裁定60 が禁じる「行が変わるたびに」）とは
    // 独立に、onConfirm/onDismiss の中でだけ発行する。
    var focusRestoreRequest by remember { mutableStateOf<FocusRestoreRequest?>(null) }
    var focusRestoreTokenSeq by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp)
            // 裁定83: ConfirmDialog は別 Window の Dialog をやめ、この画面と同じ
            // Window の中に描くオーバーレイになった。表示中に背後のこの Column
            // （ヘッダーの2枚のカードと LazyColumn の各行）へフォーカスが移ると、
            // オーバーレイの陰で意図しない接続/切断や編集画面遷移が起きうる。
            // Modifier.focusProperties の canFocus は、その要素配下の
            // フォーカスターゲットに継承されるため、この1箇所を false にするだけで
            // LazyColumn の行・ヘッダーのカードを含む配下すべてがフォーカス探索の
            // 対象から外れる。
            // pendingDelete / pendingFirstLogin が null に戻ればまた true になり、
            // 通常操作に戻る（確認オーバーレイはどちらも [ConfirmDialog] であり、
            // 同時に出ることはない——一方が出ている間は背後の行にフォーカスが
            // 行かないので、もう一方を起動できない）。
            //
            // 裁定87 による訂正と、この書き方をよそへ写すときの注意:
            // 継承の向きは「内側が勝つ」ではなく **外側（祖先）が勝つ**
            // （`fetchFocusProperties()` は自分から祖先へ辿って順に適用し、
            // 素の可変フィールドを上書きしていく）。さらに途中に別の
            // `FocusTarget` があるとそこで打ち切られる。つまりこの1行は
            // 「配下の canFocus = false をすべて true に塗り替える」副作用も
            // 持っており、`Modifier.focusGroup()`（= canFocus = false +
            // focusTarget）を背後に持つ画面では通常時の方向探索まで壊す。
            // ここが成立しているのは、この Column の配下に `focusGroup()` が
            // 1つも無く、名指しの `down` も無いという条件が揃っているからに
            // すぎない。`GroupEditScreen` は同じ書き方を写して実機の D-pad を
            // 2手で殺した（[GroupEditScreen] の KDoc 裁定87 の節を参照）。
            .focusProperties { canFocus = pendingDelete == null && pendingFirstLogin == null },
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
                // 裁定84（fix8）: 最初の行だけでなく全行に安定したキーで
                // FocusRequester を紐付けておく。ConfirmDialog を閉じたあとに
                // 「長押しした行」や「削除で詰めたあとに同じ位置へ来た行」へ
                // プログラムからフォーカスを戻すには、対象がどの行であっても
                // 引ける必要があるため（Modifier.focusRequester を付けるだけでは
                // 自動でフォーカスが移ることはなく、requestFocus() を呼んで
                // 初めて効くので、通常時のフォーカス移動やタブ順には影響しない）。
                val key = row.focusKey()
                val focusModifier =
                    if (key != null) Modifier.focusRequester(focusRequesterFor(key)) else Modifier

                when (row) {
                    is HomeRow.GroupRow -> GroupCard(
                        row = row,
                        modifier = focusModifier,
                        onLongPressConsumed = onLongPressConsumed,
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
                        onLongPressConsumed = onLongPressConsumed,
                        onEdit = { onNavigate(TvScreen.EditProfile(row.uuid)) },
                        onDelete = {
                            statusMessage = null
                            pendingDelete = row
                        },
                        onFirstLogin = {
                            // 文面を組み立てられないなら何もしない。この操作は
                            // HomeRows.firstLoginActionLabel が非 null のときしか
                            // 画面に出ないので通常は成立するが、確認を出す前に
                            // 文面と経路グループを確定させておく（オーバーレイ側で
                            // null になりうると、背後がフォーカス不可のまま
                            // 何も描かれない＝D-pad が死ぬ状態を作れてしまう）。
                            val group = row.firstLoginGroup
                            val message = HomeRows.firstLoginConfirmation(row)
                            if (group != null && message != null) {
                                statusMessage = null
                                pendingFirstLogin = PendingFirstLogin(row, group, message)
                            }
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
                // 裁定84（fix8）: 削除実行では対象行が一覧から消えるので、
                // 「詰めたあとに同じ位置へ来た行」を求めるのに削除前の並びが要る。
                // rows/focusableKeys は reloadToken++ で次のコンポーズ時に
                // 作り直されてしまうので、消える前のいま captured しておく。
                val keysBeforeDelete = focusableKeys
                val deletedKey = target.focusKey()
                pendingDelete = null
                statusMessage = if (deleted) {
                    null
                } else {
                    "「${target.name}」の削除に失敗しました。既に削除されている可能性があります。"
                }
                reloadToken++
                if (deletedKey != null) {
                    focusRestoreTokenSeq++
                    focusRestoreRequest = FocusRestoreRequest.AfterDelete(
                        keysBeforeDelete = keysBeforeDelete,
                        deletedKey = deletedKey,
                        token = focusRestoreTokenSeq,
                    )
                }
            },
            onDismiss = {
                // 裁定84（fix8）: キャンセル/BACK では一覧は変わらないので、
                // 長押しした行そのものへフォーカスを戻す。
                val key = target.focusKey()
                pendingDelete = null
                if (key != null) {
                    focusRestoreTokenSeq++
                    focusRestoreRequest = FocusRestoreRequest.ToRow(key, focusRestoreTokenSeq)
                }
            },
        )
    }

    pendingFirstLogin?.let { target ->
        ConfirmDialog(
            message = target.message,
            confirmLabel = "接続する",
            onConfirm = {
                // 裁定84（fix8）: 一覧は変わらないので、操作した行そのものへ
                // フォーカスを戻す（削除のキャンセルと同じ経路）。
                val key = target.row.focusKey()
                pendingFirstLogin = null
                // **この操作そのもの**で行の操作を押せなくする（接続の節目の到着を
                // 待たない）。待つと、認証ダイアログが出て入力している最中も
                // 「初回ログイン」が押せる状態のままになり、二度押しが裁定95 の
                // cancelActiveDialog() を通してそのダイアログを畳む
                // （＝このブランチが直した症状の再現）。
                firstLoginAttemptSeq++
                firstLoginAttempt = FirstLoginAttempt(
                    uuid = target.row.uuid,
                    groupId = target.group.id,
                    startedAtRecheckKey = firstLoginRecheckKey,
                    token = firstLoginAttemptSeq,
                )
                // 既存の有人接続と同じ入口（ACTION_CONNECT_GROUP）に、どの
                // メンバーから始めるかを足して渡すだけ。新しいシグナリング経路は
                // 作らない（uuid → 添字の解決は FailoverService 側）。
                FailoverService.connectGroup(
                    context,
                    target.group.id,
                    memberUuid = target.row.uuid,
                )
                if (key != null) {
                    focusRestoreTokenSeq++
                    focusRestoreRequest = FocusRestoreRequest.ToRow(key, focusRestoreTokenSeq)
                }
            },
            onDismiss = {
                val key = target.row.focusKey()
                pendingFirstLogin = null
                if (key != null) {
                    focusRestoreTokenSeq++
                    focusRestoreRequest = FocusRestoreRequest.ToRow(key, focusRestoreTokenSeq)
                }
            },
        )
    }

    // 「初回ログイン中」の印を降ろす（決着した試行を捨てる）。降ろす条件は
    // HomeRows.firstLoginAttemptInFlight にあり、**成功と失敗の両方で降りる**
    // （成功だけで降りる印は、失敗したときに再試行の手段を奪うので欠陥より悪い）。
    // ここで state そのものを捨てておくのは、鍵が将来また同じ値に戻ったときに
    // 古い試行が「進行中」として復活しないようにするため。
    // 判定（needsFirstLogin）は引き直さない——見ているのは publish された状態だけ。
    LaunchedEffect(firstLoginAttempt?.token, firstLoginRecheckKey) {
        val attempt = firstLoginAttempt ?: return@LaunchedEffect
        val inFlight = HomeRows.firstLoginAttemptInFlight(
            attempt.groupId,
            attempt.startedAtRecheckKey,
            failoverState,
        )
        if (!inFlight) firstLoginAttempt = null
    }

    // 指示がどこにも届かなかった場合の取り下げ。`FailoverService` は、画面が読んだ
    // グループ一覧が古くて添字を解決できないと**何も dispatch しない**ので、
    // 接続の節目が1つも動かない＝上の効果は降ろす契機を得られない。その1点を
    // 時間で回収する: 鍵がまったく動かないまま FIRST_LOGIN_STALL_MS 過ぎたら
    // 印を降ろし、利用者が操作をもう一度押せる状態に戻す。鍵が動いていれば
    // （＝ダイヤルは始まっている。利用者が入力中の窓を含む）何もしない。
    LaunchedEffect(firstLoginAttempt?.token) {
        val attempt = firstLoginAttempt ?: return@LaunchedEffect
        delay(FIRST_LOGIN_STALL_MS)
        if (firstLoginAttempt?.token == attempt.token &&
            HomeRows.firstLoginRecheckKey(failoverState) == attempt.startedAtRecheckKey
        ) {
            firstLoginAttempt = null
        }
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
        val key = focusableKeys.firstOrNull() ?: return@LaunchedEffect
        focusRequesterFor(key).requestFocusRetrying()
    }

    // 裁定84（fix8）: ConfirmDialog を閉じたという一度きりの出来事（onConfirm /
    // onDismiss が focusRestoreRequest をセットしたとき）にだけ反応してフォーカスを
    // 復帰させる。キーは focusRestoreRequest そのもの（data class の構造的等価）
    // ではなく token（Int）にしている — 「rows が変わるたびに再実行」（裁定60 が
    // 禁じる形）を確実に避けるには、rows を直接キーに使わない一度きりのトリガーが
    // 要る。
    //
    // AfterDelete のとき、対象キーの算出に使う focusableKeys はこの効果が実行される
    // 時点のもの（＝削除後の rows から作り直された最新のもの）を参照する。
    // pendingDelete=null と reloadToken++ は同じイベントハンドラの中で一緒に
    // 更新されるため、その1回の再コンポーズで rows・focusableKeys とも削除後の
    // 内容へ更新されてからこの LaunchedEffect が走る（Compose はエフェクトを
    // 該当フレームの再コンポーズ確定後に実行する）。
    LaunchedEffect(focusRestoreRequest?.token) {
        val request = focusRestoreRequest ?: return@LaunchedEffect
        val targetKey = when (request) {
            is FocusRestoreRequest.ToRow -> request.key
            is FocusRestoreRequest.AfterDelete -> HomeRows.targetKeyAfterConfirmedDelete(
                focusableKeysBeforeDelete = request.keysBeforeDelete,
                deletedRowKey = request.deletedKey,
                focusableKeysAfterDelete = focusableKeys,
            )
        }
        if (targetKey != null) {
            focusRequesterFor(targetKey).requestFocusRetrying()
        }
    }
}

/**
 * 「初回ログイン」の確認オーバーレイに必要なものを、**確認を出すと決めた時点で
 * 確定させて**持ち回る入れ物。
 *
 * [message] と [group] を保持するのは、オーバーレイをコンポーズする箇所で
 * `HomeRows.firstLoginConfirmation` が null を返しうる形にしないためである。
 * 背後の画面は確認中フォーカス不可（裁定83）なので、「オーバーレイも描かれず
 * 背後も掴めない」状態を作ると D-pad が完全に死ぬ（脱出は BACK だけ）。
 */
private data class PendingFirstLogin(
    val row: HomeRow.ProfileRow,
    val group: FirstLoginGroup,
    val message: String,
)

/**
 * 確認で実行した「初回ログイン」の試行。**確認そのものが立てる**印であり、
 * 状態機械からの `Connecting` の到着は待たない——待つとその隙間に操作を
 * もう一度押せてしまい、二度目の接続指示が裁定95 の `cancelActiveDialog()` を
 * 通して入力中の認証ダイアログを畳む（`HomeRow.ProfileRow.firstLoginInProgress` の
 * KDoc）。
 *
 * [startedAtRecheckKey] は確認した時点の `HomeRows.firstLoginRecheckKey`。
 * 降ろす条件は `HomeRows.firstLoginAttemptInFlight`（成功でも失敗でも降りる）。
 * [token] は `FocusRestoreRequest` と同じ目的で、内容が同じ試行が連続しても
 * `LaunchedEffect` のキーが必ず変わるようにするためだけの値。
 *
 * **画面側だけの印なので、この画面がコンポジションから外れると消える**
 * （接続先やグループの編集へ入る、裁定84/87 の画面往復など）。それは受け入れる:
 * 戻ってきた時点で `needsFirstLogin` は引き直され、初回ログインが済んでいれば
 * 注記も操作も消え、済んでいなければ操作が出る（＝再試行できる）だけである。
 * 印を永続化すると、いつ消えるのかを別に決める必要が生まれ、状態機械の外に
 * 2つ目の真実を作ることになる。
 */
private data class FirstLoginAttempt(
    val uuid: String,
    val groupId: String,
    val startedAtRecheckKey: String,
    val token: Int,
)

/**
 * 「初回ログイン」を指示したのに接続の節目が1つも動かないとき、印を取り下げる
 * までの時間（`HomeScreen` の該当 `LaunchedEffect` 参照）。`Connecting` の
 * publish は指示から間もなく届くので、これを過ぎて鍵がまったく動いていなければ
 * 指示はどこにも届いていない。Ruling 22 の45秒より長くとってあるのは、
 * 「まだ始まっていない」と「始まっているが人が入力している」を取り違えて
 * 操作を復活させないため（取り違えると二度押しの窓が戻る）。
 */
private const val FIRST_LOGIN_STALL_MS = 60_000L

/**
 * 裁定84（fix8）: ConfirmDialog を閉じた直後に一度だけ行うフォーカス復帰の指示。
 * [token] 自体に意味は無く、同じ内容の指示が連続しても
 * `LaunchedEffect(focusRestoreRequest?.token)` が確実にそのたび再実行される
 * ようにするためだけに持たせている。
 */
private sealed interface FocusRestoreRequest {
    val token: Int

    /** キャンセル/BACK で閉じた: 長押しした行そのものへ戻す。 */
    data class ToRow(val key: String, override val token: Int) : FocusRestoreRequest

    /**
     * 削除を実行して閉じた: 対象行が一覧から消えるので、削除前の並びでの位置を
     * 削除後の並びに当てはめ直した行へ戻す
     * （[HomeRows.targetKeyAfterConfirmedDelete] 参照）。
     */
    data class AfterDelete(
        val keysBeforeDelete: List<String>,
        val deletedKey: String,
        override val token: Int,
    ) : FocusRestoreRequest
}

/**
 * 裁定57/58: VPN 許可が無いと `FailoverService` は無人で繋げず、通知を出すだけで
 * 止まる。一覧より前にここで止め、許可を取る手段を必ず提示する。
 * 初期フォーカスはここでも必ず置く。
 *
 * 裁定86（M5）: 初期フォーカスの要求を1回きりの `runCatching { requestFocus() }`
 * から共有の [requestFocusRetrying] に替えた。この画面はフォーカス可能な要素が
 * このボタン1つだけなので、要求が1回失敗するとフォーカスがどこにも無い状態に
 * なる（理由の詳細は `TvFocus.kt`）。
 */
@Composable
private fun ConsentRequiredScreen(onRequestConsent: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocusRetrying() }

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
    onLongPressConsumed: () -> Unit,
    onToggleConnection: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        onClick = onToggleConnection,
        onLongClick = withLongPressConsumed(onLongPressConsumed, onEdit),
        modifier = modifier,
    ) {
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

/**
 * 接続先の行。決定で編集、長押しで削除（裁定80 の作法で
 * [withLongPressConsumed] を必ず通す）。
 *
 * 初回ログインが必要で、かつどこかのグループに属している接続先
 * （[HomeRows.firstLoginActionLabel] が非 null）では、**同じ行の右に**
 * 「初回ログイン」のカードを1枚並べる。既存の2つの操作
 * （決定＝編集・長押し＝削除）はそのままにして、3つ目の入力の作法を
 * 発明しない: D-pad の **→** でこのカードへ移り、**決定**で確認を出す
 * （`GroupEditScreen` のメンバー行が ▲/▼/名前の3枚を横に並べ、左右で
 * 移動して決定で実行するのと同じ形。実機で確認済みの形である）。
 *
 * 決定（`onClick`）は ACTION_UP で発火するので、長押し（ACTION_DOWN 中に
 * 発火して UP が孤児になる裁定78/80 の問題）は起きない。[LongPressKeyUpFilter]
 * が関わるのは既存の長押し＝削除だけで、そちらの経路は変えていない。
 *
 * 行の [FocusRequester]（裁定84 の復帰先）は**左の本体のカード**に付けたまま
 * にしてある。確認を閉じたあとフォーカスが戻るのは行そのもの（従来と同じ位置）
 * であり、操作が増えても復帰先の意味が変わらない。
 */
@Composable
private fun ProfileCard(
    row: HomeRow.ProfileRow,
    modifier: Modifier,
    onLongPressConsumed: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFirstLogin: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            onClick = onEdit,
            onLongClick = withLongPressConsumed(onLongPressConsumed, onDelete),
            modifier = modifier,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(row.serverAddress, style = MaterialTheme.typography.bodySmall)
                HomeRows.firstLoginNotice(row)?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                }
                Text("長押しで削除", style = MaterialTheme.typography.bodySmall)
            }
        }

        HomeRows.firstLoginActionLabel(row)?.let { label ->
            Card(onClick = onFirstLogin) {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text("決定で接続", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * 裁定80: `Card` の `onLongClick` は ACTION_DOWN 中に発火し、その後に届く
 * ACTION_UP が遷移先の画面（`TvFieldRow` の編集モードや `ConfirmDialog` の
 * 既定選択）の初期フォーカス要素を誤って押してしまう
 * （[LongPressKeyUpFilter] の KDoc 参照）。TV UI で `onLongClick` を書くときは
 * 必ずこの関数を経由させ、実際の処理（[action]）の前に
 * [onLongPressConsumed] を呼ぶこと。
 *
 * 呼び出しを1か所（この関数）にまとめることで、`onLongClick = { ... }` を
 * 直接書いて呼び忘れる経路そのものを塞いでいる。将来 `HomeScreen` に
 * 長押しを増やすときは、この関数を通さない `onLongClick` は
 * コードレビューで目に留まりやすい（この関数が唯一の「正しい書き方」になる）
 * うえ、[HomeScreen] の `onLongPressConsumed` パラメータの KDoc にも
 * 同じ注意書きがある。
 */
private fun withLongPressConsumed(
    onLongPressConsumed: () -> Unit,
    action: () -> Unit,
): () -> Unit = {
    onLongPressConsumed()
    action()
}
