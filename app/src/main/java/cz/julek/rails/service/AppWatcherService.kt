package cz.julek.rails.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import cz.julek.rails.network.FirebaseManager

/**
 * App Watcher Service — DIAGNOSTIC BUILD
 *
 * This version has extensive logging to diagnose why blocking stops
 * after 2-3 attempts. Please check Logcat filtered by "Rails/" after
 * reproducing the bug.
 *
 * Key diagnostic features:
 *   - Heartbeat log every 3 seconds (shows if monitor loop is alive)
 *   - Every isBlocked() call logged
 *   - Every kickWithCooldown call logged with result
 *   - Try-catch around EVERYTHING so the loop never silently dies
 *   - Self-healing: if loop dies, watchdog restarts it in 5 seconds
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"
        private const val PREFS_NAME = "rails_blocked_apps"
        private const val KEY_BLOCKED_APPS = "blocked_apps_set"
        private const val MONITOR_INTERVAL_MS = 500L
        private const val KICK_COOLDOWN_MS = 800L
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val WATCHDOG_INTERVAL_MS = 5000L

        private var currentForegroundPackage: String = ""
        private var lastKickTime: Long = 0
        private var kickCount: Int = 0
        private var skipCount: Int = 0

        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning

        private var instance: AppWatcherService? = null
        private val handler = Handler(Looper.getMainLooper())

        private var monitorRunnable: Runnable? = null
        @Volatile private var isMonitoring: Boolean = false

        // Cached blocked apps — avoid reading SharedPreferences every 500ms
        @Volatile private var cachedBlockedApps: Set<String> = emptySet()
        private var lastBlockedAppsUpdate: Long = 0

        fun saveBlockedApps(context: Context, apps: List<String>) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_BLOCKED_APPS, apps.toSet()).apply()
            cachedBlockedApps = apps.toSet()
            lastBlockedAppsUpdate = System.currentTimeMillis()
            Log.i(TAG, "💾 Saved ${apps.size} blocked apps: $apps (cache updated)")
        }

        fun loadBlockedApps(context: Context): Set<String> {
            // Use cache if fresh (< 2 seconds old)
            if (cachedBlockedApps.isNotEmpty() &&
                System.currentTimeMillis() - lastBlockedAppsUpdate < 2000) {
                return cachedBlockedApps
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val loaded = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
            cachedBlockedApps = loaded
            lastBlockedAppsUpdate = System.currentTimeMillis()
            return loaded
        }

        fun clearBlockedApps(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_BLOCKED_APPS).apply()
            cachedBlockedApps = emptySet()
            Log.i(TAG, "🗑️ Cleared blocked apps (cache cleared)")
        }

        /**
         * Update cache from Firebase — called by monitor loop
         */
        private fun refreshBlockedAppsCache() {
            val firebaseBlocked = FirebaseManager.blockedApps.value
            if (firebaseBlocked.isNotEmpty()) {
                cachedBlockedApps = firebaseBlocked.toSet()
                lastBlockedAppsUpdate = System.currentTimeMillis()
            }
        }

        fun startMonitoring() {
            if (isMonitoring) {
                Log.d(TAG, "⚠️ startMonitoring called but already monitoring")
                return
            }
            isMonitoring = true
            Log.i(TAG, "▶️ Starting monitor loop (every ${MONITOR_INTERVAL_MS}ms)")

            monitorRunnable = object : Runnable {
                override fun run() {
                    if (!isMonitoring) {
                        Log.w(TAG, "🛑 Monitor loop stopped — isMonitoring=false")
                        return
                    }

                    try {
                        val svc = instance
                        if (svc == null) {
                            Log.w(TAG, "⚠️ Monitor: instance=null, retrying in 1.5s")
                            handler.postDelayed(this, 1500L)
                            return
                        }

                        // Refresh blocked apps cache from Firebase
                        refreshBlockedAppsCache()

                        val fg = svc.getForegroundAppViaUsageStats() ?: currentForegroundPackage
                        if (fg.isNotEmpty()) {
                            currentForegroundPackage = fg

                            val isOwn = fg.startsWith("cz.julek.rails")
                            val isLaunch = svc.isLauncher(fg)
                            val blocked = svc.isBlocked(fg)

                            if (!isOwn && !isLaunch && blocked) {
                                val appName = FirebaseManager.getFriendlyAppName(fg)
                                Log.w(TAG, "🔴 Monitor: BLOCKED $appName ($fg) — kicking!")
                                svc.kickWithCooldown(fg)
                            }
                        }

                        handler.postDelayed(this, MONITOR_INTERVAL_MS)

                    } catch (e: Exception) {
                        Log.e(TAG, "💥 Monitor loop CRASHED: ${e.message}", e)
                        // Self-heal: restart the loop after a delay
                        handler.postDelayed(this, 2000L)
                    }
                }
            }

            handler.post(monitorRunnable!!)
        }

        fun stopMonitoring() {
            isMonitoring = false
            monitorRunnable?.let { handler.removeCallbacks(it) }
            monitorRunnable = null
            Log.i(TAG, "⏹️ Stopped monitoring")
        }

        fun forceCheckAndKick() {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "❌ forceCheckAndKick: instance=null! Service not running!")
                return
            }

            val fg = svc.getForegroundAppViaUsageStats() ?: currentForegroundPackage
            if (fg.isEmpty()) {
                Log.w(TAG, "⚠️ forceCheckAndKick: fg is empty")
                return
            }

            currentForegroundPackage = fg
            Log.i(TAG, "🔄 forceCheckAndKick: fg=$fg, isLauncher=${svc.isLauncher(fg)}, isBlocked=${svc.isBlocked(fg)}")

            if (fg.startsWith("cz.julek.rails") || svc.isLauncher(fg)) return

            if (svc.isBlocked(fg)) {
                val appName = FirebaseManager.getFriendlyAppName(fg)
                Log.w(TAG, "🔴 forceCheckAndKick: BLOCKED $appName ($fg) — kicking!")
                svc.kickWithCooldown(fg, force = true)
            } else {
                Log.i(TAG, "✅ forceCheckAndKick: $fg is NOT blocked")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Overlay
    // ═══════════════════════════════════════════════════════════════════

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayShowing = false

    private fun createOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = FrameLayout(this).apply {
            alpha = 0f
            setBackgroundColor(0xFF1A1A2E.toInt())

            val textView = TextView(this@AppWatcherService).apply {
                text = "Tato aplikace je zablokovana\nVrat se k praci!"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            }
            addView(textView)

            setOnClickListener {
                Log.i(TAG, "👆 Overlay tapped — force kicking")
                kickWithCooldown(currentForegroundPackage, force = true)
            }
        }

        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            windowManager?.addView(overlayView, overlayLayoutParams)
            Log.i(TAG, "🪟 Overlay pre-created (invisible)")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to create overlay: ${e.message}")
            overlayView = null
        }
    }

    fun showOverlay(appName: String) {
        if (overlayView == null) createOverlay()
        try {
            (overlayView as? FrameLayout)?.let { frame ->
                (frame.getChildAt(0) as? TextView)?.let { tv ->
                    tv.text = "$appName je zablokovana\nVrat se k praci!"
                }
            }
            overlayView?.alpha = 1f
            overlayLayoutParams?.let { windowManager?.updateViewLayout(overlayView, it) }
            isOverlayShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "💥 showOverlay failed: ${e.message}")
        }
    }

    fun hideOverlay() {
        if (!isOverlayShowing) return
        try {
            overlayView?.alpha = 0f
            isOverlayShowing = false
        } catch (e: Exception) { /* ignore */ }
    }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        isOverlayShowing = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Service Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning = true
        instance = this
        kickCount = 0
        skipCount = 0

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0
        }

        createOverlay()

        // Load blocked apps from local storage
        val localBlocked = loadBlockedApps(this)
        val firebaseBlocked = FirebaseManager.blockedApps.value
        Log.i(TAG, "🚀 AppWatcher CONNECTED — local blocked: $localBlocked, firebase blocked: $firebaseBlocked")

        // Always start monitoring
        startMonitoring()

        // Start heartbeat
        startHeartbeat()

        // Start watchdog
        startWatchdog()

        // Force-check in case blocked app is already open
        handler.postDelayed({ forceCheckAndKick() }, 500L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val packageName = event.packageName?.toString() ?: return
            if (packageName.isEmpty()) return

            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                checkAllVisibleWindows()
                return
            }

            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

            currentForegroundPackage = packageName

            if (packageName.startsWith("cz.julek.rails")) {
                hideOverlay()
                return
            }
            if (isLauncher(packageName)) {
                hideOverlay()
                return
            }

            val blocked = isBlocked(packageName)
            Log.d(TAG, "📡 Event: $packageName — isLauncher=${isLauncher(packageName)}, isBlocked=$blocked")

            if (!blocked) return

            Log.w(TAG, "🔴 Event: BLOCKED $packageName detected — kicking!")
            kickWithCooldown(packageName)

        } catch (e: Exception) {
            Log.e(TAG, "💥 onAccessibilityEvent CRASHED: ${e.message}", e)
        }
    }

    private fun checkAllVisibleWindows() {
        try {
            val wins = windows ?: return
            for (window in wins) {
                val pkg = window.root?.packageName?.toString() ?: continue
                if (pkg.isNotEmpty() && !pkg.startsWith("cz.julek.rails") && !isLauncher(pkg) && isBlocked(pkg)) {
                    Log.w(TAG, "🔴 WindowsChanged: BLOCKED $pkg — kicking!")
                    kickWithCooldown(pkg)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 checkAllVisibleWindows CRASHED: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Foreground Detection
    // ═══════════════════════════════════════════════════════════════════

    private fun getForegroundAppViaUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) {
                Log.e(TAG, "❌ UsageStatsManager is NULL — permission not granted?")
                return null
            }

            val now = System.currentTimeMillis()
            val usageEvents = usageStatsManager.queryEvents(now - 5000, now)
            var lastFg: UsageEvents.Event? = null

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastFg = event
                }
            }

            lastFg?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "💥 UsageStats query CRASHED: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Kick with Cooldown
    // ═══════════════════════════════════════════════════════════════════

    private fun kickWithCooldown(packageName: String, force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val timeSinceLastKick = now - lastKickTime

            if (!force && timeSinceLastKick < KICK_COOLDOWN_MS) {
                skipCount++
                Log.d(TAG, "⏳ Cooldown: ${timeSinceLastKick}ms < ${KICK_COOLDOWN_MS}ms — skipping (total skips: $skipCount)")
                return
            }
            lastKickTime = now
            kickCount++

            val appName = FirebaseManager.getFriendlyAppName(packageName)
            Log.w(TAG, "🔨 KICK #$kickCount: $appName ($packageName) [force=$force]")

            // Layer 1: System-level home action
            val globalResult = performGlobalAction(GLOBAL_ACTION_HOME)
            Log.d(TAG, "  Layer 1: performGlobalAction(HOME) = $globalResult")

            // Layer 2: Home intent
            goToHomeScreen()

            // Layer 3: Overlay
            showOverlay(appName)

            // Layer 4: Broadcast
            notifyBlocked(appName)

            Log.i(TAG, "✅ Kick #$kickCount completed")

        } catch (e: Exception) {
            Log.e(TAG, "💥 kickWithCooldown CRASHED: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Blocked App Detection — with logging
    // ═══════════════════════════════════════════════════════════════════

    fun isBlocked(packageName: String): Boolean {
        val firebaseBlocked = FirebaseManager.blockedApps.value
        if (firebaseBlocked.isNotEmpty()) {
            if (firebaseBlocked.any { matchesBlocked(packageName, it) }) return true
        }

        val localBlocked = loadBlockedApps(this)
        if (localBlocked.isNotEmpty()) {
            if (localBlocked.any { matchesBlocked(packageName, it) }) return true
        }

        return false
    }

    private fun matchesBlocked(packageName: String, blockedApp: String): Boolean {
        return packageName.equals(blockedApp, ignoreCase = true) ||
                FirebaseManager.getFriendlyAppName(packageName).equals(blockedApp, ignoreCase = true) ||
                packageName.contains(blockedApp, ignoreCase = true)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Heartbeat — proves the service is alive
    // ═══════════════════════════════════════════════════════════════════

    private var heartbeatRunnable: Runnable? = null

    private fun startHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val fb = FirebaseManager.blockedApps.value
                val local = cachedBlockedApps
                Log.i(TAG, "💓 HEARTBEAT — kicks=$kickCount, skips=$skipCount, " +
                        "monitoring=$isMonitoring, instance=${instance != null}, " +
                        "fg=$currentForegroundPackage, " +
                        "firebaseBlocked=$fb, localBlocked=$local")
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Watchdog — restarts monitor if it dies
    // ═══════════════════════════════════════════════════════════════════

    private var watchdogRunnable: Runnable? = null

    private fun startWatchdog() {
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring && _isRunning) {
                    Log.e(TAG, "🐕 WATCHDOG: Monitor loop died! Restarting...")
                    startMonitoring()
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Kick Helpers
    // ═══════════════════════════════════════════════════════════════════

    fun goToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "💥 goToHomeScreen failed: ${e.message}")
        }
    }

    private fun notifyBlocked(appName: String) {
        try {
            val intent = Intent("cz.julek.rails.ACTION_APP_BLOCKED").apply {
                putExtra("app_name", appName)
                setPackage("cz.julek.rails")
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "💥 notifyBlocked failed: ${e.message}")
        }
    }

    fun isLauncher(packageName: String): Boolean {
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
        )
        if (knownLaunchers.contains(packageName)) return true

        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, 0)
            resolveInfos.any { it.activityInfo.packageName == packageName }
        } catch (e: Exception) {
            Log.e(TAG, "💥 isLauncher query failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ onInterrupt() called — system is interrupting the service!")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "💀 AppWatcher DESTROYED — this is BAD! Service was killed!")
        _isRunning = false
        instance = null
        stopMonitoring()
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
    }
}
