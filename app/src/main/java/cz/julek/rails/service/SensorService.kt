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
import android.os.Build
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
 * Rails Sensor Service — Foreground Service (PRIMARY BLOCKER)
 *
 * Runs continuously and:
 *   1. Collects sensor data (screen, lock, foreground app, battery)
 *   2. Sends state to Firebase for the Orchestrator
 *   3. BLOCKS apps when BLOCK_APPS command is received (500ms polling)
 *
 * ARCHITECTURE:
 *   - SensorService is the PRIMARY blocker — uses UsageStatsManager polling
 *   - AppWatcherService is the OPTIONAL fast-path — uses AccessibilityService events
 *   - Blocking works regardless of whether AppWatcherService is enabled
 *
 * BLOCKING FLOW:
 *   1. BLOCK_APPS command → FirebaseManager → onBlockApps callback
 *   2. SensorService starts a 500ms polling monitor
 *   3. On each poll: getForegroundApp() → isBlockedApp() → kickToHomeScreen()
 *   4. Continues until UNBLOCK_APPS is received
 *   5. On app restart: loads persisted blocked apps from SharedPreferences
 */
class SensorService : Service() {

    companion object {
        const val TAG = "Rails/Sensor"
        const val CHANNEL_ID = "rails_sensor"
        const val CHANNEL_ID_ALERTS = "rails_alerts"
        const val CHANNEL_ID_CHAT = "rails_chat"
        const val CHANNEL_ID_BLOCK = "rails_block"
        const val NOTIFICATION_ID = 1001
        const val BLOCK_NOTIFICATION_ID = 3001

        const val ACTION_START = "cz.julek.rails.action.START_SENSOR"
        const val ACTION_STOP = "cz.julek.rails.action.STOP_SENSOR"

        // Normal polling interval (seconds) — for state reporting
        private const val POLL_INTERVAL_S = 3L

        // Battery polling interval (seconds)
        private const val BATTERY_INTERVAL_S = 60L

        // Blocking monitor interval (milliseconds) — fast polling for app blocking
        private const val BLOCK_POLL_INTERVAL_MS = 500L

        // Cooldown between kicks to avoid spam
        private const val KICK_COOLDOWN_MS = 800L
    }

    // ═══════════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════════

    private var screenReceiver: ScreenReceiver? = null
    private var isScreenOn: Boolean = false
    private var isDeviceLocked: Boolean = true
    private var lastForegroundApp: String = ""
    private var lastBatteryLevel: Int? = null
    private var scheduler: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isBlockNotificationShown: Boolean = false

    // ── Blocking state ──
    private var activeBlockedApps: List<String> = emptyList()
    private var isBlocking: Boolean = false
    private var blockScheduler: ScheduledExecutorService? = null
    private var lastKickTime: Long = 0
    private var kickCount: Int = 0

    // ── Launcher detection cache ──
    private var cachedLauncherPackages: Set<String>? = null

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

        // Start foreground notification — use specialUse on Android 14+ to avoid
        // the 6-hour dataSync timeout that causes ForegroundServiceDidNotStopInTimeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Connect to Firebase
        FirebaseManager.connect(this)

        // Register Firebase command callbacks
        registerFirebaseCallbacks()

        // ── Detect initial screen state ──
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

        // ── Restore blocking from persisted state ──
        // If the app was killed and restarted while blocking was active,
        // we need to resume blocking from SharedPreferences
        val persistedBlocked = AppWatcherService.loadBlockedApps(this).toList()
        if (persistedBlocked.isNotEmpty()) {
            activeBlockedApps = persistedBlocked
            isBlocking = true
            startBlockingMonitor()
            Log.i(TAG, "Restored ${persistedBlocked.size} blocked apps from persistence: $persistedBlocked")

            // Also sync with Firebase's current state
            val firebaseBlocked = FirebaseManager.blockedApps.value
            if (firebaseBlocked.isNotEmpty()) {
                activeBlockedApps = firebaseBlocked
            }
        }

        // Send initial state
        sendCurrentState()
    }

    private fun stopSensor() {
        Log.i(TAG, "Stopping sensor service")

        // Stop blocking monitor
        stopBlockingMonitor()

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

        // Properly stop foreground to avoid ForegroundServiceDidNotStopInTimeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Firebase Command Callbacks
    // ═══════════════════════════════════════════════════════════════════

    private fun registerFirebaseCallbacks() {
        // INTERVENE — show notification + optional overlay
        FirebaseManager.onIntervene = { message ->
            Log.w(TAG, "INTERVENE callback: $message")
            showInterventionNotification(message)
        }

        // CHAT MESSAGE — show notification when AI responds
        FirebaseManager.onChatMessage = { text ->
            Log.i(TAG, "Chat message callback: ${text.substring(0, minOf(60, text.length))}")
            showChatNotification(text)
        }

        // BLOCK_APPS — PRIMARY BLOCKING: start 500ms polling monitor
        FirebaseManager.onBlockApps = { apps, message ->
            Log.w(TAG, "BLOCK_APPS callback: apps=$apps — starting blocking monitor")

            // Update blocking state
            activeBlockedApps = apps
            isBlocking = true
            kickCount = 0

            // Start the fast polling monitor
            startBlockingMonitor()

            // Show block notification
            val currentApp = lastForegroundApp
            if (currentApp.isNotEmpty() && isBlockedApp(currentApp)) {
                val appName = FirebaseManager.getFriendlyAppName(currentApp)
                showBlockNotification(appName)
                isBlockNotificationShown = true

                // Immediate kick — don't wait for the next poll
                kickToHomeScreen()
                kickCount++
                Log.w(TAG, "Immediate kick #$kickCount: $appName was in foreground")
            } else {
                // Show generic block notification
                val appNames = apps.map { FirebaseManager.getFriendlyAppName(it) }
                showBlockNotification(appNames.joinToString(", "))
                isBlockNotificationShown = true
            }
        }

        // UNBLOCK_APPS — stop blocking monitor
        FirebaseManager.onUnblockApps = { message ->
            Log.i(TAG, "UNBLOCK_APPS callback: $message")
            isBlockNotificationShown = false
            cancelBlockNotification()
            stopBlockingMonitor()
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
            stopBlockingMonitor()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  App Blocking — PRIMARY (does NOT require AccessibilityService)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if a package name matches any of the currently blocked apps.
     * Supports matching by:
     *   - Exact package name (e.g., "com.instagram.android")
     *   - Friendly name (e.g., "Instagram")
     *   - Substring contains (e.g., "instagram" matches "com.instagram.android")
     */
    private fun isBlockedApp(packageName: String): Boolean {
        if (activeBlockedApps.isEmpty()) return false
        return activeBlockedApps.any { blocked ->
            packageName.equals(blocked, ignoreCase = true) ||
            FirebaseManager.getFriendlyAppName(packageName).equals(blocked, ignoreCase = true) ||
            packageName.contains(blocked, ignoreCase = true)
        }
    }

    /**
     * Check if a package is a home screen launcher.
     * Uses a hardcoded list of known launchers + dynamic PackageManager query.
     * Results are cached for performance.
     */
    private fun isLauncher(packageName: String): Boolean {
        // Hardcoded list of known launchers
        val knownLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.android.launcher",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.vivo.abe",
            "com.nothing.launcher",
            "com.oneplus.launcher",
            "com.sonyericsson.home",
            "com.lge.launcher2",
            "com.android.systemui",
            "com.teslacoilsw.launcher",
            "com.actionlauncher.playstore",
            "net.oneplus.launcher",
            "com.google.android.launcher",
            "com.microsoft.launcher",
            "ginlemon.flowerfree",       // Hola Launcher / Flower Free
            "ginlemon.launcher",         // Hola Launcher variant
        )
        if (knownLaunchers.contains(packageName)) return true

        // Dynamic check via PackageManager (with caching)
        cachedLauncherPackages?.let { cached ->
            return cached.contains(packageName)
        }

        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, 0)
            val launchers = resolveInfos.map { it.activityInfo.packageName }.toSet()
            cachedLauncherPackages = launchers
            launchers.contains(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "isLauncher query failed: ${e.message}")
            false
        }
    }

    /**
     * Start the blocking monitor — polls foreground app every 500ms.
     * If a blocked app is detected, kicks user to home screen.
     * Continues until stopBlockingMonitor() is called.
     */
    private fun startBlockingMonitor() {
        // Stop any existing monitor first
        blockScheduler?.shutdownNow()
        blockScheduler = Executors.newSingleThreadScheduledExecutor()

        Log.i(TAG, "Blocking monitor started — polling every ${BLOCK_POLL_INTERVAL_MS}ms, " +
                "watching: ${activeBlockedApps.map { FirebaseManager.getFriendlyAppName(it) }}")

        blockScheduler?.scheduleAtFixedRate({
            try {
                if (!isBlocking) return@scheduleAtFixedRate

                val fg = getForegroundApp() ?: return@scheduleAtFixedRate

                // Skip our own app
                if (fg.startsWith("cz.julek.rails")) return@scheduleAtFixedRate

                // Skip launchers (user is on home screen)
                if (isLauncher(fg)) return@scheduleAtFixedRate

                // Check if this is a blocked app
                if (isBlockedApp(fg)) {
                    val now = System.currentTimeMillis()
                    if (now - lastKickTime > KICK_COOLDOWN_MS) {
                        lastKickTime = now
                        kickCount++
                        val appName = FirebaseManager.getFriendlyAppName(fg)
                        Log.w(TAG, "BLOCKED: $appName ($fg) — kicking to home! (kick #$kickCount)")
                        kickToHomeScreen()

                        // Update block notification with current app name
                        showBlockNotification(appName)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Blocking monitor error: ${e.message}")
            }
        }, 0, BLOCK_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Stop the blocking monitor and clear blocking state.
     */
    private fun stopBlockingMonitor() {
        if (!isBlocking && blockScheduler == null) return
        blockScheduler?.shutdownNow()
        blockScheduler = null
        isBlocking = false
        activeBlockedApps = emptyList()
        kickCount = 0
        Log.i(TAG, "Blocking monitor stopped")
    }

    /**
     * Kick user to home screen using a HOME intent.
     * This is the primary blocking mechanism — works from any Service
     * (no AccessibilityService required).
     */
    private fun kickToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(homeIntent)
            Log.i(TAG, "Kick: user sent to home screen")
        } catch (e: Exception) {
            Log.e(TAG, "Kick failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Screen State + Lock Detection
    // ═══════════════════════════════════════════════════════════════════

    private fun registerScreenReceiver() {
        screenReceiver = ScreenReceiver()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }

        registerReceiver(screenReceiver, filter)

        // Callback from ScreenReceiver
        ScreenReceiver.onScreenEvent = { screenOn, deviceLocked ->
            val screenChanged = isScreenOn != screenOn
            val lockChanged = isDeviceLocked != deviceLocked

            isScreenOn = screenOn
            isDeviceLocked = deviceLocked

            Log.i(TAG, "State changed: isScreenOn=$isScreenOn, isDeviceLocked=$isDeviceLocked " +
                    "(screenChanged=$screenChanged, lockChanged=$lockChanged)")

            if (screenChanged || lockChanged) {
                sendCurrentState()
            }
        }
    }

    private fun refreshScreenState() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val realScreenOn = powerManager.isInteractive
            if (realScreenOn != isScreenOn) {
                Log.w(TAG, "Screen state drift detected! BroadcastReceiver=$isScreenOn, PowerManager=$realScreenOn — correcting")
                isScreenOn = realScreenOn
            }

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
                // Renew wake lock
                wakeLock?.let {
                    if (it.isHeld) {
                        it.acquire(4 * 60 * 60 * 1000L)
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

        // Battery level polling
        scheduler?.scheduleAtFixedRate({
            try {
                val currentLevel = getBatteryLevel()
                if (currentLevel != null && currentLevel != lastBatteryLevel) {
                    lastBatteryLevel = currentLevel
                    Log.d(TAG, "Battery level changed: $currentLevel%")
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
    //  Battery Level
    // ═══════════════════════════════════════════════════════════════════

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
    //  Overlay Service Control
    // ═══════════════════════════════════════════════════════════════════

    private fun startOverlayService(type: String, message: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot show overlay — SYSTEM_ALERT_WINDOW permission not granted!")
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
    //  Send State to Firebase
    // ═══════════════════════════════════════════════════════════════════

    private fun sendCurrentState() {
        refreshScreenState()

        val app = when {
            !isScreenOn -> ""
            isDeviceLocked -> ""
            else -> lastForegroundApp
        }

        val effectiveLocked = isDeviceLocked || !isScreenOn

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
    //  Notifications
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

    private fun createAlertNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            "Rails Intervence",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI varovani a intervence pri prokrastinaci"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 100, 300)
            enableLights(true)
            lightColor = 0xFFFF6F00.toInt()
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createChatNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_CHAT,
            "Rails Chat",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Zpravy od AI asistenta"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
            enableLights(true)
            lightColor = 0xFF1976D2.toInt()
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createBlockNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_BLOCK,
            "Rails Blokovani",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Upozorneni pri zablokovani aplikace"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 100)
            enableLights(true)
            lightColor = 0xFFD32F2F.toInt()
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

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

    private fun showChatNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notification)

        Log.i(TAG, "Chat notification shown: ${text.substring(0, minOf(60, text.length))}")
    }

    private fun showBlockNotification(appName: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_BLOCK)
            .setContentTitle("$appName je zablokovana")
            .setContentText("Vrat se k praci! Aplikace bude odblokovana automaticky.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(BLOCK_NOTIFICATION_ID, notification)

        Log.i(TAG, "Block notification shown for: $appName")
    }

    private fun cancelBlockNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(BLOCK_NOTIFICATION_ID)
        Log.i(TAG, "Block notification cancelled")
    }

    private fun buildNotification(): Notification {
        val blockingInfo = if (isBlocking) " | Blokovani aktivni" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rails senzor aktivni$blockingInfo")
            .setContentText("Sleduji aktivitu a odesilam data pres Firebase")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
