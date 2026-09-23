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
