package net.openconnect_vpn.android.failover

import kotlinx.serialization.Serializable

/** 接続先プローブの宛先。アプリ全体で1つだけ持つ（仕様書 6章）。 */
@Serializable
data class ProbeTarget(
    val host: String = "1.1.1.1",
    val port: Int = 443,
)

/**
 * 裁定74: プローブ間隔と失敗閾値はアプリ全体で1つだけ持つ（[FailoverGroup.config] は
 * データ上グループ単位だが、それを個別に編集させる UI は無く、設定画面は
 * この2値を「アプリ全体の設定」として1個だけ見せる。ストアの形が UI の見せ方と
 * 食い違うと、設定画面が表示した値がどのグループにも効いていない、あるいは
 * 一部のグループにしか効いていない、という嘘が生まれる）。
 *
 * [GroupStore.loadGroups] がこの値を読み込んだ全グループの
 * `config.probeIntervalSec` / `config.failureThreshold` に上書きするため、
 * [net.openconnect_vpn.android.failover.FailoverController] 側は変更なしで
 * 引き続き `FailoverGroup.config` だけを読めばよい。
 */
@Serializable
data class ProbeSchedule(
    val probeIntervalSec: Int,
    val failureThreshold: Int,
)

/**
 * 仕様書 §4-A: 経路品質による切替の設定。**4項目すべてを利用者が変更できる。**
 *
 * 既定値は旧仕様 §2-2 の実測から引いたが、**その実測は1台の端末・1つの宛先・
 * 1つの時間帯のものである**（改訂版 §3-2 の限界）。埋め込みで固定しない。
 *
 * **保存キーは `slow_link_settings_v1` のまま据え置く**（`DECISIONS` 項目7）。
 * 新しい項目には既定値があるので、古い版が書いた JSON を読んでも落ちない。
 * 改訂で消えた `demandTxKbps` は `GroupStore` の `ignoreUnknownKeys` が読み飛ばす。
 */
@Serializable
data class SlowLinkSettings(
    val enabled: Boolean = false,
    /** 「遅い」と見なす受信速度の上限（kbps）。旧仕様から変更なし。 */
    val slowRxKbps: Int = 1000,
    /**
     * 「使っている」と見なす受信速度の下限（kbps）。これを下回るあいだは判定しない。
     * **`0` は下限なし。**
     *
     * **待機中の誤判定を防ぐ要の値である。** 2026-10-09 に、待機中の受信
     * 0.16 kbps（中央値）で切替が起きた事故（`docs/MANUAL-TEST.md` 節 10-5-1）は
     * この下限が無かったために起きた。下げると戻る。
     */
    val rxFloorKbps: Int = 50,
    /**
     * 応答時間の中央値の上限（ms）。これを超えたら「経路が遅い」側の条件が成立。
     * 実測の目安: 素の回線 約70ms、遅かった接続先 140〜380ms（旧仕様 §2-2）。
     */
    val degradedRttMs: Int = 250,
    /**
     * 応答時間のばらつき（最大−最小）の上限（ms）。
     * 実測の目安: 素の回線 約5ms、遅かった接続先 140〜336ms（同）。
     */
    val degradedJitterMs: Int = 150,
    /**
     * 一周して止まったあと、再開するまでの時間（分）。**`0` は再開しない。**
     *
     * 仕様書 §2-B: **再開は「切り替える」ことではない。** 一周の記録を消して
     * 資格を戻すだけで、切替には判定の3条件が別途必要である。
     */
    val rearmAfterLapMin: Int = 0,
)

/**
 * [SlowLinkSettings] の数値5項目の**範囲（上下限）**。仕様書 §4-A の表。
 *
 * **範囲の置き場はここ1か所だけ。** 使い手は2つある。
 * 1. 設定画面のステッパー（`tv.SettingsStepper.clamp*`）——押しすぎて範囲を出ないため
 * 2. 読込経路（[SlowLinkSettings.coerced]、`GroupStore.loadSlowLinkSettings`）——
 *    別の版が書いた、あるいは手で書き換えた JSON が範囲外の値を運んできても、
 *    判定に届く前に範囲へ収めるため
 *
 * 読込側は `failover` パッケージにあり、`tv`（UI 層）を参照できない（下向きの依存に
 * なる）。そこで**範囲（領域の事実）はここに置き、刻み（UI の都合）は `tv` に残す。**
 * ステッパーは数字を書き写さず、この定数を名指すこと。2か所が食い違うと、画面が
 * 許す値を読込が黙って書き換える（あるいはその逆）。
 *
 * 各定数の KDoc は、仕様書 §4-A の「動かしたときに何が起きるか」の要約である。
 */
object SlowLinkBounds {
    /**
     * 「遅い」と見なす受信速度の上限（kbps）の下限。旧仕様から変更なし。
     *
     * **範囲外を判定に届かせない理由（裁定30）:** 受信速度の帯は上限（この項目）と
     * 下限（[RX_FLOOR_KBPS_MIN]〜）の2つの門でできている。上限が 0 以下だと
     * 「受信が上限未満」が決して成り立たず、`isDegraded()` が即 false を返して
     * 機能が黙って死ぬ。
     */
    const val SLOW_RX_KBPS_MIN = 250

    /**
     * 同上の上限。**上げすぎると**普通のビットレートの再生まで「遅い」と誤判定し、
     * さらに巨大な値では「受信が上限未満」が常に成り立って**帯の上側の門が
     * 何も絞らなくなる**（判定は3条件の積なので、1つが黙って消える）。
     */
    const val SLOW_RX_KBPS_MAX = 5000

    /**
     * 応答時間の中央値の上限（ms）の下限。**下げるほど誤判定が増える。**
     * 宛先が地理的に遠いだけで「経路が悪い」と判定されうる。実測の目安は
     * 素の回線 約 70 ms、遅かった接続先 140〜380 ms。
     */
    const val DEGRADED_RTT_MS_MIN = 50

    /**
     * 同上の上限。**上げるほど切り替わりにくくなる。** 宛先が遠い利用者は
     * 上げる必要があるが、上げすぎると本当に遅い経路も見逃す。
     */
    const val DEGRADED_RTT_MS_MAX = 2000

    /**
     * 応答時間のばらつき（最大−最小）の上限（ms）の下限。**下げると一瞬の揺れで
     * 切り替わる。** 素の回線 約 5 ms と遅かった接続先 140〜336 ms は2桁の差で
     * 分かれていたので、既定（150）から大きく下げる理由は乏しい。
     *
     * **0 や負の値を許さないこと。** しきい値が 0 だと「ばらつきがしきい値を超える」が
     * ほとんど常に真になり、判定の3条件のうちこの1つが事実上無効になる。
     */
    const val DEGRADED_JITTER_MS_MIN = 50

    /** 同上の上限。**上げると一過性の揺れを拾わなくなる**が、不安定な経路も見逃す。 */
    const val DEGRADED_JITTER_MS_MAX = 1000

    /**
     * 「使っている」と見なす受信速度の下限（kbps）の下限。**`0` は下限なし。**
     *
     * **下げると待機中の誤判定が戻る。** 2026-10-09 に実際に起きた事故
     * （`docs/MANUAL-TEST.md` 節 10-5-1、待機中の受信は 0.16 kbps）がこれで、
     * `0` にすると誰も見ていないのに切り替わりうる。0 を許すのは利用者に
     * 選ばせるためで、画面は 0 のとき警告を出す（`SettingsText`）。
     */
    const val RX_FLOOR_KBPS_MIN = 0

    /**
     * 同上の上限。**上げると低ビットレートの再生（音声のみ等）を「使っていない」と
     * 見なし**、本当に遅いときでも切り替えなくなる。
     */
    const val RX_FLOOR_KBPS_MAX = 500

    /** 一周して止まったあと再開するまでの時間（分）の下限。**`0` は再開しない。** */
    const val REARM_AFTER_LAP_MIN_MIN = 0

    /**
     * 同上の上限。**短いほど**劣化が続くとき、その間隔ごとに一周ぶんの切断が起きる。
     * **長いほど**一度諦めたら長く諦めたままになる。
     */
    const val REARM_AFTER_LAP_MIN_MAX = 240
}

/**
 * 数値5項目を [SlowLinkBounds] の範囲へ収めた複製を返す。
 *
 * **読込経路で使う。** `GroupStore.loadSlowLinkSettings` は、`ignoreUnknownKeys` で
 * 未知の項目を読み飛ばせても、**範囲外の値**までは弾けない。たとえば
 * `degradedJitterMs: 0` がそのまま判定に届くと「ばらつきがしきい値を超える」が
 * ほとんど常に真になり、切替が不当に起きやすくなる。範囲内の値は変えない。
 *
 * 裁定30: `slowRxKbps` も対象にする。受信速度の帯の上側の門であり、範囲外の値が
 * 届くと判定の3条件の1つが黙って消える（巨大な値）か、機能が黙って死ぬ（0 以下）。
 * `enabled` は真偽値なので範囲が無く、触らない。
 */
fun SlowLinkSettings.coerced(): SlowLinkSettings = copy(
    slowRxKbps = slowRxKbps.coerceIn(SlowLinkBounds.SLOW_RX_KBPS_MIN, SlowLinkBounds.SLOW_RX_KBPS_MAX),
    rxFloorKbps = rxFloorKbps.coerceIn(SlowLinkBounds.RX_FLOOR_KBPS_MIN, SlowLinkBounds.RX_FLOOR_KBPS_MAX),
    degradedRttMs = degradedRttMs.coerceIn(SlowLinkBounds.DEGRADED_RTT_MS_MIN, SlowLinkBounds.DEGRADED_RTT_MS_MAX),
    degradedJitterMs = degradedJitterMs.coerceIn(
        SlowLinkBounds.DEGRADED_JITTER_MS_MIN,
        SlowLinkBounds.DEGRADED_JITTER_MS_MAX,
    ),
    rearmAfterLapMin = rearmAfterLapMin.coerceIn(
        SlowLinkBounds.REARM_AFTER_LAP_MIN_MIN,
        SlowLinkBounds.REARM_AFTER_LAP_MIN_MAX,
    ),
)

/** フェイルオーバーの挙動を決めるパラメータ。 */
@Serializable
data class FailoverConfig(
    val probeIntervalSec: Int = 30,
    val probeTimeoutMs: Int = 5_000,
    val failureThreshold: Int = 3,
    val graceAfterConnectSec: Int = 15,
    /**
     * Ruling 22: `Connecting` に留まれる上限。ブラックホール宛先など RST が返らない
     * 相手だと、既存コアは OS の TCP タイムアウト（約2分）まで沈黙する。それより
     * 十分短く、かつ低速回線での TLS ハンドシェイク＋認証をタイムアウトさせない
     * 45秒を既定値にする。
     */
    val connectTimeoutSec: Int = 45,
)

/**
 * 優先順位付きのフェイルオーバー群。
 * [memberUuids] の並び順がそのまま優先順位になる。
 */
@Serializable
data class FailoverGroup(
    val id: String,
    val name: String,
    val memberUuids: List<String>,
    val autoFailoverEnabled: Boolean,
    val config: FailoverConfig = FailoverConfig(),
)
