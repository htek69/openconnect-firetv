package net.openconnect_vpn.android.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 */
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

        Card(onClick = {
            group = GroupEditor.setAutoFailover(group, !group.autoFailoverEnabled)
        }) {
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
            items(group.memberUuids) { uuid ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(onClick = { group = GroupEditor.moveUp(group, uuid) }) {
                        Text("▲", modifier = Modifier.padding(16.dp))
                    }
                    Card(onClick = { group = GroupEditor.moveDown(group, uuid) }) {
                        Text("▼", modifier = Modifier.padding(16.dp))
                    }
                    Card(onClick = { group = GroupEditor.removeMember(group, uuid) }) {
                        Text("${nameOf(uuid)}  （決定で除外）", modifier = Modifier.padding(16.dp))
                    }
                }
            }

            val candidates = allProfiles.filterNot { it.uuid in group.memberUuids }
            if (candidates.isNotEmpty()) {
                item {
                    Text(
                        "追加できる接続先",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            items(candidates) { profile ->
                Card(onClick = { group = GroupEditor.addMember(group, profile.uuid) }) {
                    Text("＋ ${profile.name}", modifier = Modifier.padding(16.dp))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                if (group.name.isBlank() || group.memberUuids.isEmpty()) return@Button
                val others = storedGroups.filterNot { it.id == group.id }
                groupStore.saveGroups(others + group)
                onDone()
            }) {
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
