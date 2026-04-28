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
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.hardware.display.DisplayManager
import androidx.core.app.NotificationCompat
import cz.julek.rails.network.FirebaseManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Rails Sensor Service — Foreground Service
 *
 * Runs continuously and collects:
 *   1. Screen on/off state (via BroadcastReceiver + PowerManager)
 *   2. Device locked/unlocked state (via KeyguardManager + USER_PRESENT broadcast)
 *   3. Foreground app name (via UsageStatsManager)
 *
 * Sends changes to the Orchestrator via FirebaseManager (Firebase RTDB):
 *   Path: /rails/devices/my_phone/phone_state
 *   Payload: { "screen_on": true, "app": "...", "app_name": "...", "device_locked": false, "timestamp": ... }
 *
 * Detection logic:
 *   - screen_on: PowerManager.isInteractive (real-time, not static text)
 *   - device_locked: True when screen is on but keyguard is showing,
 *     or when screen is off. Set to false on USER_PRESENT broadcast.
 *   - app: UsageStatsManager foreground app (only when screen is on AND device is unlocked)
 */
class SensorService : Service() {

    companion object {
        const val TAG = "Rails/Sensor"
        const val CHANNEL_ID = "rails_sensor"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "cz.julek.rails.action.START_SENSOR"
        const val ACTION_STOP = "cz.julek.rails.action.STOP_SENSOR"

        // How often to poll UsageStats for foreground app (seconds)
        private const val POLL_INTERVAL_S = 3L
    }

    private var screenReceiver: ScreenReceiver? = null
    private var isScreenOn: Boolean = false
    private var isDeviceLocked: Boolean = true
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
                startSensor()
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

    private fun startSensor() {
        Log.i(TAG, "Starting sensor service — connecting to Firebase")

        // Start foreground notification — MUST specify foregroundServiceType on Android 14+ (API 34)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Connect to Firebase (no IP address needed!)
        FirebaseManager.connect()

        // ── Detect initial screen state (REAL, not static) ──
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive
        Log.i(TAG, "Initial screen state: isScreenOn=$isScreenOn (PowerManager.isInteractive)")

        // ── Detect initial lock state ──
        if (!isScreenOn) {
            isDeviceLocked = true
            Log.i(TAG, "Initial lock state: locked (screen is off)")
        } else {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            isDeviceLocked = keyguardManager.isDeviceLocked
            Log.i(TAG, "Initial lock state: isDeviceLocked=$isDeviceLocked (KeyguardManager.isDeviceLocked)")
        }

        // Register screen on/off + USER_PRESENT receiver
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

        // Disconnect from Firebase
        FirebaseManager.disconnect()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Screen State + Lock Detection (REAL values)
    // ═══════════════════════════════════════════════════════════════════

    private fun registerScreenReceiver() {
        screenReceiver = ScreenReceiver()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)  // User unlocked the device
        }

        registerReceiver(screenReceiver, filter)

        // Callback from ScreenReceiver — receives BOTH screen state AND lock state
        ScreenReceiver.onScreenEvent = { screenOn, deviceLocked ->
            val screenChanged = isScreenOn != screenOn
            val lockChanged = isDeviceLocked != deviceLocked

            isScreenOn = screenOn
            isDeviceLocked = deviceLocked

            Log.i(TAG, "State changed: isScreenOn=$isScreenOn, isDeviceLocked=$isDeviceLocked " +
                    "(screenChanged=$screenChanged, lockChanged=$lockChanged)")

            // Send update if anything changed
            if (screenChanged || lockChanged) {
                sendCurrentState()
            }
        }
    }

    /**
     * Double-check screen state using PowerManager AND lock state using
     * KeyguardManager before sending. This ensures we never send stale
     * data — always the REAL current state from the system APIs.
     */
    private fun refreshScreenState() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val realScreenOn = powerManager.isInteractive
            if (realScreenOn != isScreenOn) {
                Log.w(TAG, "Screen state drift detected! BroadcastReceiver=$isScreenOn, PowerManager=$realScreenOn — correcting")
                isScreenOn = realScreenOn
            }

            // Also re-verify lock state if screen is on
            if (isScreenOn) {
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                val realLocked = keyguardManager.isDeviceLocked
                if (realLocked != isDeviceLocked) {
                    Log.w(TAG, "Lock state drift detected! Cached=$isDeviceLocked, KeyguardManager=$realLocked — correcting")
                    isDeviceLocked = realLocked
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh screen/lock state: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Foreground App Detection (UsageStatsManager)
    // ═══════════════════════════════════════════════════════════════════

    private fun startAppPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        scheduler?.scheduleAtFixedRate({
            try {
                // Renew wake lock in the polling loop
                wakeLock?.let {
                    if (it.isHeld) {
                        it.acquire(4 * 60 * 60 * 1000L) // Renew for another 4 hours
                    }
                }

                // Only detect foreground app when screen is on AND device is unlocked
                if (!isScreenOn || isDeviceLocked) {
                    if (lastForegroundApp.isNotEmpty()) {
                        lastForegroundApp = ""
                        sendCurrentState()
                    }
                    return@scheduleAtFixedRate
                }

                val currentApp = getForegroundApp()
                if (currentApp != null && currentApp != lastForegroundApp) {
                    lastForegroundApp = currentApp
                    Log.d(TAG, "Foreground app changed: $currentApp")
                    sendCurrentState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "App polling error: ${e.message}")
            }
        }, POLL_INTERVAL_S, POLL_INTERVAL_S, TimeUnit.SECONDS)
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

            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastEvent = event
            }
        }

        return lastEvent?.packageName
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Send State to Orchestrator via Firebase
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send the current phone state to the Orchestrator via Firebase.
     *
     * Before sending, we double-check the screen state using PowerManager
     * to ensure we never send stale/incorrect data.
     *
     * Firebase path: /rails/devices/my_phone/phone_state
     * Payload: { "screen_on": true, "app": "...", "app_name": "...", "device_locked": false, "timestamp": ... }
     */
    private fun sendCurrentState() {
        // Refresh screen state from PowerManager (ensure REAL value)
        refreshScreenState()

        // Determine app based on screen + lock state
        val app = when {
            !isScreenOn -> ""                    // Screen off → no app
            isDeviceLocked -> ""                 // Locked → keyguard is showing
            else -> lastForegroundApp            // Unlocked → real foreground app
        }

        // Determine device_locked:
        // If screen is off, device is implicitly locked
        val effectiveLocked = isDeviceLocked || !isScreenOn

        // Send via Firebase
        FirebaseManager.sendState(
            screenOn = isScreenOn,
            foregroundApp = app,
            deviceLocked = effectiveLocked
        )

        Log.d(TAG, "State sent: screen_on=$isScreenOn, device_locked=$effectiveLocked, app=$app")
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
            .setContentText("Sleduji aktivitu a odesílám data přes Firebase")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
