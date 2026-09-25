package net.openconnect_vpn.android.tv

import net.openconnect_vpn.android.failover.FailoverConfig
import net.openconnect_vpn.android.failover.FailoverGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditorTest {

    private fun group(vararg members: String) = FailoverGroup(
        id = "g1",
        name = "自宅優先",
        memberUuids = members.toList(),
        autoFailoverEnabled = true,
        config = FailoverConfig(),
    )

    @Test
    fun `メンバーを末尾に追加する`() {
        val result = GroupEditor.addMember(group("a", "b"), "c")
        assertEquals(listOf("a", "b", "c"), result.memberUuids)
    }

    @Test
    fun `既に居るメンバーは重複追加しない`() {
        val result = GroupEditor.addMember(group("a", "b"), "a")
        assertEquals(listOf("a", "b"), result.memberUuids)
    }

    @Test
    fun `メンバーを削除する`() {
        val result = GroupEditor.removeMember(group("a", "b", "c"), "b")
        assertEquals(listOf("a", "c"), result.memberUuids)
    }

    @Test
    fun `居ないメンバーの削除は何もしない`() {
        val result = GroupEditor.removeMember(group("a", "b"), "z")
        assertEquals(listOf("a", "b"), result.memberUuids)
    }

    @Test
    fun `優先順位を1つ上げる`() {
        val result = GroupEditor.moveUp(group("a", "b", "c"), "b")
        assertEquals(listOf("b", "a", "c"), result.memberUuids)
    }

    @Test
    fun `先頭のメンバーはこれ以上上げられない`() {
        val result = GroupEditor.moveUp(group("a", "b", "c"), "a")
        assertEquals(listOf("a", "b", "c"), result.memberUuids)
    }

    @Test
    fun `優先順位を1つ下げる`() {
        val result = GroupEditor.moveDown(group("a", "b", "c"), "b")
        assertEquals(listOf("a", "c", "b"), result.memberUuids)
    }

    @Test
    fun `末尾のメンバーはこれ以上下げられない`() {
        val result = GroupEditor.moveDown(group("a", "b", "c"), "c")
        assertEquals(listOf("a", "b", "c"), result.memberUuids)
    }

    @Test
    fun `居ないメンバーの移動は何もしない`() {
        assertEquals(listOf("a", "b"), GroupEditor.moveUp(group("a", "b"), "z").memberUuids)
        assertEquals(listOf("a", "b"), GroupEditor.moveDown(group("a", "b"), "z").memberUuids)
    }

    @Test
    fun `自動切替を切り替える`() {
        val off = GroupEditor.setAutoFailover(group("a"), enabled = false)
        assertFalse(off.autoFailoverEnabled)

        val on = GroupEditor.setAutoFailover(off, enabled = true)
        assertTrue(on.autoFailoverEnabled)
    }

    @Test
    fun `操作してもグループの id と名前は変わらない`() {
        val result = GroupEditor.moveDown(GroupEditor.addMember(group("a"), "b"), "a")
        assertEquals("g1", result.id)
        assertEquals("自宅優先", result.name)
    }

    @Test
    fun `単一メンバーの上げ下げは何もしない`() {
        assertEquals(listOf("a"), GroupEditor.moveUp(group("a"), "a").memberUuids)
        assertEquals(listOf("a"), GroupEditor.moveDown(group("a"), "a").memberUuids)
    }

    @Test
    fun `空グループへの追加・削除・移動は例外を投げない`() {
        val empty = group()
        assertEquals(listOf("a"), GroupEditor.addMember(empty, "a").memberUuids)
        assertEquals(emptyList<String>(), GroupEditor.removeMember(empty, "a").memberUuids)
        assertEquals(emptyList<String>(), GroupEditor.moveUp(empty, "a").memberUuids)
        assertEquals(emptyList<String>(), GroupEditor.moveDown(empty, "a").memberUuids)
    }

    @Test
    fun `メンバー操作は config と autoFailoverEnabled を変えない`() {
        // 既定値ではない FailoverConfig と autoFailoverEnabled = false を使う。
        // 既定値のままだと「入力を保った」のか「たまたま既定値と一致した」のか
        // テストからは区別できず、copy() でフィールドを取りこぼしていても
        // 偶然グリーンになりかねない。
        val customConfig = FailoverConfig(
            probeIntervalSec = 99,
            probeTimeoutMs = 1_234,
            failureThreshold = 7,
            graceAfterConnectSec = 3,
            connectTimeoutSec = 12,
        )
        val source = FailoverGroup(
            id = "g1",
            name = "自宅優先",
            memberUuids = listOf("a", "b"),
            autoFailoverEnabled = false,
            config = customConfig,
        )

        val afterAdd = GroupEditor.addMember(source, "c")
        assertEquals(customConfig, afterAdd.config)
        assertFalse(afterAdd.autoFailoverEnabled)

        val afterRemove = GroupEditor.removeMember(source, "a")
        assertEquals(customConfig, afterRemove.config)
        assertFalse(afterRemove.autoFailoverEnabled)

        val afterMoveUp = GroupEditor.moveUp(source, "b")
        assertEquals(customConfig, afterMoveUp.config)
        assertFalse(afterMoveUp.autoFailoverEnabled)

        val afterMoveDown = GroupEditor.moveDown(source, "a")
        assertEquals(customConfig, afterMoveDown.config)
        assertFalse(afterMoveDown.autoFailoverEnabled)
    }

    // --- 裁定86（M2）: 保存できない理由の文言 ---

    @Test
    fun `名前と候補が揃っていれば保存を妨げない`() {
        assertNull(GroupEditor.saveBlockedReason(group("a")))
    }

    @Test
    fun `グループ名が空なら理由を返す`() {
        val blocked = GroupEditor.saveBlockedReason(group("a").copy(name = ""))
        assertEquals("グループ名を入力してください", blocked)
    }

    @Test
    fun `グループ名が空白のみでも理由を返す`() {
        // isBlank なので空白だけの名前も弾く（保存すると一覧で名無しに見える）。
        assertEquals(
            "グループ名を入力してください",
            GroupEditor.saveBlockedReason(group("a").copy(name = "   ")),
        )
    }

    @Test
    fun `候補が0件なら理由を返す`() {
        assertEquals("候補を1件以上選んでください", GroupEditor.saveBlockedReason(group()))
    }

    @Test
    fun `両方満たしていないときは先にグループ名を案内する`() {
        // 画面の並び（グループ名 -> 候補）と同じ順序で直せるようにする。
        assertEquals(
            "グループ名を入力してください",
            GroupEditor.saveBlockedReason(group().copy(name = "")),
        )
    }
}
