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
 * KEY FIXES vs previous version:
 *   FIX 1: scheduleRecheck now uses a snapshot of blockedPackage, NOT
 *           currentForegroundPackage — so re-kicks fire even after launcher shows.
 *   FIX 2: monitorRunnable NEVER stops itself — only explicit stopMonitoring() can.
 *   FIX 3: Polling interval reduced to 300ms.
 *   FIX 4: Split-screen: TYPE_WINDOWS_CHANGED + FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.
 *   FIX 5: isLauncher() is dynamic (PackageManager) — works with any launcher.
 *   FIX 6: startMonitoring() is always called on service connect, not conditionally.
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"
        private const val PREFS_NAME = "rails_blocked_apps"
        private const val KEY_BLOCKED_APPS = "blocked_apps_set"
        private const val MONITOR_INTERVAL_MS = 300L

        private var currentForegroundPackage: String = ""

        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning

        private var instance: AppWatcherService? = null
        private val handler = Handler(Looper.getMainLooper())

        private var monitorRunnable: Runnable? = null

        @Volatile private var isMonitoring: Boolean = false

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

        fun startMonitoring() {
            if (isMonitoring) return
            isMonitoring = true
            Log.i(TAG, "Starting continuous monitoring (every ${MONITOR_INTERVAL_MS}ms)")

            monitorRunnable = object : Runnable {
                override fun run() {
                    if (!isMonitoring) return

                    val svc = instance
                    if (svc == null) {
                        handler.postDelayed(this, MONITOR_INTERVAL_MS * 3)
                        return
                    }

                    val fg = svc.getForegroundAppViaUsageStats() ?: currentForegroundPackage
                    if (fg.isNotEmpty()) {
                        val wasChanged = fg != currentForegroundPackage
                        if (wasChanged) {
                            Log.d(TAG, "Monitor: foreground $currentForegroundPackage → $fg")
                            currentForegroundPackage = fg
                        }

                        if (!svc.isLauncher(fg) && !fg.startsWith("cz.julek.rails") && svc.isBlocked(fg)) {
                            val appName = FirebaseManager.getFriendlyAppName(fg)
                            Log.w(TAG, "Monitor: BLOCKED $appName ($fg) — kicking!")
                            svc.engageAllDefenseLayers(fg)
                        }
                    }

                    handler.postDelayed(this, MONITOR_INTERVAL_MS)
                }
            }

            handler.post(monitorRunnable!!)
        }

        fun stopMonitoring() {
            isMonitoring = false
            monitorRunnable?.let { handler.removeCallbacks(it) }
            monitorRunnable = null
            Log.i(TAG, "Stopped continuous monitoring")
        }

        fun forceCheckAndKick() {
            val svc = instance ?: return

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning = true
        instance = this

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

        // Always start monitoring — the loop checks for blocked apps every 300ms
        startMonitoring()
        handler.postDelayed({ forceCheckAndKick() }, 300L)

        Log.i(TAG, "AppWatcher connected — dual mechanism (events + 300ms polling)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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

        if (!isBlocked(packageName)) return

        Log.w(TAG, "Event: BLOCKED $packageName detected — engaging!")
        engageAllDefenseLayers(packageName)
    }

    private fun checkAllVisibleWindows() {
        try {
            val wins = windows ?: return
            for (window in wins) {
                val pkg = window.root?.packageName?.toString() ?: continue
                if (pkg.isNotEmpty() && !pkg.startsWith("cz.julek.rails") && !isLauncher(pkg) && isBlocked(pkg)) {
                    Log.w(TAG, "WindowsChanged: BLOCKED $pkg visible (split-screen?) — engaging!")
                    engageAllDefenseLayers(pkg)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAllVisibleWindows failed: ${e.message}")
        }
    }

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

    private fun engageAllDefenseLayers(packageName: String) {
        val appName = FirebaseManager.getFriendlyAppName(packageName)

        val globalResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "  Layer 1: performGlobalAction(HOME) = $globalResult")

        goToHomeScreen()
        showOverlay(appName)
        notifyBlocked(appName)

        // Exponential re-checks — pass packageName as snapshot, NOT currentForegroundPackage
        scheduleRecheck(packageName, 300L)
        scheduleRecheck(packageName, 700L)
        scheduleRecheck(packageName, 1500L)
        scheduleRecheck(packageName, 3000L)
        scheduleRecheck(packageName, 6000L)

        // Auto-hide overlay only when blocked app is actually gone
        handler.postDelayed({
            val realFg = getForegroundAppViaUsageStats() ?: currentForegroundPackage
            if (!isBlocked(realFg) || isLauncher(realFg)) {
                hideOverlay()
            }
        }, 3500L)

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

    /**
     * FIX: Uses getForegroundAppViaUsageStats() to check the REAL current foreground,
     * NOT currentForegroundPackage (which was already the launcher by the time this runs).
     */
    private fun scheduleRecheck(blockedPackage: String, delayMs: Long) {
        handler.postDelayed({
            if (!isBlocked(blockedPackage)) return@postDelayed

            val realFg = getForegroundAppViaUsageStats() ?: currentForegroundPackage

            if (realFg == blockedPackage || matchesBlocked(realFg, blockedPackage)) {
                Log.w(TAG, "Recheck @${delayMs}ms: $blockedPackage STILL foreground — kicking again!")
                performGlobalAction(GLOBAL_ACTION_HOME)
                goToHomeScreen()
                showOverlay(FirebaseManager.getFriendlyAppName(blockedPackage))
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

    /**
     * FIX: Dynamic launcher detection via PackageManager.
     * Works with Nova Launcher, Action Launcher, any 3rd-party launcher.
     */
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
            false
        }
    }
}
