package cz.julek.rails.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import cz.julek.rails.network.WebSocketManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Rails Sensor Service — Foreground Service
 *
 * Runs continuously and collects:
 *   1. Screen on/off state (via BroadcastReceiver + PowerManager)
 *   2. Foreground app name (via UsageStatsManager)
 *
 * Sends changes to the Orchestrator via WebSocketManager in the format:
 *   { "type": "phone_state", "screen_on": true, "app": "Instagram" }
 *
 * This payload is identical to what POST /api/phone expects — full compatibility.
 */
class SensorService : Service() {

    companion object {
        const val TAG = "Rails/Sensor"
        const val CHANNEL_ID = "rails_sensor"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "cz.julek.rails.action.START_SENSOR"
        const val ACTION_STOP = "cz.julek.rails.action.STOP_SENSOR"

        const val EXTRA_SERVER_ADDRESS = "server_address"

        // How often to poll UsageStats for foreground app (seconds)
        private const val POLL_INTERVAL_S = 3L
    }

    private var screenReceiver: ScreenReceiver? = null
    private var isScreenOn: Boolean = false
    private var lastForegroundApp: String = ""
    private var scheduler: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: ""
                startSensor(address)
            }
            ACTION_STOP -> {
                stopSensor()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopSensor()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Start / Stop
    // ═══════════════════════════════════════════════════════════════════

    private fun startSensor(serverAddress: String) {
        Log.i(TAG, "Starting sensor service — server: $serverAddress")

        // Start foreground notification
        startForeground(NOTIFICATION_ID, buildNotification())

        // Connect WebSocket to Orchestrator
        WebSocketManager.connect(serverAddress)

        // Detect initial screen state
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive
        Log.i(TAG, "Initial screen state: isScreenOn=$isScreenOn")

        // Register screen on/off receiver
        registerScreenReceiver()

        // Acquire partial wake lock (keeps CPU alive for polling)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "rails::sensor_poll"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hours max, renewed each poll
        }

        // Start periodic foreground app polling
        startAppPolling()

        // Send initial state
        sendCurrentState()
    }

    private fun stopSensor() {
        Log.i(TAG, "Stopping sensor service")

        // Stop polling
        scheduler?.shutdownNow()
        scheduler = null

        // Unregister receiver
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { /* ignore */ }
        screenReceiver = null

        // Release wake lock
        try { wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        wakeLock = null

        // Disconnect WebSocket
        WebSocketManager.disconnect()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Screen State Detection
    // ═══════════════════════════════════════════════════════════════════

    private fun registerScreenReceiver() {
        screenReceiver = ScreenReceiver()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        registerReceiver(screenReceiver, filter)

        // Also set up a direct callback from the receiver
        ScreenReceiver.onScreenEvent = { screenOn ->
            isScreenOn = screenOn
            Log.i(TAG, "Screen state changed: isScreenOn=$isScreenOn")
            sendCurrentState()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Foreground App Detection (UsageStatsManager)
    // ═══════════════════════════════════════════════════════════════════

    private fun startAppPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        scheduler?.scheduleAtFixedRate({
            try {
                val currentApp = getForegroundApp()
                if (currentApp != null && currentApp != lastForegroundApp) {
                    lastForegroundApp = currentApp
                    Log.d(TAG, "Foreground app changed: $currentApp")
                    sendCurrentState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "App polling error: ${e.message}")
            }
        }, POLL_INTERVAL_S, POLL_INTERVALS, TimeUnit.SECONDS)
    }

    /**
     * Get the current foreground app package name using UsageStatsManager.
     * Returns null if permission not granted or no data available.
     */
    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val startTime = now - 10_000 // Look at last 10 seconds

        val usageEvents = usageStatsManager.queryEvents(startTime, now)
        var lastEvent: UsageEvents.Event? = null

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)

            // MOVE_TO_FOREGROUND is the event we care about
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastEvent = event
            }
        }

        return lastEvent?.packageName
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Send State to Orchestrator
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send the current phone state to the Orchestrator.
     *
     * Payload format (identical to what POST /api/phone expects):
     *   { "type": "phone_state", "screen_on": true, "app": "com.instagram.android" }
     *
     * The Orchestrator handles both:
     *   - WebSocket: routes via handleWsMessage → state.phone update
     *   - HTTP POST: direct body { screen_on, app } → state.phone update
     */
    private fun sendCurrentState() {
        val app = if (isScreenOn) lastForegroundApp else ""
        WebSocketManager.sendState(screenOn = isScreenOn, foregroundApp = app)

        Log.d(TAG, "State sent: screen_on=$isScreenOn, app=$app")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Notification
    // ═══════════════════════════════════════════════════════════════════

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
