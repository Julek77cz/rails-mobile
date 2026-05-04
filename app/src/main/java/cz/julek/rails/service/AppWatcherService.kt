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
 * App Watcher Service — Minimal, Storm-Free AccessibilityService
 *
 * PREVIOUS BUG: engageAllDefenseLayers scheduled 5 rechecks (300/700/1500/3000/6000ms).
 * The monitoring loop (every 300ms) also called engage when blocked app was detected.
 * Each recheck that found the app still there called engage AGAIN → exponential
 * callback growth → 50+ pending callbacks after 3 seconds → Android throttles/kills
 * the accessibility service → blocking stops working after 2-3 attempts.
 *
 * FIX: RADICAL SIMPLIFICATION
 *   1. ONE kick method with COOLDOWN (800ms) — prevents callback storms
 *   2. ZERO scheduleRecheck — the monitoring loop IS the recheck
 *   3. The loop runs every 500ms and naturally retries if kick failed
 *   4. Dynamic launcher detection with CACHE — works with any custom launcher
 *   5. Monitoring loop NEVER self-stops — only stopMonitoring() can stop it
 *   6. startMonitoring() ALWAYS called on service connect
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"
        private const val PREFS_NAME = "rails_blocked_apps"
        private const val KEY_BLOCKED_APPS = "blocked_apps_set"
        private const val MONITOR_INTERVAL_MS = 500L
        private const val KICK_COOLDOWN_MS = 800L

        private var currentForegroundPackage: String = ""
        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning

        private var instance: AppWatcherService? = null
        private val handler = Handler(Looper.getMainLooper())

        // ── Cooldown: the single most important fix ──
        private var lastKickTime: Long = 0

        // ── Monitoring ──
        private var monitorRunnable: Runnable? = null
        @Volatile private var isMonitoring: Boolean = false

        // ── Launcher cache ──
        private var cachedLauncherPackages: Set<String>? = null

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
         * Start the monitoring loop. Runs every 500ms.
         * The loop itself is the recheck mechanism — no scheduleRecheck needed.
         * NEVER self-stops — only explicit stopMonitoring() can stop it.
         */
        fun startMonitoring() {
            if (isMonitoring) return
            isMonitoring = true
            Log.i(TAG, "Starting monitoring loop (every ${MONITOR_INTERVAL_MS}ms)")

            monitorRunnable = object : Runnable {
                override fun run() {
                    if (!isMonitoring) return

                    val svc = instance
                    if (svc == null) {
                        // Service not connected yet — retry less frequently
                        handler.postDelayed(this, MONITOR_INTERVAL_MS * 4)
                        return
                    }

                    // Get REAL foreground via UsageStatsManager
                    val fg = svc.getForegroundAppViaUsageStats() ?: currentForegroundPackage
                    if (fg.isNotEmpty()) {
                        val changed = fg != currentForegroundPackage
                        if (changed) {
                            Log.d(TAG, "Monitor: $currentForegroundPackage → $fg")
                            currentForegroundPackage = fg
                        }

                        if (fg.startsWith("cz.julek.rails")) {
                            svc.hideOverlay()
                        } else if (svc.isLauncherCached(fg)) {
                            svc.hideOverlay()
                        } else if (svc.isBlocked(fg)) {
                            // Blocked app detected — kick with cooldown
                            svc.kickWithCooldown(fg)
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
            Log.i(TAG, "Stopped monitoring loop")
        }

        fun forceCheckAndKick() {
            val svc = instance ?: return

            val fg = svc.getForegroundAppViaUsageStats() ?: currentForegroundPackage
            if (fg.isEmpty()) return
            if (fg.startsWith("cz.julek.rails") || svc.isLauncherCached(fg)) return

            currentForegroundPackage = fg

            if (svc.isBlocked(fg)) {
                Log.w(TAG, "forceCheckAndKick: BLOCKED $fg — kicking!")
                svc.kickWithCooldown(fg, force = true)
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

        // Always start monitoring — the loop handles blocked app detection
        startMonitoring()

        // Force-check in case a blocked app is already open
        handler.postDelayed({ forceCheckAndKick() }, 300L)

        Log.i(TAG, "AppWatcher connected — accessibility events + 500ms polling")
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
        if (isLauncherCached(packageName)) {
            hideOverlay()
            return
        }

        if (!isBlocked(packageName)) return

        // Accessibility event detected blocked app — kick with cooldown
        kickWithCooldown(packageName)
    }

    private fun checkAllVisibleWindows() {
        try {
            val wins = windows ?: return
            for (window in wins) {
                val pkg = window.root?.packageName?.toString() ?: continue
                if (pkg.isNotEmpty() && !pkg.startsWith("cz.julek.rails") &&
                    !isLauncherCached(pkg) && isBlocked(pkg)) {
                    kickWithCooldown(pkg)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAllVisibleWindows failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Foreground Detection (UsageStatsManager)
    // ═══════════════════════════════════════════════════════════════════

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
    //  THE KEY METHOD: Kick with Cooldown
    //
    //  This is the ONLY method that performs the kick.
    //  Both accessibility events and the monitoring loop call this.
    //  The cooldown prevents callback storms that killed the service.
    // ═══════════════════════════════════════════════════════════════════

    private fun kickWithCooldown(packageName: String, force: Boolean = false) {
        val now = System.currentTimeMillis()

        // Cooldown check — prevents flooding the handler queue
        if (!force && (now - lastKickTime < KICK_COOLDOWN_MS)) {
            return
        }
        lastKickTime = now

        val appName = FirebaseManager.getFriendlyAppName(packageName)
        Log.w(TAG, "KICKING $appName ($packageName)")

        // Layer 1: System-level home action
        val homeResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "  Layer 1: GLOBAL_ACTION_HOME = $homeResult")

        // Layer 2: Home intent (dual kick — some devices ignore one but not the other)
        goToHomeScreen()

        // Layer 3: Overlay barrier
        showOverlay(appName)

        // Layer 4: Broadcast notification
        notifyBlocked(appName)

        // NO scheduleRecheck! The monitoring loop IS the recheck.
        // It runs every 500ms and will naturally detect if the blocked app
        // is still in foreground and kick again (after cooldown expires).
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Blocked App Detection
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
    //  Launcher Detection — Dynamic with Cache
    //
    //  Uses PackageManager to detect ANY launcher on the device,
    //  including custom launchers (Nova, Niagara, Lawnchair, KISS, etc.)
    //  Results are cached to avoid querying PackageManager every 500ms.
    // ═══════════════════════════════════════════════════════════════════

    private fun isLauncherCached(packageName: String): Boolean {
        // Check cache first
        cachedLauncherPackages?.let { cached ->
            return cached.contains(packageName)
        }

        // Build cache
        val launchers = detectLaunchers()
        cachedLauncherPackages = launchers
        Log.i(TAG, "Launcher cache built: $launchers")
        return launchers.contains(packageName)
    }

    private fun detectLaunchers(): Set<String> {
        val launchers = mutableSetOf<String>()

        // 1. Hardcoded known launchers (instant, no query needed)
        launchers.addAll(setOf(
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
            "com.teslacoilsw.launcher",           // Nova Launcher
            "com.actionlauncher.playstore",        // Action Launcher
            "net.oneplus.launcher",
            "com.google.android.launcher",
            "com.microsoft.launcher",              // Microsoft Launcher
            "ch.deletescape.lawnchair.ci",         // Lawnchair
            "ch.deletescape.lawnchair",            // Lawnchair
            "fr.neamar.kiss",                      // KISS Launcher
            "com.nielsen.launcher",                // Niagara Launcher
            "bitpit.launcher",                     // Niagara Launcher
            "com.slim.launcher",                   // Slim Launcher
            "com.bbh.scenes",                      // ASAP Launcher
            "com.catchingnow.iceboxapp",           // Ice Box
            "me.craftsapp.nano",                   // Nano Launcher
            "org.indywidualni.fblite",             // herolife
        ))

        // 2. Dynamic detection via PackageManager — catches ANY launcher
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, 0)
            for (info in resolveInfos) {
                launchers.add(info.activityInfo.packageName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PackageManager launcher query failed: ${e.message}")
        }

        return launchers
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
            Log.e(TAG, "goToHomeScreen failed: ${e.message}")
        }
    }

    private fun notifyBlocked(appName: String) {
        val intent = Intent("cz.julek.rails.ACTION_APP_BLOCKED").apply {
            putExtra("app_name", appName)
            setPackage("cz.julek.rails")
        }
        sendBroadcast(intent)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        instance = null
        stopMonitoring()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        cachedLauncherPackages = null
        Log.i(TAG, "AppWatcher destroyed")
    }
}
