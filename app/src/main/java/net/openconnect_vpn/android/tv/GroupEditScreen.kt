package net.openconnect_vpn.android.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import net.openconnect_vpn.android.failover.GroupStore
import java.util.UUID

/**
 * フェイルオーバーグループの作成・編集。
 * メンバーの並び順がそのまま優先順位になるため、上下移動を明示的に操作させる。
 *
 * 並び替えの操作モデル: 各メンバー行に ▲（優先度を上げる）・▼（優先度を下げる）・
 * 名前（決定で除外）の3枚の `Card` を横に並べる。D-pad の上下でメンバー行同士を
 * 移動し、左右でその行内の3枚（▲/▼/名前）にフォーカスを移し、決定（center）で
 * 実行する——4方向の矢印キーと決定ボタンだけで完結し、かつ画面上に▲▼の記号その
 * ものが常時見えているので、操作を試す前から「上下移動ができる」ことが伝わる
 * （マニュアル無しで発見できることを優先し、長押しや複合入力には頼らない）。
 * 実際の並び替えは Task 2 の [GroupEditor.moveUp] / [moveDown] にそのまま委譲する。
 *
 * 裁定72（Task 7）: グループ名欄は素の `OutlinedTextField` ではなく Task 6 の
 * [TvFieldRow] を使う。[TvFieldRow] の KDoc（裁定70/71）にある通り、フォーカスが
 * 乗った `BasicTextField` 系はソフトキーボードを要求し、Fire TV ではそれが別
 * ウィンドウとしてアプリの上に乗って D-pad を独占する（この画面のどのボタンにも
 * 到達できなくなる）。`ProfileEditScreen` と同じ理由でここでも同じ部品を再利用し、
 * 2つ目のテキスト入力実装を作らない。画面全体で初期フォーカスを持つのはこの行
 * だけ（[TvFieldRow] の `requestInitialFocus`）。
 *
 * 保存は [groupStore] （`GroupStore.saveGroups`）を経由する以外の経路を持たない。
 * `FailoverService` は `SharedPreferences` の変更リスナで `GroupStore` の保存
 * キーを監視し、変わればグループ定義を読み直す（計画1 Task 6.5）ため、自動切替の
 * ON/OFF を含む変更は保存するだけで稼働中の接続にも反映される。別途シグナリング
 * は行わない。
 *
 * 裁定76（実機で再現した阻害欠陥、`ProfileEditScreen` と共通）: 最後の入力行
 * （候補リストの末尾の項目）から `DPAD_DOWN` でボタン行に入ると、既定の
 * 2次元フォーカス探索では「キャンセル」に着地していた。このスクリーンは
 * ボタンが3つ（保存・キャンセル・グループを削除、削除は編集時のみ）ある
 * ため、[ProfileEditScreen] と同じ理由で余計に不安定になりうる。最初の対策
 * （`Modifier.focusGroup()` + `Modifier.focusProperties { enter = ... }`）は
 * 実機では効かなかった（裁定77、[ProfileEditScreen] と共通の原因）。
 *
 * 裁定77（実機で計測、`ProfileEditScreen` と共通の原因・対策）: `enter` は
 * `FocusDirection.Enter`（明示的な「グループへ入れ」という要求）にしか
 * 参照されず、上下左右の2次元探索がグループ内の要素を直接選ぶ経路
 * （`DOWN` はこれ）では一切参照されない。詳細な原因説明は
 * [ProfileEditScreen] の KDoc の裁定77の節を参照。
 *
 * 対策も同じ発想（ボタン行の**直前の要素**の `down` を名指しで固定する）
 * だが、このスクリーンでは「直前の要素」が [TvFieldRow] 1個ではなく、
 * `LazyColumn` 内の**可変長の候補リストの末尾**になる点が異なる。
 * 具体的には表示順（グループ名 → 自動切替 → メンバー行 → 候補行 →
 * ボタン行）のうち、ボタン行の直前に実際に描画される要素は
 * 状態によって変わる:
 *
 * - 候補（追加できる接続先）が1件以上あれば、候補リストの**最後の項目**の
 *   `Card` が直前の要素。
 * - 候補が0件でメンバーが1件以上あれば、メンバーリストの**最後の行**の
 *   3枚の `Card`（▲/▼/名前）がいずれも直前の要素になりうる
 *   （最後のメンバー行のどの列にフォーカスがあっても `DOWN` で
 *   ボタン行に入る可能性があるため、3枚とも対象にする）。
 * - 候補もメンバーも0件（`LazyColumn` が空）なら、その手前の「自動切替」
 *   `Card` が直前の要素。
 *
 * これらそれぞれに `Modifier.focusProperties { down = saveButtonFocus }` を
 * 個別に（＝該当する行の `Card` だけに）適用する。`items`/`itemsIndexed` の
 * 各行は独立した `Modifier` チェーンを持つため、最後の行にだけ付けても
 * それより前の行の `DOWN`（次の行へ移動する既定の挙動）には影響しない
 * （逆に `LazyColumn` や `Column` 全体に `focusProperties` を付けると、
 * 内側の全フォーカス対象がその `down` を継承してしまい、行間移動が
 * 全滅する。そのため意図的に「最後の行の `Card` だけ」に絞っている。
 * この警告を裁定86 で自分で踏み抜いた——裁定87 の節を必ず読むこと）。
 *
 * `focusGroup()` はボタン行に残した。ボタン行に入る **前**（直前の要素側）
 * の `down` を変えているだけでボタン行自身の `focusProperties` は
 * 触っていないため、`focusGroup()` の有無は `down` の解決に影響しない。
 * `LEFT`/`RIGHT`/`UP` はこのラウンドで一切変更していないので、既定の
 * 2次元探索のまま維持される。
 *
 * 裁定82（実機で発見）: 外側の `Column` は `fillMaxSize()` でスクロールを持たず、
 * 候補・メンバーを並べる `LazyColumn` も高さの制約（`weight`）を持たなかった。
 * 件数が増えると `LazyColumn` が縦を食い尽くし、その下のボタン行
 * （保存・キャンセル・グループを削除）が可視領域の外へ押し出される
 * （実機の uiautomator dump で3つのボタンの文字が1つも現れないことを確認）。
 * `LazyColumn` に `Modifier.weight(1f)` を与えて残り高さに収め、内部で
 * スクロールさせることで、ボタン行は常に可視領域内の下端に固定される
 * （他の非 weight の兄弟——見出し・グループ名行・自動切替・「候補」見出し・
 * ボタン行自身——が先に必要な高さを確保し、`LazyColumn` には残りだけが渡る）。
 * 裁定77 の DOWN 着地先固定はこの変更で崩れない: `weight`/スクロールは高さの
 * 配分を変えるだけで、フォーカス探索が使う各要素の `focusProperties { down = ... }`
 * には触れていない。
 *
 * 裁定86（M2）: 「保存」は条件を満たさないとき `return@Button` するだけで、
 * エラー表示もフォーカス移動も無く**画面が完全に無反応**になっていた
 * （Fire TV では「固まった」と解釈される）。理由を [GroupEditor.saveBlockedReason]
 * の文言で出し、`ProfileEditScreen` の `error` と同じ位置・同じスタイルに揃えた。
 *
 * 裁定86（M3）: 「グループを削除」が決定1回で即実行されていた（仕様書 §9 と R2 は
 * 「削除は確認1枚」を規定しており、接続先の削除には既に [ConfirmDialog] が入って
 * いた）。裁定77 により候補行からの `DOWN` は必ず「保存」に着地するので、そこから
 * `RIGHT` を1回多く押すだけで並び順と自動切替の設定が取り消し不能に消えていた。
 * 確認は裁定83 の**画面内オーバーレイ**（[ConfirmDialog]）で挟む。別 Window の
 * `Dialog` は長押しの孤児 UP に誤爆されることが実機で確認済みなので使わない。
 *
 * 裁定87（実機で計測した回帰。**この画面に `focusProperties` を足すときは必ず
 * ここを読むこと**）: 裁定86（M3）の最初の実装は、`HomeScreen`（裁定83）を
 * そのまま真似て外側の `Column` に `.focusProperties { canFocus = !pendingDelete }`
 * を足していた。実機ではそれだけで、**確認オーバーレイを出していない通常状態の
 * D-pad が2手で死んだ**（グループ名 → `DOWN` で自動切替 → もう1回の `DOWN` で
 * `focused="true"` のノードが0個になり、以後どのキーも効かない）。編集・作成の
 * 両方、メンバー/候補の件数に関わらず再現し、直前の `b60606e` / `78dbee8` では
 * 同じ操作でボタン行まで到達できていた。
 *
 * この1行が何をしていたか（`compose-ui` 1.7.6 のバイトコードで確認した事実）:
 *
 * - `FocusTargetNode.fetchFocusProperties()` は
 *   `visitSelfAndAncestors(NodeKind.FocusProperties, untilType = NodeKind.FocusTarget)`
 *   で**自分から上へ**辿り、見つけた `FocusPropertiesModifierNode` を順に
 *   `applyFocusProperties` に通す。`FocusPropertiesImpl` は「設定済みか」を
 *   持たない素の可変フィールドなので、**後に適用されたものが勝つ**
 *   ——つまり**外側（祖先）の指定が内側の指定を上書きする**。
 *   [TvFieldRow] の裁定77 の KDoc にあった「近い方が優先」は逆であり、誤りだった
 *   （そちらも訂正済み）。
 * - `Modifier.focusGroup()` は `focusProperties { canFocus = false }.focusTarget()`
 *   そのものである（`FocusableKt.focusGroup`）。
 *
 * 帰結として、外側 `Column` の `canFocus = true`（`pendingDelete == false` の
 * 通常状態）は**ボタン行の `focusGroup()` の `canFocus = false` を打ち消し**、
 * ボタン行自身が2次元探索の候補ノードに化けていた。ボタン行の `Row` には
 * セマンティクスが無いのでフォーカスが乗っても `uiautomator` には
 * `focused="true"` が1つも出ない——観測された「フォーカス消失」の像と一致する。
 * 逆に `pendingDelete == true` のときは、ボタン行の `focusTarget` が祖先探索を
 * 止めるため**中の3つの `Button` には `canFocus = false` が届かず**、
 * 遮断そのものも不完全だった（裁定83 が `HomeScreen` で成立していたのは、
 * あちらの背後に `focusGroup()` が1つも無く、名指しの `down` も持たないため）。
 *
 * 対策（`focusProperties` の継承に頼らない形）: **オーバーレイを出している間は
 * 背後のツリーそのものを組み立てない**。`pendingDelete` のときは
 * [ConfirmDialog] だけをコンポーズし、この画面の本体（外側 `Column` 以下）は
 * `else` 側に置く。効果は2つある:
 *
 * 1. 通常状態（`pendingDelete == false`）のフォーカスツリーが、実機で計測済みの
 *    `b60606e` と**完全に同一**に戻る。祖先側に `FocusProperties` ノードが1つも
 *    無いので、裁定77 の各行の `down` も、ボタン行の `focusGroup()` も、
 *    上書きされる余地が無い。
 * 2. 表示中は背後の `Button`/`Card` がそもそも存在しないので、遮断は
 *    Compose のフォーカス探索の細部に一切依存せず成立する（裁定86 M3 の要件）。
 *
 * 代償は「オーバーレイの暗幕の裏に背後の画面が見えない」ことだけで、
 * [ConfirmDialog] 自身は不透明な `surface` 背景を持つので読める。
 *
 * 付随して2点:
 *
 * - 候補リストの `LazyListState` は [GroupEditScreen] 側に持ち上げた。背後の
 *   ツリーを組み立て直す形にしたので、持ち上げないと確認を取り消したあとに
 *   スクロール位置が先頭へ戻ってしまう。
 * - [TvFieldRow] の `requestInitialFocus` は「一度も確認を閉じていない」間だけ
 *   true にする（`focusRestoreToken == 0`）。本体を組み立て直すと
 *   [TvFieldRow] の1回きりの初期フォーカス要求が再発火し、下の
 *   裁定84 の「グループを削除」への復帰と競合して着地先が不定になるため。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GroupEditScreen(
    profiles: ProfileRepository,
    groupStore: GroupStore,
    editingGroupId: String?,
    onDone: () -> Unit,
) {
    val allProfiles = remember { profiles.list() }
    val knownUuids = remember(allProfiles) { allProfiles.map { it.uuid }.toSet() }
    val storedGroups = remember { groupStore.loadGroups(knownUuids) }

    var group by remember {
        mutableStateOf(
            storedGroups.firstOrNull { it.id == editingGroupId }
                ?: FailoverGroup(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    memberUuids = emptyList(),
                    autoFailoverEnabled = true,
                    config = FailoverConfig(),
                ),
        )
    }

    fun nameOf(uuid: String): String =
        allProfiles.firstOrNull { it.uuid == uuid }?.name ?: uuid

    // 裁定77: ボタン行（下の Row）の直前に実際に描画される要素は状態に応じて
    // 変わる（候補リストの末尾 / メンバーリストの末尾 / 自動切替 Card のいずれか）。
    // それぞれに DOWN の着地先を固定するため、ここで候補リストを LazyColumn の
    // 外側で先に確定させ、どの分岐が「直前の要素」になるかを判定できるようにする。
    val candidates = remember(allProfiles, group.memberUuids) {
        allProfiles.filterNot { it.uuid in group.memberUuids }
    }
    val hasListItems = group.memberUuids.isNotEmpty() || candidates.isNotEmpty()

    // 裁定87: 確認オーバーレイ表示中は本体のツリーを組み立てない（下の if/else）。
    // そのため LazyColumn ごと作り直されるので、スクロール位置はここに持ち上げて
    // おかないと確認を取り消したあとに先頭へ戻ってしまう。
    val listState = rememberLazyListState()

    // 裁定76/77: ボタン行への DOWN の着地先を「保存」に固定するための FocusRequester。
    val saveButtonFocus = remember { FocusRequester() }

    // 裁定86（M2）: 保存できない理由。無反応のまま押させ続けないために出す
    // （文言と条件は GroupEditor.saveBlockedReason）。
    var error by remember { mutableStateOf<String?>(null) }

    // 裁定86（M3）: グループの削除の確認。仕様書 §9 と R2 が「削除は確認1枚」を
    // 規定しており、接続先の削除には ConfirmDialog が入っているのにグループの
    // 削除だけが決定1回で即実行されていた（裁定77 により候補行からの DOWN は
    // 必ず「保存」に着地するので、そこから RIGHT を1回多く押すだけで、
    // 並び順と自動切替の設定が取り消し不能に消える）。
    var pendingDelete by remember { mutableStateOf(false) }

    // 裁定86（M3 / 裁定84 と同じ形）: 確認を取り消して閉じたあと、フォーカスを
    // 「グループを削除」ボタンへ戻すための一度きりのトリガー。0 は初期値
    // （＝まだ一度も閉じていない）なので何もしない。裁定87 以降、オーバーレイ
    // 表示中は背後のツリーそのものが無くなるので、閉じたときに誰かが
    // requestFocus() しない限りフォーカスはどこにも無いままになる
    // （requestFocusRetrying はツリーの再アタッチを数フレーム待てる）。
    val deleteButtonFocus = remember { FocusRequester() }
    var focusRestoreToken by remember { mutableStateOf(0) }
    LaunchedEffect(focusRestoreToken) {
        if (focusRestoreToken == 0) return@LaunchedEffect
        deleteButtonFocus.requestFocusRetrying()
    }

    if (pendingDelete) {
        // 裁定87: 背後（下の else 側）は組み立てない。これが「オーバーレイ表示中に
        // 背後へフォーカスが漏れない」ことの根拠であり、外側 Column への
        // focusProperties（＝裁定86 で D-pad を殺した継承）はもう使わない。
        // 裁定83 の画面内オーバーレイはそのまま使う（別 Window の Dialog は実機で
        // 長押しの孤児 UP に誤爆されることが確認済みなので使わない）。
        ConfirmDialog(
            message = "グループ「${group.name}」を削除しますか？\n\n" +
                "候補の並び順と自動切替の設定が消えます。" +
                "接続先そのものは削除されません。",
            onConfirm = {
                groupStore.saveGroups(storedGroups.filterNot { it.id == group.id })
                // 削除したらこの画面に留まる意味が無いので Home へ戻る。
                // フォーカス復帰は Home 側（裁定84）が行うため、ここでは
                // トークンを進めない。
                pendingDelete = false
                onDone()
            },
            onDismiss = {
                pendingDelete = false
                focusRestoreToken++
            },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (editingGroupId == null) "グループを作成" else "グループを編集",
                style = MaterialTheme.typography.headlineMedium,
            )

            // 裁定72: 画面全体でここだけ requestInitialFocus = true（TvFieldRow の
            // 契約どおり、複数行が初期フォーカスを取り合わないようにする）。
            TvFieldRow(
                value = group.name,
                // 裁定86（M2）: 入力し直したらエラーは消す（ProfileEditScreen と同じ）。
                onValueChange = { group = group.copy(name = it); error = null },
                label = "グループ名",
                modifier = Modifier.fillMaxWidth(),
                // 裁定87: 確認を一度でも閉じたあとは、この行に初期フォーカスを
                // 取らせない。上の LaunchedEffect（裁定84）が「グループを削除」へ
                // 戻す要求を出しており、TvFieldRow の1回きりの初期フォーカス要求が
                // 同じフレームで競合すると着地先が不定になる。
                requestInitialFocus = focusRestoreToken == 0,
            )

            Card(
                onClick = {
                    group = GroupEditor.setAutoFailover(group, !group.autoFailoverEnabled)
                },
                // 裁定77: メンバーも候補も0件のとき、この Card がボタン行の直前の
                // 要素になる。その場合だけ DOWN の着地先を「保存」に固定する。
                modifier = if (!hasListItems) {
                    Modifier.focusProperties { down = saveButtonFocus }
                } else {
                    Modifier
                },
            ) {
                Text(
                    if (group.autoFailoverEnabled) "自動切替: ON" else "自動切替: OFF",
                    modifier = Modifier.padding(20.dp),
                )
            }

            Text(
                "候補（上が優先。▲▼ で並び替え、決定で除外）",
                style = MaterialTheme.typography.titleSmall,
            )

            // 裁定82（実機で発見）: この Column は fillMaxSize() でスクロールを
            // 持たず、この LazyColumn も高さの制約が無かったため、メンバーと候補が
            // 増えると LazyColumn が縦を食い尽くし、その下のボタン行（保存・
            // キャンセル・グループを削除）が可視領域の外へ押し出されていた
            // （実機で uiautomator dump に3つのボタンの文字が1つも現れないことを
            // 確認）。Modifier.weight(1f) を与えて残り高さちょうどに収め、内部で
            // スクロールさせることで、外側の Column が非 weight の子（見出し・
            // グループ名行・自動切替・「候補」見出し・ボタン行）に必要な高さを
            // 先に確保し、この LazyColumn には残りだけを渡す。結果、件数に
            // 関わらずボタン行は常に可視領域内の下端に固定される。
            LazyColumn(
                // 裁定87: state は GroupEditScreen 側に持ち上げてある（上のコメント）。
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val lastMemberIndex = group.memberUuids.lastIndex
                itemsIndexed(group.memberUuids) { index, uuid ->
                    // 裁定77: 候補が0件で、かつこれがメンバーリストの最後の行なら、
                    // この行のどの列（▲/▼/名前）から DOWN しても「保存」に固定する
                    // （最後の行のどこにフォーカスがあるかは利用者の操作次第なので、
                    // 3枚とも対象にする）。
                    val isLastRowBeforeButtons = candidates.isEmpty() && index == lastMemberIndex
                    val downModifier = if (isLastRowBeforeButtons) {
                        Modifier.focusProperties { down = saveButtonFocus }
                    } else {
                        Modifier
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(
                            onClick = { group = GroupEditor.moveUp(group, uuid) },
                            modifier = downModifier,
                        ) {
                            Text("▲", modifier = Modifier.padding(16.dp))
                        }
                        Card(
                            onClick = { group = GroupEditor.moveDown(group, uuid) },
                            modifier = downModifier,
                        ) {
                            Text("▼", modifier = Modifier.padding(16.dp))
                        }
                        Card(
                            onClick = { group = GroupEditor.removeMember(group, uuid) },
                            modifier = downModifier,
                        ) {
                            Text("${nameOf(uuid)}  （決定で除外）", modifier = Modifier.padding(16.dp))
                        }
                    }
                }

                if (candidates.isNotEmpty()) {
                    item {
                        Text(
                            "追加できる接続先",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                val lastCandidateIndex = candidates.lastIndex
                itemsIndexed(candidates) { index, profile ->
                    // 裁定77: 候補リストの最後の項目なら、ボタン行の直前の要素として
                    // DOWN の着地先を「保存」に固定する。
                    val downModifier = if (index == lastCandidateIndex) {
                        Modifier.focusProperties { down = saveButtonFocus }
                    } else {
                        Modifier
                    }
                    Card(
                        // 裁定86（M2）: 候補を足したらエラーは消す。
                        onClick = { group = GroupEditor.addMember(group, profile.uuid); error = null },
                        modifier = downModifier,
                    ) {
                        Text("＋ ${profile.name}", modifier = Modifier.padding(16.dp))
                    }
                }
            }

            // 裁定86（M2）: 保存できない理由をここに出す（ProfileEditScreen の
            // `error` と同じ位置・同じスタイルに揃える）。
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                // 裁定77: DOWN の着地先固定は、この行に入る `enter` ではなく、
                // 直前の要素（自動切替 Card / メンバー最終行 / 候補最終行のいずれか、
                // 上の分岐参照）側の `down` で行っている。`focusGroup()` は
                // LEFT/RIGHT/UP の挙動を変えないので残す。
                // 裁定87: この `focusGroup()` の canFocus = false を、外側 Column に
                // 足した `focusProperties` が（祖先が勝つため）打ち消していたのが
                // 回帰の正体だった。祖先側に `focusProperties` を足してはならない。
                modifier = Modifier.focusGroup(),
            ) {
                Button(
                    onClick = {
                        // 裁定86（M2）: 以前はここが `return@Button` だけで、
                        // エラー表示もフォーカス移動も無く画面が完全に無反応に
                        // なっていた。理由を出す。
                        val blocked = GroupEditor.saveBlockedReason(group)
                        if (blocked != null) {
                            error = blocked
                            return@Button
                        }
                        val others = storedGroups.filterNot { it.id == group.id }
                        groupStore.saveGroups(others + group)
                        onDone()
                    },
                    // 裁定77: このボタンが、直前の要素側に付けた
                    // `focusProperties { down = saveButtonFocus }` の着地先になる。
                    modifier = Modifier.focusRequester(saveButtonFocus),
                ) {
                    Text("保存")
                }
                Button(onClick = onDone) {
                    Text("キャンセル")
                }
                if (editingGroupId != null) {
                    Button(
                        // 裁定86（M3）: 決定1回で即削除するのをやめ、HomeScreen と
                        // 同じ ConfirmDialog を1枚挟む（仕様書 §9 / R2）。
                        onClick = {
                            error = null
                            pendingDelete = true
                        },
                        modifier = Modifier.focusRequester(deleteButtonFocus),
                    ) {
                        Text("グループを削除")
                    }
                }
            }
        }
    }
}
