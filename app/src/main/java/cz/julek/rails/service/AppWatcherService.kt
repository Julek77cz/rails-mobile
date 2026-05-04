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
 * App Watcher Service — Bulletproof AccessibilityService for blocked app detection.
 *
 * DUAL MECHANISM (like AppBlock/Forest/Freedom):
 *   Mechanism 1: AccessibilityEvent (TYPE_WINDOW_STATE_CHANGED) — instant, 0ms
 *   Mechanism 2: UsageStatsManager polling every 500ms — reliable backup
 *
 * Why both? Accessibility events are sometimes NOT fired when an app is
 * resumed from recents (activity already exists, just being resumed).
 * UsageStatsManager is slower (~500ms) but 100% reliable.
 *
 * Defense layers when blocked app detected:
 *   1. performGlobalAction(GLOBAL_ACTION_HOME) — system-level
 *   2. startActivity(homeIntent) — ALWAYS, not just as fallback
 *   3. TYPE_ACCESSIBILITY_OVERLAY — visual barrier
 *   4. Delayed re-checks (500ms, 1s, 2s)
 *   5. Local persistence (SharedPreferences)
 *   6. forceCheckAndKick() — when BLOCK_APPS arrives while app is already open
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"
        private const val PREFS_NAME = "rails_blocked_apps"
        private const val KEY_BLOCKED_APPS = "blocked_apps_set"
        private const val MONITOR_INTERVAL_MS = 500L

        /**
         * Current foreground package — tracked by BOTH accessibility events
         * AND UsageStatsManager polling. The polling is the reliable one.
         */
        private var currentForegroundPackage: String = ""

        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning

        private var instance: AppWatcherService? = null
        private val handler = Handler(Looper.getMainLooper())

        // ── Continuous Monitoring ──
        private var monitorRunnable: Runnable? = null
        private var isMonitoring: Boolean = false

        fun saveBlockedApps(context: Context, apps: List<String>) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_BLOCKED_APPS, apps.toSet()).apply()
            Log.i(TAG, "Saved ${apps.size} blocked apps locally: $apps")
        }

        fun loadBlockedApps(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        }

        fun clearBlockedApps(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_BLOCKED_APPS).apply()
        }

        /**
         * Start the continuous monitoring loop.
         * Every 500ms, checks the foreground app via UsageStatsManager.
         * This is the RELIABLE backup for accessibility events.
         * Called when blocked apps are set / when service connects.
         */
        fun startMonitoring() {
            if (isMonitoring) return
            isMonitoring = true
            Log.i(TAG, "Starting continuous monitoring (every ${MONITOR_INTERVAL_MS}ms)")

            monitorRunnable = object : Runnable {
                override fun run() {
                    val svc = instance ?: return

                    // Check if there are any blocked apps at all
                    val hasBlockedApps = FirebaseManager.blockedApps.value.isNotEmpty() ||
                            loadBlockedApps(svc).isNotEmpty()

                    if (!hasBlockedApps) {
                        // No blocked apps — stop monitoring to save battery
                        isMonitoring = false
                        Log.d(TAG, "No blocked apps — stopping monitoring loop")
                        return
                    }

                    // Get the REAL foreground app via UsageStatsManager
                    val fg = svc.getForegroundAppViaUsageStats()
                    if (fg != null && fg != currentForegroundPackage) {
                        Log.d(TAG, "Monitor: foreground changed $currentForegroundPackage → $fg")
                        currentForegroundPackage = fg

                        // Is it blocked?
                        if (!svc.isLauncher(fg) && !fg.startsWith("cz.julek.rails") && svc.isBlocked(fg)) {
                            val appName = FirebaseManager.getFriendlyAppName(fg)
                            Log.w(TAG, "Monitor: BLOCKED app $appName ($fg) detected — kicking!")
                            svc.engageAllDefenseLayers(fg)
                        }
                    } else if (fg != null && fg == currentForegroundPackage && svc.isBlocked(fg) && !svc.isLauncher(fg)) {
                        // Same package as before but still blocked — maybe our kick failed.
                        // Re-engage to be safe.
                        Log.w(TAG, "Monitor: BLOCKED app ($fg) still in foreground — re-engaging!")
                        svc.engageAllDefenseLayers(fg)
                    }

                    // Schedule next check
                    if (isMonitoring) {
                        handler.postDelayed(this, MONITOR_INTERVAL_MS)
                    }
                }
            }

            handler.post(monitorRunnable!!)
        }

        /**
         * Stop the continuous monitoring loop.
         */
        fun stopMonitoring() {
            isMonitoring = false
            monitorRunnable?.let { handler.removeCallbacks(it) }
            monitorRunnable = null
            Log.i(TAG, "Stopped continuous monitoring")
        }

        fun forceCheckAndKick() {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "forceCheckAndKick: service not running!")
                return
            }

            // Use UsageStatsManager for the MOST RELIABLE foreground check
            val fg = svc.getForegroundAppViaUsageStats() ?: currentForegroundPackage
            if (fg.isEmpty()) return
            if (fg.startsWith("cz.julek.rails") || svc.isLauncher(fg)) return

            currentForegroundPackage = fg

            if (svc.isBlocked(fg)) {
                val appName = FirebaseManager.getFriendlyAppName(fg)
                Log.w(TAG, "forceCheckAndKick: BLOCKED $appName ($fg) — engaging!")
                svc.engageAllDefenseLayers(fg)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Overlay — Pre-created for instant display
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

            setOnClickListener { engageAllDefenseLayers(currentForegroundPackage) }
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
            Log.i(TAG, "Overlay pre-created (invisible)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: ${e.message}")
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
            Log.e(TAG, "Failed to show overlay: ${e.message}")
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

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }

        createOverlay()

        // If there are already blocked apps, start monitoring immediately
        val localBlocked = loadBlockedApps(this)
        if (localBlocked.isNotEmpty() || FirebaseManager.blockedApps.value.isNotEmpty()) {
            startMonitoring()
            handler.postDelayed({ forceCheckAndKick() }, 500L)
        }

        Log.i(TAG, "AppWatcher connected — dual mechanism (events + polling)")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Mechanism 1: Accessibility Events (instant, but unreliable)
    // ═══════════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty()) return

        // Update foreground tracking
        currentForegroundPackage = packageName

        // Skip our own app and launcher
        if (packageName.startsWith("cz.julek.rails")) {
            hideOverlay()
            return
        }
        if (isLauncher(packageName)) {
            hideOverlay()
            return
        }

        // Check if blocked
        if (!isBlocked(packageName)) return

        // BLOCKED — engage!
        Log.w(TAG, "Event: BLOCKED $packageName detected — engaging!")
        engageAllDefenseLayers(packageName)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Mechanism 2: UsageStatsManager polling (500ms, reliable)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the current foreground app using UsageStatsManager.
     * This is SLOWER than accessibility events but 100% RELIABLE.
     * It works even when the app is resumed from recents (no event fired).
     */
    private fun getForegroundAppViaUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null

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
            Log.e(TAG, "UsageStats query failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Defense Layers — ALL called together, no fallback logic
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Engage ALL defense layers when a blocked app is detected.
     * Called from both accessibility events AND polling monitor.
     */
    private fun engageAllDefenseLayers(packageName: String) {
        val appName = FirebaseManager.getFriendlyAppName(packageName)

        // Layer 1: performGlobalAction — ALWAYS try
        val globalResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "  Layer 1: performGlobalAction(HOME) = $globalResult")

        // Layer 2: startActivity — ALWAYS call (not just as fallback!)
        // On some devices, performGlobalAction returns true but doesn't actually work.
        // Calling both ensures one of them will succeed.
        goToHomeScreen()

        // Layer 3: Overlay
        showOverlay(appName)

        // Layer 4: Notify
        notifyBlocked(appName)

        // Layer 5: Delayed re-checks
        scheduleRecheck(packageName, 500L)
        scheduleRecheck(packageName, 1000L)
        scheduleRecheck(packageName, 2000L)

        // Layer 6: Auto-hide overlay after 3 seconds (if user is on home by then)
        handler.postDelayed({
            if (currentForegroundPackage != packageName || !isBlocked(packageName)) {
                hideOverlay()
            }
        }, 3000L)

        // Ensure monitoring is running
        if (!isMonitoring) startMonitoring()
    }

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

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        instance = null
        stopMonitoring()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        Log.i(TAG, "AppWatcher destroyed")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Kick Mechanisms
    // ═══════════════════════════════════════════════════════════════════

    fun goToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "goToHomeScreen failed: ${e.message}")
        }
    }

    private fun scheduleRecheck(blockedPackage: String, delayMs: Long) {
        handler.postDelayed({
            if (currentForegroundPackage == blockedPackage && isBlocked(blockedPackage)) {
                Log.w(TAG, "Recheck: BLOCKED app still foreground after ${delayMs}ms — kicking again!")
                performGlobalAction(GLOBAL_ACTION_HOME)
                goToHomeScreen()
                showOverlay(FirebaseManager.getFriendlyAppName(blockedPackage))
            } else {
                hideOverlay()
            }
        }, delayMs)
    }

    private fun notifyBlocked(appName: String) {
        val intent = Intent("cz.julek.rails.ACTION_APP_BLOCKED").apply {
            putExtra("app_name", appName)
            setPackage("cz.julek.rails")
        }
        sendBroadcast(intent)
    }

    fun isLauncher(packageName: String): Boolean {
        val launchers = setOf(
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
        )
        return launchers.contains(packageName)
    }
}
