package net.openconnect_vpn.android.failover

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * [GroupStore] の**性質テスト**（手書きシナリオではない）。
 *
 * 既存の `GroupStoreTest` の22件は代表的な設定を手で書いたもの。ここでは
 * 設定・グループを機械生成して保存・読み戻し、どの値でも破れてはならない
 * 性質を検査する。依存は増やさない（[Random] のシードは固定配列 [SEEDS]）。
 *
 * 性質は3つ。
 *
 * - [checkRoundTrip]: **往復**。任意の妥当な設定・グループを保存して読み戻すと
 *   一致する（[GroupStore.loadGroups] が仕様どおり行う2つの変換——削除済み
 *   プロファイルの除去と、裁定74 のグローバル設定での上書き——を経たうえで）。
 * - [checkCorrupt]: **壊れた値**。壊れた文字列に対して例外を投げず既定値に倒れる。
 *   生成する文字列は**壊れていることが構成から言える**ものだけに限る
 *   （[corruptStrings]）——たまたま妥当な JSON になっていたら、既定値に
 *   倒れないのは正しい挙動であり、偽の失敗になる。
 * - [checkForwardCompatible]: **前方互換**。知らないフィールドが混じった JSON を
 *   読んでも落ちず、知っているフィールドはそのまま読める（新しい版が書いた値を
 *   古い版が読む場合に相当する）。
 *
 * **実在のホスト名・IP アドレスは書かない。** プローブ宛先の生成には
 * RFC 2606 が予約していて解決しないことが保証される `.invalid` と、
 * 見て分かる置き換え名だけを使う（[HOSTS]）。
 */
class GroupStorePropertyTest {

    // ------------------------------------------------------------------ 入力

    private data class Case(
        val seed: Long,
        val index: Int,
        val groups: List<FailoverGroup>,
        val known: Set<String>,
        val schedule: ProbeSchedule?,
        val target: ProbeTarget,
        val slowLink: SlowLinkSettings,
        val activeGroupId: String?,
    )

    private data class Violation(val property: String, val kind: String, val detail: String, val case: Case) {
        val key: String get() = "$property/$kind"
    }

    // ------------------------------------------------------------ 検査の入口

    @Test
    fun `G1 任意の設定とグループは往復する`() = assertHolds("G1")

    @Test
    fun `G2 壊れた値は例外を投げず既定値に倒れる`() = assertHolds("G2")

    @Test
    fun `G3 知らないフィールドが混じった JSON を読んでも落ちない`() = assertHolds("G3")

    @Test
    fun `生成器は各性質の前提に到達している`() {
        val required = listOf(
            "G1 メンバーが除去されるグループ",
            "G1 グループ自体が落ちる場合",
            "G1 グローバル設定を保存済み",
            "G1 グローバル設定が未保存",
            "G1 低速設定が範囲内（往復が恒等）",
            "G1 低速設定が範囲外（収められる）",
            "G2 開き括弧が違う",
            "G2 途中で切れている",
            "G2 型が合わない",
            "G3 知らないフィールド",
        )
        val missing = required.filter { (corpus.coverage[it] ?: 0) == 0 }
        assertTrue(
            "生成器が踏んでいない前提がある: $missing\n" +
                "列数=${corpus.cases} 所要=${corpus.elapsedMs}ms\n" +
                "到達記録=${corpus.coverage.toSortedMap()}",
            missing.isEmpty(),
        )
    }

    private fun assertHolds(property: String) {
        val found = corpus.worst.filterKeys { it.startsWith("$property/") }
        if (found.isEmpty()) return
        val message = buildString {
            appendLine(
                "性質 $property が破れた（${found.size} 種類、${corpus.cases} 列中）。" +
                    "**製品コードを直してはならない。性質を緩めてもならない。**",
            )
            for (violation in found.values.sortedBy { it.case.groups.size }) {
                appendLine()
                appendLine(renderFull(violation))
                appendLine(renderMinimal(shrink(violation)))
            }
        }
        fail(message)
    }

    private fun renderFull(violation: Violation): String = buildString {
        appendLine("=== ${violation.key} : ${violation.detail}")
        appendLine("  出現回数: ${corpus.counts[violation.key]}")
        appendLine("  シード: seed=${violation.case.seed} 列=${violation.case.index}")
        appendLine("  --- 生成された入力 ---")
        appendLine(renderCase(violation.case))
    }

    private fun renderMinimal(violation: Violation): String = buildString {
        appendLine("  --- 最小の入力（削れるところまで削った）---")
        appendLine(renderCase(violation.case))
    }

    private fun renderCase(case: Case): String = buildString {
        appendLine("  knownProfileUuids = ${case.known}")
        appendLine("  saveProbeSchedule(${case.schedule})  // null は未保存")
        appendLine("  saveProbeTarget(${case.target})")
        appendLine("  saveSlowLinkSettings(${case.slowLink})")
        appendLine("  saveActiveGroupId(${case.activeGroupId})")
        appendLine("  saveGroups(")
        case.groups.forEach { appendLine("    $it,") }
        appendLine("  )")
    }

    /** グループを1つずつ落として、同じ破れが残る最小の集合にする。 */
    private fun shrink(violation: Violation): Violation {
        val check = CHECKS[violation.property] ?: return violation
        val sink = mutableMapOf<String, Int>()
        var best = violation
        var at = 0
        var budget = 200
        while (at < best.case.groups.size && budget > 0) {
            budget--
            val reduced = best.case.groups.toMutableList()
            reduced.removeAt(at)
            val replayed = check(best.case.copy(groups = reduced), sink)
            if (replayed != null && replayed.key == violation.key) best = replayed else at++
        }
        return best
    }

    private companion object {

        private val SEEDS = listOf(1L, 2L, 3L, 5L, 8L, 13L)
        private const val CASES_PER_SEED = 60

        /** メンバーに使う UUID の池。実在の値ではない。 */
        private val UUID_POOL = listOf(
            "uuid-a", "uuid-b", "uuid-c", "uuid-d", "uuid-e",
            "5f3c1c20-0000-4000-8000-000000000001",
            "", "uuid-\u3042", "uuid with space", "uuid\"quote",
        )

        /** グループ名の池（既存テストと同じく日本語を含む）。 */
        private val NAME_POOL = listOf(
            "自宅優先", "社外", "予備", "", "a".repeat(200), "改行\nを含む", "引用\"符", "逆\\斜線",
            "\uD83D\uDE00 絵文字", "タブ\tを含む",
        )

        /**
         * プローブ宛先のホスト名。**実在のホスト名・IP アドレスは書かない。**
         * `.invalid` は RFC 2606 の予約 TLD で、解決しないことが保証されている。
         */
        private val HOSTS = listOf(
            "probe-host.invalid", "placeholder.invalid", "ホスト.invalid", "", "h".repeat(300),
        )

        private val CHECKS: Map<String, (Case, MutableMap<String, Int>) -> Violation?> = mapOf(
            "G1" to { c, cov -> checkRoundTrip(c, cov) },
            "G2" to { c, cov -> checkCorrupt(c, cov) },
            "G3" to { c, cov -> checkForwardCompatible(c, cov) },
        )

        /**
         * G1: 往復。
         *
         * 期待値は仕様が決めている2つの変換を施したもの: [GroupStore.loadGroups] の
         * KDoc にある「[knownProfileUuids] に無いメンバーを除去し、メンバーが
         * 残らないグループは落とす」と、裁定74 の「`probeIntervalSec` /
         * `failureThreshold` はグローバル設定で必ず上書きする」。
         */
        private fun checkRoundTrip(case: Case, cov: MutableMap<String, Int>): Violation? {
            val kv = InMemoryKeyValueStore()
            val store = GroupStore(kv)
            if (case.schedule != null) {
                store.saveProbeSchedule(case.schedule)
                bump(cov, "G1 グローバル設定を保存済み")
            } else {
                bump(cov, "G1 グローバル設定が未保存")
            }
            store.saveProbeTarget(case.target)
            store.saveSlowLinkSettings(case.slowLink)
            store.saveActiveGroupId(case.activeGroupId)
            store.saveGroups(case.groups)

            val schedule = case.schedule ?: defaultSchedule()
            val expected = case.groups
                .map { it.copy(memberUuids = it.memberUuids.filter { uuid -> uuid in case.known }) }
                .filter { it.memberUuids.isNotEmpty() }
                .map {
                    it.copy(
                        config = it.config.copy(
                            probeIntervalSec = schedule.probeIntervalSec,
                            failureThreshold = schedule.failureThreshold,
                        ),
                    )
                }
            if (case.groups.any { it.memberUuids.any { uuid -> uuid !in case.known } }) {
                bump(cov, "G1 メンバーが除去されるグループ")
            }
            if (expected.size < case.groups.size) bump(cov, "G1 グループ自体が落ちる場合")

            val loaded = try {
                store.loadGroups(case.known)
            } catch (t: Throwable) {
                return Violation("G1", "例外", "${t.javaClass.name}: ${t.message}", case)
            }
            if (loaded != expected) {
                return Violation(
                    "G1", "グループが一致しない",
                    "期待=$expected\n  実際=$loaded", case,
                )
            }
            if (store.loadProbeTarget() != case.target) {
                return Violation(
                    "G1", "プローブ宛先が一致しない",
                    "期待=${case.target} 実際=${store.loadProbeTarget()}", case,
                )
            }
            // 裁定30: 読込は数値5項目を範囲へ収めて返す。したがって性質は「保存した値が
            // そのまま戻る」ではなく「読んだ値は、保存した値を収めた形である」。
            // 範囲内の値は収めても変わらないので、範囲内の入力ではこれが従来どおりの
            // 「保存した値がそのまま戻る」（直列化の忠実さ）になる。範囲外の入力では
            // 収められた形を期待する。**どちらの入力も生成器が必ず踏む**（到達記録）。
            // 期待は製品の coerced() を呼ばず、範囲の定数から独立に組む（製品側の
            // coerced が項目を落としても、期待が同じ誤りに引きずられないように）。
            val l = case.slowLink
            val expectedSlow = l.copy(
                slowRxKbps = l.slowRxKbps.coerceIn(SlowLinkBounds.SLOW_RX_KBPS_MIN, SlowLinkBounds.SLOW_RX_KBPS_MAX),
                rxFloorKbps = l.rxFloorKbps.coerceIn(SlowLinkBounds.RX_FLOOR_KBPS_MIN, SlowLinkBounds.RX_FLOOR_KBPS_MAX),
                degradedRttMs = l.degradedRttMs.coerceIn(
                    SlowLinkBounds.DEGRADED_RTT_MS_MIN, SlowLinkBounds.DEGRADED_RTT_MS_MAX,
                ),
                degradedJitterMs = l.degradedJitterMs.coerceIn(
                    SlowLinkBounds.DEGRADED_JITTER_MS_MIN, SlowLinkBounds.DEGRADED_JITTER_MS_MAX,
                ),
                rearmAfterLapMin = l.rearmAfterLapMin.coerceIn(
                    SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN, SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX,
                ),
            )
            if (case.slowLink == expectedSlow) {
                bump(cov, "G1 低速設定が範囲内（往復が恒等）")
            } else {
                bump(cov, "G1 低速設定が範囲外（収められる）")
            }
            if (store.loadSlowLinkSettings() != expectedSlow) {
                return Violation(
                    "G1", "低速設定が一致しない",
                    "保存=${case.slowLink} 期待（収めた形）=$expectedSlow 実際=${store.loadSlowLinkSettings()}",
                    case,
                )
            }
            if (store.loadProbeSchedule() != schedule) {
                return Violation(
                    "G1", "プローブ設定が一致しない",
                    "期待=$schedule 実際=${store.loadProbeSchedule()}", case,
                )
            }
            if (store.loadActiveGroupId() != case.activeGroupId) {
                return Violation(
                    "G1", "アクティブグループIDが一致しない",
                    "期待=${case.activeGroupId} 実際=${store.loadActiveGroupId()}", case,
                )
            }
            // 保存したキーはすべて再読込トリガの判定に掛けられる（裁定48/72/74/86, Task 3）。
            // グループ・プローブ宛先・プローブ設定・低速設定は載っていなければならない。
            val mustTrigger = kv.writtenKeys.filter { it != ACTIVE_GROUP_KEY }
            val notTriggering = mustTrigger.filterNot { store.isReloadTriggerKey(it) }
            if (notTriggering.isNotEmpty()) {
                return Violation(
                    "G1", "再読込トリガから漏れたキー",
                    "保存したのに isReloadTriggerKey が false: $notTriggering", case,
                )
            }
            return null
        }

        /** G2: 壊れた値は例外を投げず既定値に倒れる。 */
        private fun checkCorrupt(case: Case, cov: MutableMap<String, Int>): Violation? {
            val rnd = Random(case.seed * 31L + case.index)
            val valid = validEncodings(case)
            for ((label, corrupt) in corruptStrings(rnd, valid)) {
                bump(cov, label)
                val kv = InMemoryKeyValueStore()
                kv.putString(KEY_GROUPS, corrupt)
                kv.putString(KEY_PROBE_TARGET, corrupt)
                kv.putString(KEY_PROBE_SCHEDULE, corrupt)
                kv.putString(KEY_SLOW_LINK, corrupt)
                val store = GroupStore(kv)
                val results = mutableListOf<String>()
                try {
                    results.add("loadGroups=" + store.loadGroups(case.known))
                    results.add("loadProbeTarget=" + store.loadProbeTarget())
                    results.add("loadProbeSchedule=" + store.loadProbeSchedule())
                    results.add("loadSlowLinkSettings=" + store.loadSlowLinkSettings())
                } catch (t: Throwable) {
                    return Violation(
                        "G2", "例外",
                        "壊れた値 \"${cut(corrupt)}\"（$label）で ${t.javaClass.name}: ${t.message}", case,
                    )
                }
                val store2 = GroupStore(kv)
                if (store2.loadGroups(case.known).isNotEmpty()) {
                    return Violation(
                        "G2", "グループが既定値に倒れない",
                        "壊れた値 \"${cut(corrupt)}\"（$label）で ${store2.loadGroups(case.known)}", case,
                    )
                }
                if (store2.loadProbeTarget() != ProbeTarget()) {
                    return Violation(
                        "G2", "プローブ宛先が既定値に倒れない",
                        "壊れた値 \"${cut(corrupt)}\"（$label）で ${store2.loadProbeTarget()}", case,
                    )
                }
                if (store2.loadProbeSchedule() != defaultSchedule()) {
                    return Violation(
                        "G2", "プローブ設定が既定値に倒れない",
                        "壊れた値 \"${cut(corrupt)}\"（$label）で ${store2.loadProbeSchedule()}", case,
                    )
                }
                if (store2.loadSlowLinkSettings() != SlowLinkSettings()) {
                    return Violation(
                        "G2", "低速設定が既定値に倒れない",
                        "壊れた値 \"${cut(corrupt)}\"（$label）で ${store2.loadSlowLinkSettings()}", case,
                    )
                }
                // 世代カウンタは壊れていても必ず「前と違う値」へ進む（裁定86）。
                kv.putString(KEY_PROFILE_GENERATION, corrupt)
                val before = kv.getString(KEY_PROFILE_GENERATION)
                store2.bumpProfileGeneration()
                if (kv.getString(KEY_PROFILE_GENERATION) == before) {
                    return Violation(
                        "G2", "世代カウンタが進まない",
                        "壊れた値 \"${cut(corrupt)}\"（$label）から bumpProfileGeneration が同じ値を書いた", case,
                    )
                }
            }
            return null
        }

        /**
         * G3: 前方互換。知らないフィールド（新しい版が書いたもの）が混じっていても
         * 落ちず、知っているフィールドはそのまま読める。
         */
        private fun checkForwardCompatible(case: Case, cov: MutableMap<String, Int>): Violation? {
            val rnd = Random(case.seed * 131L + case.index)
            bump(cov, "G3 知らないフィールド")
            val id = "g-future"
            val member = "uuid-a"
            val extra = (0 until 1 + rnd.nextInt(3)).map { futureField(rnd, it) }
            val groupsJson = "[{\"id\":\"$id\",\"name\":\"n\",\"memberUuids\":[\"$member\"]," +
                "\"autoFailoverEnabled\":true,\"config\":{\"probeIntervalSec\":41,\"probeTimeoutMs\":4100," +
                "\"failureThreshold\":7,\"graceAfterConnectSec\":11,\"connectTimeoutSec\":21," +
                extra.joinToString(",") + "}," + extra.joinToString(",") + "}]"
            val targetJson = "{\"host\":\"probe-host.invalid\",\"port\":8443," + extra.joinToString(",") + "}"
            val scheduleJson = "{\"probeIntervalSec\":55,\"failureThreshold\":6," + extra.joinToString(",") + "}"
            val slowJson = "{\"enabled\":true,\"slowRxKbps\":750," + extra.joinToString(",") + "}"

            val kv = InMemoryKeyValueStore()
            kv.putString(KEY_GROUPS, groupsJson)
            kv.putString(KEY_PROBE_TARGET, targetJson)
            kv.putString(KEY_PROBE_SCHEDULE, scheduleJson)
            kv.putString(KEY_SLOW_LINK, slowJson)
            val store = GroupStore(kv)

            val loaded = try {
                store.loadGroups(setOf(member))
            } catch (t: Throwable) {
                return Violation("G3", "例外", "${t.javaClass.name}: ${t.message}\n  $groupsJson", case)
            }
            val group = loaded.singleOrNull()
                ?: return Violation("G3", "グループを読めない", "読み戻せたのは $loaded\n  $groupsJson", case)
            if (group.id != id || group.name != "n" || group.memberUuids != listOf(member) ||
                !group.autoFailoverEnabled
            ) {
                return Violation("G3", "知っているフィールドが変わった", "$group\n  $groupsJson", case)
            }
            // 裁定74: この2つはグローバル設定（scheduleJson）で上書きされる。
            if (group.config.probeIntervalSec != 55 || group.config.failureThreshold != 6) {
                return Violation("G3", "グローバル設定の上書きが効いていない", "${group.config}", case)
            }
            if (group.config.probeTimeoutMs != 4_100 || group.config.graceAfterConnectSec != 11 ||
                group.config.connectTimeoutSec != 21
            ) {
                return Violation("G3", "config の他のフィールドが変わった", "${group.config}", case)
            }
            val target = store.loadProbeTarget()
            if (target != ProbeTarget(host = "probe-host.invalid", port = 8_443)) {
                return Violation("G3", "プローブ宛先が変わった", "$target\n  $targetJson", case)
            }
            val schedule = store.loadProbeSchedule()
            if (schedule != ProbeSchedule(probeIntervalSec = 55, failureThreshold = 6)) {
                return Violation("G3", "プローブ設定が変わった", "$schedule\n  $scheduleJson", case)
            }
            val slow = store.loadSlowLinkSettings()
            if (slow != SlowLinkSettings(enabled = true, slowRxKbps = 750)) {
                return Violation("G3", "低速設定が変わった", "$slow\n  $slowJson", case)
            }
            return null
        }

        // ---------------------------------------------------------- 壊れた値の作り方

        /** 保存経路が実際に書く正しい JSON（切り詰めの元にする）。 */
        private fun validEncodings(case: Case): List<String> {
            val kv = InMemoryKeyValueStore()
            val store = GroupStore(kv)
            store.saveGroups(case.groups)
            store.saveProbeTarget(case.target)
            store.saveProbeSchedule(case.schedule ?: defaultSchedule())
            store.saveSlowLinkSettings(case.slowLink)
            return listOfNotNull(
                kv.getString(KEY_GROUPS),
                kv.getString(KEY_PROBE_TARGET),
                kv.getString(KEY_PROBE_SCHEDULE),
                kv.getString(KEY_SLOW_LINK),
            )
        }

        /**
         * **壊れていることが構成から言える**文字列だけを作る。
         *
         * - 開き括弧が違う: 最初の非空白文字が `[` でも `{` でもない
         *   （どの型の JSON としても読めない）。
         * - 途中で切れている: 正しい JSON を、括弧または引用符が**釣り合わない**
         *   位置で切ったもの（[unbalanced] で確かめる）。
         * - 型が合わない: JSON としては妥当だが、必須フィールドが無い・型が違う。
         */
        private fun corruptStrings(rnd: Random, valid: List<String>): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            out.add("G2 開き括弧が違う" to "{ this is not json")
            out.add("G2 開き括弧が違う" to "")
            out.add("G2 開き括弧が違う" to "   ")
            out.add(
                "G2 開き括弧が違う" to buildString {
                    repeat(1 + rnd.nextInt(24)) { append(CORRUPT_CHARS[rnd.nextInt(CORRUPT_CHARS.size)]) }
                }.let { if (it.trimStart().firstOrNull() in setOf('[', '{')) "x$it" else it },
            )
            for (text in valid) {
                if (text.length < 3) continue
                var tries = 0
                while (tries < 8) {
                    tries++
                    val cut = 1 + rnd.nextInt(text.length - 1)
                    val piece = text.substring(0, cut)
                    if (unbalanced(piece)) {
                        out.add("G2 途中で切れている" to piece)
                        break
                    }
                }
            }
            out.add("G2 型が合わない" to "[{\"id\":\"only-id\"}]")
            out.add("G2 型が合わない" to "[[\"not\",\"an\",\"object\"]]")
            out.add("G2 型が合わない" to "{\"host\":true,\"port\":\"not a number\"}")
            out.add("G2 型が合わない" to "{\"probeIntervalSec\":\"x\"}")
            out.add("G2 型が合わない" to "42")
            out.add("G2 型が合わない" to "null")
            return out
        }

        /**
         * JSON として**閉じ切っていない**か（括弧の深さが残っている／文字列の途中）。
         * 真なら、その文字列はどの型としても読めない。
         */
        private fun unbalanced(text: String): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (ch in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                }
            }
            return inString || depth != 0
        }

        private fun futureField(rnd: Random, index: Int): String {
            val name = "x_future_${index}_${rnd.nextInt(1_000)}"
            val value = when (rnd.nextInt(6)) {
                0 -> "123"
                1 -> "\"text\""
                2 -> "true"
                3 -> "null"
                4 -> "[1,2,3]"
                else -> "{\"nested\":\"value\"}"
            }
            return "\"$name\":$value"
        }

        private fun cut(text: String): String =
            (if (text.length > 120) text.substring(0, 120) + "…" else text)
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

        private fun defaultSchedule(): ProbeSchedule {
            val defaults = FailoverConfig()
            return ProbeSchedule(
                probeIntervalSec = defaults.probeIntervalSec,
                failureThreshold = defaults.failureThreshold,
            )
        }

        private fun bump(cov: MutableMap<String, Int>, name: String) {
            cov[name] = (cov[name] ?: 0) + 1
        }

        // ---------------------------------------------------------------- 生成器

        private fun generate(rnd: Random, seed: Long, index: Int): Case {
            val groupCount = rnd.nextInt(4)
            val groups = (0 until groupCount).map { i ->
                val memberCount = rnd.nextInt(5)
                FailoverGroup(
                    id = "g$i-${rnd.nextInt(1_000)}",
                    name = NAME_POOL[rnd.nextInt(NAME_POOL.size)],
                    memberUuids = (0 until memberCount).map { UUID_POOL[rnd.nextInt(UUID_POOL.size)] },
                    autoFailoverEnabled = rnd.nextBoolean(),
                    config = FailoverConfig(
                        probeIntervalSec = smallInt(rnd),
                        probeTimeoutMs = smallInt(rnd),
                        failureThreshold = smallInt(rnd),
                        graceAfterConnectSec = smallInt(rnd),
                        connectTimeoutSec = smallInt(rnd),
                    ),
                )
            }
            val known = UUID_POOL.filter { rnd.nextInt(100) < 60 }.toMutableSet()
            if (rnd.nextInt(100) < 20) known.add("uuid-unrelated")
            return Case(
                seed = seed,
                index = index,
                groups = groups,
                known = known,
                schedule = if (rnd.nextInt(100) < 70) {
                    ProbeSchedule(probeIntervalSec = smallInt(rnd), failureThreshold = smallInt(rnd))
                } else {
                    null
                },
                target = ProbeTarget(host = HOSTS[rnd.nextInt(HOSTS.size)], port = smallInt(rnd)),
                slowLink = slowLinkSettings(rnd),
                activeGroupId = if (rnd.nextInt(100) < 70) {
                    groups.randomOrNull(rnd)?.id ?: "g-missing"
                } else {
                    null
                },
            )
        }

        /**
         * 低速設定。半々で、範囲内の値（往復が恒等になる入力）と、範囲外を含みうる
         * 値（収められる入力）を作る。**6項目すべてを動かす**——項目を書き落とす
         * 保存・読込は、既定値のままの項目では見つからない。
         */
        private fun slowLinkSettings(rnd: Random): SlowLinkSettings =
            if (rnd.nextBoolean()) {
                SlowLinkSettings(
                    enabled = rnd.nextBoolean(),
                    slowRxKbps = inRange(rnd, SlowLinkBounds.SLOW_RX_KBPS_MIN, SlowLinkBounds.SLOW_RX_KBPS_MAX),
                    rxFloorKbps = inRange(rnd, SlowLinkBounds.RX_FLOOR_KBPS_MIN, SlowLinkBounds.RX_FLOOR_KBPS_MAX),
                    degradedRttMs = inRange(
                        rnd, SlowLinkBounds.DEGRADED_RTT_MS_MIN, SlowLinkBounds.DEGRADED_RTT_MS_MAX,
                    ),
                    degradedJitterMs = inRange(
                        rnd, SlowLinkBounds.DEGRADED_JITTER_MS_MIN, SlowLinkBounds.DEGRADED_JITTER_MS_MAX,
                    ),
                    rearmAfterLapMin = inRange(
                        rnd, SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN, SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX,
                    ),
                )
            } else {
                SlowLinkSettings(
                    enabled = rnd.nextBoolean(),
                    slowRxKbps = smallInt(rnd),
                    rxFloorKbps = smallInt(rnd),
                    degradedRttMs = smallInt(rnd),
                    degradedJitterMs = smallInt(rnd),
                    rearmAfterLapMin = smallInt(rnd),
                )
            }

        /** 両端を含む範囲の整数。端そのものも出やすくする。 */
        private fun inRange(rnd: Random, min: Int, max: Int): Int = when (rnd.nextInt(4)) {
            0 -> min
            1 -> max
            else -> rnd.nextInt(min, max + 1)
        }

        /** 設定に入りうる整数。0・負・極値も持たせる（JSON は符号付き 32bit をそのまま往復する）。 */
        private fun smallInt(rnd: Random): Int = when (rnd.nextInt(6)) {
            0 -> 0
            1 -> 1
            2 -> rnd.nextInt(1, 100_000)
            3 -> -rnd.nextInt(1, 100_000)
            4 -> Int.MAX_VALUE
            else -> Int.MIN_VALUE
        }

        private val CORRUPT_CHARS: List<Char> =
            "abc0123{}[]\":,\\ \t\n".toList() + listOf('\u0000', 'é', '中', '\uFFFD')

        private const val KEY_GROUPS = "failover_groups_v1"
        private const val KEY_PROBE_TARGET = "failover_probe_target_v1"
        private const val KEY_PROBE_SCHEDULE = "failover_probe_schedule_v1"
        private const val KEY_SLOW_LINK = "slow_link_settings_v1"
        private const val KEY_PROFILE_GENERATION = "failover_profile_generation_v1"
        private const val ACTIVE_GROUP_KEY = "failover_active_group_id_v1"

        // ---------------------------------------------------------------- 実行

        class Corpus {
            val worst = mutableMapOf<String, Violation>()
            val counts = mutableMapOf<String, Int>()
            val coverage = mutableMapOf<String, Int>()
            var cases = 0
            var elapsedMs = 0L
        }

        val corpus: Corpus by lazy { buildCorpus() }

        private fun buildCorpus(): Corpus {
            val corpus = Corpus()
            val startedAt = System.nanoTime()
            for (seed in SEEDS) {
                for (index in 0 until CASES_PER_SEED) {
                    val rnd = Random(seed * 1_000_003L + index)
                    val case = generate(rnd, seed, index)
                    corpus.cases++
                    for ((_, check) in CHECKS) {
                        val violation = check(case, corpus.coverage) ?: continue
                        corpus.counts[violation.key] = (corpus.counts[violation.key] ?: 0) + 1
                        val known = corpus.worst[violation.key]
                        if (known == null || violation.case.groups.size < known.case.groups.size) {
                            corpus.worst[violation.key] = violation
                        }
                    }
                }
            }
            corpus.elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            return corpus
        }
    }
}
