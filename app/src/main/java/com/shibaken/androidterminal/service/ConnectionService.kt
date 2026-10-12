package com.shibaken.androidterminal.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.MainActivity
import com.shibaken.androidterminal.R

/**
 * Foreground service that keeps the process (and therefore the SSH connections and herdr
 * subscriptions) alive while the app is in the background. Holds no state itself.
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            App.instance.sessions.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, App.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${count} 件の SSH セッションに接続中")
            .setContentIntent(open)
            .addAction(0, "すべて切断", disconnect)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"
        private const val ACTION_DISCONNECT_ALL = "disconnect_all"

        fun update(context: Context, sessionCount: Int) {
            val intent = Intent(context, ConnectionService::class.java)
            if (sessionCount > 0) {
                intent.putExtra(EXTRA_COUNT, sessionCount)
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: Exception) {
                    // Starting from the background can be refused; sessions still work while visible.
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
