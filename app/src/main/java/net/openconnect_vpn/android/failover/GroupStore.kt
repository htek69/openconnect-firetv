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

    /**
     * Ruling 18: プロセス kill からの復帰時にどのグループへ再接続すべきかを覚えておく。
     * ユーザーが切断した場合は null を渡して消す。未保存なら null を返す
     * （＝サービス再起動時は Idle のまま何もしない）。
     */
    fun loadActiveGroupId(): String? = store.getString(KEY_ACTIVE_GROUP_ID)

    fun saveActiveGroupId(id: String?) {
        if (id == null) {
            store.remove(KEY_ACTIVE_GROUP_ID)
        } else {
            store.putString(KEY_ACTIVE_GROUP_ID, id)
        }
    }

    /**
     * 裁定48/72: FailoverService が SharedPreferences の変更リスナで
     * 「グループまたはプローブ宛先に関わるキーが変わったか」を判定するための
     * 問い合わせ。永続化フォーマットや保存先はここで一切変えない
     * （読み取り専用の追加であり、キー文字列を外に漏らさないための窓口）。
     * `KEY_ACTIVE_GROUP_ID` はどのグループに繋ぐかの記録であって
     * グループ定義そのものではないので対象に含めない。
     */
    fun isReloadTriggerKey(key: String?): Boolean = key == KEY_GROUPS || key == KEY_PROBE_TARGET

    private companion object {
        const val KEY_GROUPS = "failover_groups_v1"
        const val KEY_PROBE_TARGET = "failover_probe_target_v1"
        const val KEY_ACTIVE_GROUP_ID = "failover_active_group_id_v1"
    }
}
