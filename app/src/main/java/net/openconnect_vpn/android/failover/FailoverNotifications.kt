package net.openconnect_vpn.android.failover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import net.openconnect_vpn.android.R

/** FailoverService のフォアグラウンド通知と、ユーザーに伝えるべき事象の通知。 */
object FailoverNotifications {

    const val CHANNEL_ID = "failover_status"
    const val FOREGROUND_ID = 4201
    const val ALERT_ID = 4202

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN 自動切替",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    fun foreground(context: Context, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle(context.getString(R.string.app))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    fun alert(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        nm.notify(
            ALERT_ID,
            builder
                .setContentTitle(context.getString(R.string.app))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .build(),
        )
    }
}
