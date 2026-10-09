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
