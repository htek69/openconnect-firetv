package net.openconnect_vpn.android.failover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 下層ネットワーク（Wi-Fi / 有線）の可用性を見る（安全策 S2）。
 * VPN トランスポートは除外する。VPN 自体が生きているかは状態機械が別に見ている。
 */
class ConnectivityNetworkGate(private val context: Context) : NetworkGate {

    override fun hasUnderlyingNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true  // 判定できないときは「ある」とみなして状態機械を止めない

        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val isPhysical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            !isVpn && isPhysical && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
