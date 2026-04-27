package cz.julek.rails.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Rails Sensor Service — Foreground Service
 *
 * Runs continuously in the background and:
 * 1. Detects screen on/off via BroadcastReceiver
 * 2. Polls foreground app via UsageStatsManager
 * 3. Sends state changes to Orchestrator via WebSocket
 *
 * Will be implemented in Phase 2.
 */
class SensorService : Service() {

    companion object {
        const val CHANNEL_ID = "rails_sensor"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "cz.julek.rails.action.START_SENSOR"
        const val ACTION_STOP = "cz.julek.rails.action.STOP_SENSOR"

        const val EXTRA_SERVER_ADDRESS = "server_address"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: ""
                startForeground(NOTIFICATION_ID, buildNotification())
                // TODO: Start WebSocket connection, register ScreenReceiver, poll UsageStats
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Disconnect WebSocket, unregister receivers
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rails Sensor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Senzor sledování aktivity na pozadí"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rails senzor aktivní")
            .setContentText("Sleduji aktivitu a odesílám data na PC")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
