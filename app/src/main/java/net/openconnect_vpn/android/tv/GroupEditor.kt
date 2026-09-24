package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverGroup

/**
 * グループのメンバー操作。すべて純関数で、元のオブジェクトは変更しない。
 * memberUuids の並び順が優先順位を表すため、移動操作の正しさが直接動作に効く。
 */
object GroupEditor {

    fun addMember(group: FailoverGroup, uuid: String): FailoverGroup =
        if (uuid in group.memberUuids) group
        else group.copy(memberUuids = group.memberUuids + uuid)

    fun removeMember(group: FailoverGroup, uuid: String): FailoverGroup =
        group.copy(memberUuids = group.memberUuids.filterNot { it == uuid })

    fun moveUp(group: FailoverGroup, uuid: String): FailoverGroup = move(group, uuid, offset = -1)

    fun moveDown(group: FailoverGroup, uuid: String): FailoverGroup = move(group, uuid, offset = 1)

    fun setAutoFailover(group: FailoverGroup, enabled: Boolean): FailoverGroup =
        group.copy(autoFailoverEnabled = enabled)

    /**
     * 裁定86（M2）: 保存できない理由の文言。保存できるなら null。
     *
     * 以前は `GroupEditScreen` の「保存」が
     * `if (group.name.isBlank() || group.memberUuids.isEmpty()) return@Button` と
     * 書いており、エラー表示もフォーカス移動も無いまま**画面が完全に無反応**に
     * なっていた（Fire TV では「アプリが固まった」と解釈される）。
     * `ProfileEditScreen` は同じ状況で `error` を出すので、画面間で挙動が
     * 食い違ってもいた。
     *
     * 判定を純関数として切り出してあるのは、文言と条件の対応を Robolectric
     * 無しで固定できるようにするため（`ProfileEditMessages` と同じ方針）。
     * 両方満たしていない場合は、利用者が上から順に直せるよう先にグループ名を
     * 案内する（画面の並びと同じ順序）。
     */
    fun saveBlockedReason(group: FailoverGroup): String? = when {
        group.name.isBlank() -> "グループ名を入力してください"
        group.memberUuids.isEmpty() -> "候補を1件以上選んでください"
        else -> null
    }

    private fun move(group: FailoverGroup, uuid: String, offset: Int): FailoverGroup {
        val index = group.memberUuids.indexOf(uuid)
        if (index < 0) return group
        val target = index + offset
        if (target < 0 || target >= group.memberUuids.size) return group
        val reordered = group.memberUuids.toMutableList()
        reordered[index] = reordered[target]
        reordered[target] = uuid
        return group.copy(memberUuids = reordered)
    }
}
