package net.openconnect_vpn.android.failover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * いま生きている VPN トンネルのインターフェース名を [ConnectivityManager] から引く。
 *
 * **OS に聞く。推測しない。** 以前は `tun0` を固定で測っていて、再接続のたびに
 * 番号が進む実機で壊れていた（`docs/DECISIONS.md` 項目15、[selectVpnInterfaceName]
 * の KDoc）。`/sys/class/net` の各インターフェースの `operstate` を見て `UP` な tun を探す手も
 * あるが、「どれが**いまの VPN か**」は Android が知っていることなので、
 * そちらに聞くほうが推測が入らない。
 *
 * 判定そのものは純関数 [selectVpnInterfaceName] にあり、ここは候補を集めるだけ
 * である（Android 非依存の部分を JVM のテストで押さえるため）。
 *
 * 必要な権限は `ACCESS_NETWORK_STATE` で、[ConnectivityNetworkGate] が既に
 * 使っているので追加は無い。
 *
 * **判定できないときは null を返す**（`ConnectivityManager` が取れない場合を含む）。
 * [ConnectivityNetworkGate] が「判定できないときは**ある**とみなす」のとは逆向きに
 * 倒していることに注意する。あちらは「下層ネットがあるか」で、分からないときに
 * 止めると状態機械そのものが進まなくなる。こちらは「速度を測る対象」で、
 * 分からないまま測ると**誤った根拠で切り替える**ので、測らない側＝安全側に倒す。
 */
class ConnectivityVpnInterfaceName(private val context: Context) : VpnInterfaceNameSource {

    override fun currentName(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val names = cm.allNetworks.map { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                null
            } else {
                cm.getLinkProperties(network)?.interfaceName
            }
        }
        return selectVpnInterfaceName(names)
    }
}
