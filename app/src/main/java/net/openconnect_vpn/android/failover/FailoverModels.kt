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
