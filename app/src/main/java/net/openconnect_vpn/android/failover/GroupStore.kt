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
     *
     * 裁定74: 返す各グループの `config.probeIntervalSec` / `config.failureThreshold`
     * は、保存済みの内容に関わらず [loadProbeSchedule] の値で必ず上書きする。
     * プローブ間隔・失敗閾値はアプリ全体で1つだけ持つ設定であり（設定画面がそう
     * 見せている）、`FailoverGroup.config` に保存されている値は「最後に
     * `saveGroups` を呼んだ時点のグローバル設定のコピー」に過ぎず真実ではない
     * ——特に、この読み込みより後に設定画面でグローバル設定を変えていた場合、
     * 保存データ側はまだ古い値のままである。ここで上書きすることで、
     * `FailoverController` / `FailoverService` は今まで通り `FailoverGroup.config`
     * だけを読めばよく、変更不要になる（Task 6.5 の再読込は `groups` を
     * 読み直すだけで、この上書きも自動的に効く）。
     */
    fun loadGroups(knownProfileUuids: Set<String>): List<FailoverGroup> {
        val raw = store.getString(KEY_GROUPS) ?: return emptyList()
        val decoded = try {
            json.decodeFromString<List<FailoverGroup>>(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val schedule = loadProbeSchedule()
        return decoded
            .map { it.copy(memberUuids = it.memberUuids.filter { uuid -> uuid in knownProfileUuids }) }
            .filter { it.memberUuids.isNotEmpty() }
            .map { group ->
                group.copy(
                    config = group.config.copy(
                        probeIntervalSec = schedule.probeIntervalSec,
                        failureThreshold = schedule.failureThreshold,
                    ),
                )
            }
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
     * 裁定74: プローブ間隔・失敗閾値のグローバル設定を読み込む。
     *
     * 未保存（このキーが導入される前からある端末を含む）や壊れた JSON の場合は
     * [FailoverConfig] の既定値（30秒・3回）を返す。ゼロ等の無効値を返さないのは、
     * これがそのまま [loadGroups] 経由で `FailoverController` の判定に使われる値に
     * なるためで、0秒間隔や0回閾値は実質的に「常時ダウン扱い」を意味してしまい
     * 危険だから。この既定値は、このキーが存在しなかった以前のバージョンで
     * 実際に使われていた値（[FailoverConfig] のコンストラクタ既定値）と同じにして
     * あるので、既にグループを保存済みの端末をこの変更のある版へ更新しても
     * 挙動は変わらない。
     */
    fun loadProbeSchedule(): ProbeSchedule {
        val raw = store.getString(KEY_PROBE_SCHEDULE) ?: return defaultProbeSchedule()
        return try {
            json.decodeFromString<ProbeSchedule>(raw)
        } catch (e: Exception) {
            defaultProbeSchedule()
        }
    }

    fun saveProbeSchedule(schedule: ProbeSchedule) {
        store.putString(KEY_PROBE_SCHEDULE, json.encodeToString(schedule))
    }

    /**
     * 裁定86（H1）: 接続先（プロファイル）の集合が変わったことを、**既存の再読込
     * トリガ**（[isReloadTriggerKey]）に載せるための世代カウンタ。
     *
     * `ProfileManager.delete()` はプロファイルの XML を消すだけで既定
     * `SharedPreferences` には一切書かない（既存 Java であり変更しない）。そのため
     * 接続先を削除しても `FailoverService` の変更リスナは発火せず、エンジン側の
     * `groups` は削除済み UUID をメンバーに含んだままになり、
     * [FailoverController.reconcileCandidateIdentity] が「候補が外された」ことに
     * 気づけない——**削除済みプロファイルのトンネルが `Healthy` を名乗って
     * 残り続ける**（計画1 の欠陥13・欠陥15 と同じ「画面の言う接続先と実際の
     * トンネルが違う」事故）。
     *
     * ここで既定 prefs のキーを1つ進めることで、`ProfileRepository` の
     * 作成・削除が**そのまま既存の再読込経路**（`prefsListener` →
     * `reloadGroupsAndProbeTarget()` → `loadGroups(最新の knownUuids)`）に乗る。
     * 新しいシグナリング経路は作らないし、`FailoverService` が
     * `ProfileManager` を直接監視することもない。
     *
     * 削除で候補が消えたあとの挙動は裁定72a が既に規定しており
     * （`currentCandidateUuid` が新しい一覧に無ければ障害として次候補へ）、
     * ここは「その判定をいつ走らせるか」だけを直している。
     *
     * 値そのものは誰も読まない（[KeyValueStore] は文字列しか扱えないので
     * 10進数の文字列として持つ）。壊れた値・未保存はどちらも 0 として扱い、
     * 必ず「前と違う値」になるようにする。
     */
    fun bumpProfileGeneration() {
        val current = store.getString(KEY_PROFILE_GENERATION)?.toLongOrNull() ?: 0L
        store.putString(KEY_PROFILE_GENERATION, (current + 1).toString())
    }

    private fun defaultProbeSchedule(): ProbeSchedule {
        val defaults = FailoverConfig()
        return ProbeSchedule(
            probeIntervalSec = defaults.probeIntervalSec,
            failureThreshold = defaults.failureThreshold,
        )
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
     *
     * 裁定74: `KEY_PROBE_SCHEDULE`（プローブ間隔・失敗閾値のグローバル設定）も
     * ここに含める。設定画面での保存が既存の `FailoverService` の再読込経路に
     * そのまま乗るために必要（新しいシグナリング経路は増やさない）。
     *
     * 裁定86（H1）: `KEY_PROFILE_GENERATION`（接続先の作成・削除の世代）も
     * ここに含める。理由は [bumpProfileGeneration] を参照。
     */
    fun isReloadTriggerKey(key: String?): Boolean =
        key == KEY_GROUPS ||
            key == KEY_PROBE_TARGET ||
            key == KEY_PROBE_SCHEDULE ||
            key == KEY_PROFILE_GENERATION

    private companion object {
        const val KEY_GROUPS = "failover_groups_v1"
        const val KEY_PROBE_TARGET = "failover_probe_target_v1"
        const val KEY_ACTIVE_GROUP_ID = "failover_active_group_id_v1"
        const val KEY_PROBE_SCHEDULE = "failover_probe_schedule_v1"
        const val KEY_PROFILE_GENERATION = "failover_profile_generation_v1"
    }
}
