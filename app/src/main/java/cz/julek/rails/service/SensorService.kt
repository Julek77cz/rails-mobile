package cz.julek.rails.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import cz.julek.rails.MainActivity
import cz.julek.rails.network.FirebaseManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Rails Sensor Service — Foreground Service (Phase 2+3)
 *
 * Runs continuously and collects:
 *   1. Screen on/off state (via BroadcastReceiver + PowerManager)
 *   2. Device locked/unlocked state (via KeyguardManager + USER_PRESENT broadcast)
 *   3. Foreground app name (via UsageStatsManager)
 *   4. Battery level (via BatteryManager) — Phase 3
 *
 * Sends changes to the Orchestrator via FirebaseManager (Firebase RTDB):
 *   Path: /rails/devices/my_phone/phone_state
 *   Payload: { "screen_on": true, "app": "...", "app_name": "...", "device_locked": false,
 *              "timestamp": ..., "battery_level": 85, "notifications": [], "screen_text": "" }
 *
 * Also monitors blocked apps (from FirebaseManager.blockedApps) and triggers
 * overlay when a blocked app becomes the foreground app.
 */
class SensorService : Service() {

    companion object {
        const val TAG = "Rails/Sensor"
        const val CHANNEL_ID = "rails_sensor"
        const val CHANNEL_ID_ALERTS = "rails_alerts"  // High-priority for interventions
        const val CHANNEL_ID_CHAT = "rails_chat"    // Chat messages from AI
        const val CHANNEL_ID_BLOCK = "rails_block"  // App blocking notifications
        const val NOTIFICATION_ID = 1001
        const val BLOCK_NOTIFICATION_ID = 3001

        const val ACTION_START = "cz.julek.rails.action.START_SENSOR"
        const val ACTION_STOP = "cz.julek.rails.action.STOP_SENSOR"

        // How often to poll UsageStats for foreground app (seconds)
        private const val POLL_INTERVAL_S = 3L

        // How often to report battery level (seconds) — less frequent than app polling
        private const val BATTERY_INTERVAL_S = 60L
    }

    private var screenReceiver: ScreenReceiver? = null
    private var isScreenOn: Boolean = false
    private var isDeviceLocked: Boolean = true
    private var lastForegroundApp: String = ""
    private var lastBatteryLevel: Int? = null
    private var scheduler: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Block notification tracking (AppWatcherService handles the actual kicking)
    private var isBlockNotificationShown: Boolean = false

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

        // Create ALL notification channels
        createNotificationChannel()
        createAlertNotificationChannel()
        createChatNotificationChannel()
        createBlockNotificationChannel()

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

        // Register Firebase command callbacks (includes intervention notifications)
        registerFirebaseCallbacks()

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

        // ── Read initial battery level ──
        lastBatteryLevel = getBatteryLevel()
        Log.i(TAG, "Initial battery level: $lastBatteryLevel%")

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
    //  Firebase Command Callbacks (Phase 2)
    // ═══════════════════════════════════════════════════════════════════

    private fun registerFirebaseCallbacks() {
        // INTERVENE — show notification + optional overlay
        FirebaseManager.onIntervene = { message ->
            Log.w(TAG, "INTERVENE callback: $message")
            showInterventionNotification(message)
        }

        // CHAT MESSAGE — show notification when AI responds (user may be outside app)
        FirebaseManager.onChatMessage = { text ->
            Log.i(TAG, "Chat message callback: ${text.substring(0, minOf(60, text.length))}")
            showChatNotification(text)
        }

        // BLOCK_APPS — show notification (AppWatcherService handles kicking)
        FirebaseManager.onBlockApps = { apps, message ->
            Log.w(TAG, "BLOCK_APPS callback: apps=$apps message=$message")
            // Show a block notification immediately
            val currentApp = lastForegroundApp
            if (currentApp.isNotEmpty()) {
                val isBlocked = apps.any { blockedApp ->
                    currentApp.equals(blockedApp, ignoreCase = true) ||
                    FirebaseManager.getFriendlyAppName(currentApp).equals(blockedApp, ignoreCase = true) ||
                    currentApp.contains(blockedApp, ignoreCase = true)
                }
                if (isBlocked) {
                    showBlockNotification(FirebaseManager.getFriendlyAppName(currentApp))
                    isBlockNotificationShown = true
                }
            }
        }

        // UNBLOCK_APPS — cancel block notification
        FirebaseManager.onUnblockApps = { message ->
            Log.i(TAG, "UNBLOCK_APPS callback: $message")
            isBlockNotificationShown = false
            cancelBlockNotification()
        }

        // LOCK_SCREEN — show lock-style overlay
        FirebaseManager.onLockScreen = { message ->
            Log.w(TAG, "LOCK_SCREEN callback: $message")
            startOverlayService("LOCK", message)
        }

        // CLEAR — remove all overlays + notifications
        FirebaseManager.onClear = {
            Log.i(TAG, "CLEAR callback")
            isBlockNotificationShown = false
            cancelBlockNotification()
        }
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
            addAction(Intent.ACTION_BATTERY_CHANGED) // Battery level changes
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

            // If screen turned on and unlocked, AppWatcherService will
            // handle blocking automatically via Accessibility events
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

                    // Blocking is handled by AppWatcherService (AccessibilityService)
                    // which detects app switches instantly — no polling needed
                }
            } catch (e: Exception) {
                Log.e(TAG, "App polling error: ${e.message}")
            }
        }, POLL_INTERVAL_S, POLL_INTERVAL_S, TimeUnit.SECONDS)

        // Battery level polling (less frequent)
        scheduler?.scheduleAtFixedRate({
            try {
                val currentLevel = getBatteryLevel()
                if (currentLevel != null && currentLevel != lastBatteryLevel) {
                    lastBatteryLevel = currentLevel
                    Log.d(TAG, "Battery level changed: $currentLevel%")
                    // Send state update with new battery info
                    // (only if something else also changed, to avoid excessive Firebase writes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Battery polling error: ${e.message}")
            }
        }, BATTERY_INTERVAL_S, BATTERY_INTERVAL_S, TimeUnit.SECONDS)
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
    //  Battery Level (Phase 3)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get current battery level as a percentage (0-100).
     * Uses BatteryManager which doesn't require a sticky broadcast.
     */
    private fun getBatteryLevel(): Int? {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read battery level: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Blocked Apps — Notification only (kicking handled by AppWatcherService)
    // ═══════════════════════════════════════════════════════════════════

    // Note: The actual home-screen kick is done by AppWatcherService
    // (AccessibilityService), which detects app switches instantly.
    // SensorService only shows/cancels the block notification.

    // ═══════════════════════════════════════════════════════════════════
    //  Overlay Service Control (Phase 2)
    // ═══════════════════════════════════════════════════════════════════

    private fun startOverlayService(type: String, message: String) {
        // Check overlay permission before attempting to show
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot show overlay — SYSTEM_ALERT_WINDOW permission not granted! " +
                    "User must enable it in Settings > Apps > Special access > Display over other apps")
            return
        }

        val intent = Intent(this, cz.julek.rails.overlay.OverlayService::class.java).apply {
            action = when (type) {
                "BLOCK" -> cz.julek.rails.overlay.OverlayService.ACTION_INTERVENE
                "LOCK" -> cz.julek.rails.overlay.OverlayService.ACTION_LOCK_SCREEN
                else -> cz.julek.rails.overlay.OverlayService.ACTION_INTERVENE
            }
            putExtra(cz.julek.rails.overlay.OverlayService.EXTRA_MESSAGE, message)
        }
        Log.w(TAG, "Starting OverlayService: type=$type message=${message.substring(0, minOf(60, message.length))}")
        startService(intent)
    }

    private fun stopOverlayService() {
        val intent = Intent(this, cz.julek.rails.overlay.OverlayService::class.java).apply {
            action = cz.julek.rails.overlay.OverlayService.ACTION_CLEAR
        }
        startService(intent)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Send State to Orchestrator via Firebase (Phase 3 — extended)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send the current phone state to the Orchestrator via Firebase.
     *
     * Before sending, we double-check the screen state using PowerManager
     * to ensure we never send stale/incorrect data.
     *
     * Firebase path: /rails/devices/my_phone/phone_state
     * Payload: { "screen_on": true, "app": "...", "app_name": "...", "device_locked": false,
     *            "timestamp": ..., "battery_level": 85 }
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

        // Send via Firebase (with Phase 3 battery level)
        FirebaseManager.sendState(
            screenOn = isScreenOn,
            foregroundApp = app,
            deviceLocked = effectiveLocked,
            batteryLevel = lastBatteryLevel,
        )

        Log.d(TAG, "State sent: screen_on=$isScreenOn, device_locked=$effectiveLocked, " +
                "app=$app, battery=$lastBatteryLevel%")
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
            description = "Senzor sledovani aktivity na pozadi"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * High-priority notification channel for AI interventions.
     * This channel WILL buzz, vibrate, and show as heads-up notification.
     */
    private fun createAlertNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            "Rails Intervence",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI varování a intervence při prokrastinaci"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 100, 300)
            enableLights(true)
            lightColor = 0xFFFF6F00.toInt()  // Amber
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Notification channel for AI chat responses.
     * Uses IMPORTANCE_HIGH so it makes sound, vibrates, and shows as heads-up.
     * The user sees these when the AI replies while the app is in the background.
     */
    private fun createChatNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_CHAT,
            "Rails Chat",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Zprávy od AI asistenta"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
            enableLights(true)
            lightColor = 0xFF1976D2.toInt()  // Blue
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Show a high-priority notification when the AI Judge intervenes.
     * Uses the alerts channel which has sound + vibration.
     */
    private fun showInterventionNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setContentTitle("Rails — Pozor!")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(2001, notification)

        Log.i(TAG, "Intervention notification shown: ${message.substring(0, minOf(60, message.length))}")
    }

    /**
     * Show a notification when the AI sends a chat response.
     * Uses a separate chat channel with sound + vibration so the user
     * is alerted even when the app is in the background.
     */
    private fun showChatNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Truncate for notification preview (full text in BigTextStyle)
        val previewText = if (text.length > 80) text.substring(0, 80) + "..." else text

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_CHAT)
            .setContentTitle("Rails")
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        // Use a unique ID based on timestamp so multiple messages don't overwrite
        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notification)

        Log.i(TAG, "Chat notification shown: ${text.substring(0, minOf(60, text.length))}")
    }

    /**
     * Notification channel for app blocking — shown when a blocked app
     * is detected and user is kicked to home screen.
     * Uses IMPORTANCE_DEFAULT so it doesn't make noise every time
     * (user gets kicked to home, notification is just the explanation).
     */
    private fun createBlockNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_BLOCK,
            "Rails Blokování",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Upozornění při zablokování aplikace"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 100)
            enableLights(true)
            lightColor = 0xFFD32F2F.toInt()  // Red
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Show an ongoing notification when a blocked app is detected.
     * Stays visible until the app is unblocked — reminds the user
     * why they were kicked to the home screen.
     */
    private fun showBlockNotification(appName: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_BLOCK)
            .setContentTitle("$appName je zablokována")
            .setContentText("Vrať se k práci! Aplikace bude odblokována automaticky.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)  // Cannot be swiped away — stays until unblocked
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(BLOCK_NOTIFICATION_ID, notification)

        Log.i(TAG, "Block notification shown for: $appName")
    }

    /**
     * Cancel the block notification when apps are unblocked.
     */
    private fun cancelBlockNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(BLOCK_NOTIFICATION_ID)
        Log.i(TAG, "Block notification cancelled")
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rails senzor aktivni")
            .setContentText("Sleduji aktivitu a odesilam data pres Firebase")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
