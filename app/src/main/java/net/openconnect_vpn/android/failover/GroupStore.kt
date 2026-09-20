package net.openconnect_vpn.android.failover

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * フェイルオーバーグループとプローブ宛先の永続化。
 * 実際の保存先は [KeyValueStore] の実装が決める（本番は SharedPreferences）。
 */
class GroupStore(private val store: KeyValueStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 保存済みのグループを読み込む。
     *
     * [knownProfileUuids] に無いメンバーは削除済みプロファイルとみなして除去し、
     * メンバーが1件も残らなかったグループはグループ自体を落とす。
     * 保存データが壊れていた場合は空リストを返す（起動不能を避ける）。
     */
    fun loadGroups(knownProfileUuids: Set<String>): List<FailoverGroup> {
        val raw = store.getString(KEY_GROUPS) ?: return emptyList()
        val decoded = try {
            json.decodeFromString<List<FailoverGroup>>(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        return decoded
            .map { it.copy(memberUuids = it.memberUuids.filter { uuid -> uuid in knownProfileUuids }) }
            .filter { it.memberUuids.isNotEmpty() }
    }

    fun saveGroups(groups: List<FailoverGroup>) {
        store.putString(KEY_GROUPS, json.encodeToString(groups))
    }

    fun loadProbeTarget(): ProbeTarget {
        val raw = store.getString(KEY_PROBE_TARGET) ?: return ProbeTarget()
        return try {
            json.decodeFromString<ProbeTarget>(raw)
        } catch (e: Exception) {
            ProbeTarget()
        }
    }

    fun saveProbeTarget(target: ProbeTarget) {
        store.putString(KEY_PROBE_TARGET, json.encodeToString(target))
    }

    private companion object {
        const val KEY_GROUPS = "failover_groups_v1"
        const val KEY_PROBE_TARGET = "failover_probe_target_v1"
    }
}
