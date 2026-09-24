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
import androidx.compose.runtime.Composable
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
 * 全滅する。そのため意図的に「最後の行の `Card` だけ」に絞っている）。
 *
 * `focusGroup()` はボタン行に残した。ボタン行に入る **前**（直前の要素側）
 * の `down` を変えているだけでボタン行自身の `focusProperties` は
 * 触っていないため、`focusGroup()` の有無は `down` の解決に影響しない。
 * `LEFT`/`RIGHT`/`UP` はこのラウンドで一切変更していないので、既定の
 * 2次元探索のまま維持される。
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

    // 裁定76/77: ボタン行への DOWN の着地先を「保存」に固定するための FocusRequester。
    val saveButtonFocus = remember { FocusRequester() }

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
            onValueChange = { group = group.copy(name = it) },
            label = "グループ名",
            modifier = Modifier.fillMaxWidth(),
            requestInitialFocus = true,
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

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
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
                    onClick = { group = GroupEditor.addMember(group, profile.uuid) },
                    modifier = downModifier,
                ) {
                    Text("＋ ${profile.name}", modifier = Modifier.padding(16.dp))
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // 裁定77: DOWN の着地先固定は、この行に入る `enter` ではなく、
            // 直前の要素（自動切替 Card / メンバー最終行 / 候補最終行のいずれか、
            // 上の分岐参照）側の `down` で行っている。`focusGroup()` は
            // LEFT/RIGHT/UP の挙動を変えないので残す。
            modifier = Modifier.focusGroup(),
        ) {
            Button(
                onClick = {
                    if (group.name.isBlank() || group.memberUuids.isEmpty()) return@Button
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
                Button(onClick = {
                    groupStore.saveGroups(storedGroups.filterNot { it.id == group.id })
                    onDone()
                }) {
                    Text("グループを削除")
                }
            }
        }
    }
}
