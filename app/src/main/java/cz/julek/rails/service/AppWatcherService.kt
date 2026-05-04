package cz.julek.rails.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
 * Architecture (inspired by AppBlock, Forest, Freedom):
 *   Layer 1: AccessibilityService detects app switch INSTANTLY
 *   Layer 2: performGlobalAction(GLOBAL_ACTION_HOME) kicks user out (system-level)
 *   Layer 3: startActivity(homeIntent) as fallback if global action fails
 *   Layer 4: TYPE_ACCESSIBILITY_OVERLAY covers the screen (no SYSTEM_ALERT_WINDOW needed!)
 *   Layer 5: Delayed re-checks (300ms, 800ms) catch edge cases during transition
 *   Layer 6: Local persistence — blocked apps stored in SharedPreferences, not just Firebase
 *   Layer 7: forceCheckAndKick() — called when BLOCK_APPS command arrives while
 *            app is ALREADY open (no accessibility event fires for already-foreground app)
 *
 * KEY DESIGN DECISIONS:
 *   - NO cooldown, NO "lastKickedPackage" flag, NO "isShowingBlockOverlay" flag
 *   - EVERY event for a blocked app → kick immediately
 *   - Overlay is pre-created at service start, toggled to opaque instantly
 *   - Blocked apps cached locally in SharedPreferences (survives Firebase disconnect)
 *   - Both performGlobalAction AND startActivity as dual kick mechanism
 *   - Static instance reference allows forceCheckAndKick() from anywhere
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"
        private const val PREFS_NAME = "rails_blocked_apps"
        private const val KEY_BLOCKED_APPS = "blocked_apps_set"

        /**
         * Current foreground package — tracks what's actually on screen.
         * Updated on every WINDOW_STATE_CHANGED event.
         * NOT a "lastKicked" flag — this is the ACTUAL current state.
         */
        private var currentForegroundPackage: String = ""

        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning

        /**
         * Static reference to the running service instance.
         * Needed so we can call forceCheckAndKick() from FirebaseManager
         * when a BLOCK_APPS command arrives while the app is already open.
         */
        private var instance: AppWatcherService? = null

        // Handler for delayed re-checks
        private val handler = Handler(Looper.getMainLooper())

        /**
         * Save blocked apps to SharedPreferences.
         * Called by FirebaseManager when BLOCK_APPS command is received.
         * This ensures the list survives Firebase disconnects, app restarts,
         * and is available immediately when the service starts.
         */
        fun saveBlockedApps(context: Context, apps: List<String>) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_BLOCKED_APPS, apps.toSet()).apply()
            Log.i(TAG, "Saved ${apps.size} blocked apps to local storage: $apps")
        }

        /**
         * Load blocked apps from SharedPreferences.
         * Called in onServiceConnected() so blocking works immediately,
         * even before Firebase connects.
         */
        fun loadBlockedApps(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val apps = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
            Log.i(TAG, "Loaded ${apps.size} blocked apps from local storage: $apps")
            return apps
        }

        /**
         * Clear blocked apps from SharedPreferences.
         */
        fun clearBlockedApps(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_BLOCKED_APPS).apply()
            Log.i(TAG, "Cleared blocked apps from local storage")
        }

        /**
         * FORCE check the current foreground app and kick if blocked.
         *
         * This is the CRITICAL method that fixes the bug where blocking
         * doesn't work when the BLOCK_APPS command arrives while the
         * blocked app is ALREADY open.
         *
         * When the user is in Instagram and the BLOCK_APPS command arrives,
         * no new TYPE_WINDOW_STATE_CHANGED event fires (the app is already
         * in the foreground). So the AccessibilityService's onAccessibilityEvent
         * never gets called. This method bridges that gap.
         *
         * Called by:
         *   - FirebaseManager when BLOCK_APPS command arrives
         *   - SensorService as fallback (if AppWatcherService isn't running)
         *   - Delayed re-checks
         */
        fun forceCheckAndKick() {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "forceCheckAndKick: service instance is NULL — AppWatcherService not running!")
                Log.w(TAG, "forceCheckAndKick: user needs to enable Accessibility Service in Settings")
                return
            }

            val pkg = currentForegroundPackage
            if (pkg.isEmpty()) {
                Log.d(TAG, "forceCheckAndKick: no foreground package tracked yet")
                return
            }

            // Skip launcher/our own app
            if (pkg.startsWith("cz.julek.rails") || svc.isLauncher(pkg)) {
                Log.d(TAG, "forceCheckAndKick: current app ($pkg) is launcher or our app — skip")
                return
            }

            // Check if blocked
            if (svc.isBlocked(pkg)) {
                val appName = FirebaseManager.getFriendlyAppName(pkg)
                Log.w(TAG, "forceCheckAndKick: BLOCKED app $appName ($pkg) is in foreground — kicking NOW!")

                // Layer 1: performGlobalAction
                val globalResult = svc.performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d(TAG, "  forceCheck: performGlobalAction(HOME) = $globalResult")

                // Layer 2: startActivity fallback
                if (!globalResult) {
                    svc.goToHomeScreen()
                }

                // Layer 3: Show overlay
                svc.showOverlay(appName)

                // Layer 4: Notify
                svc.notifyBlocked(appName)

                // Layer 5: Delayed re-checks
                svc.scheduleRecheck(pkg, 300L)
                svc.scheduleRecheck(pkg, 800L)
                svc.scheduleRecheck(pkg, 1500L)

                // Layer 6: Auto-hide overlay
                handler.postDelayed({
                    if (currentForegroundPackage != pkg) {
                        svc.hideOverlay()
                    }
                }, 2000L)
            } else {
                Log.d(TAG, "forceCheckAndKick: current app ($pkg) is NOT blocked")
            }
        }

        /**
         * Get the current foreground package name (for SensorService fallback).
         */
        fun getCurrentForegroundPackage(): String = currentForegroundPackage
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Overlay — Pre-created for instant display
    // ═══════════════════════════════════════════════════════════════════

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayShowing = false

    /**
     * Pre-create the overlay view at service start.
     * Uses TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW permission needed!
     * Higher Z-order than TYPE_APPLICATION_OVERLAY, no "untrusted touches" blocking.
     *
     * The view starts with transparent background. When a blocked app is detected,
     * we toggle it to opaque INSTANTLY — no addView() latency.
     */
    private fun createOverlay() {
        if (overlayView != null) return  // Already created

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = FrameLayout(this).apply {
            // Start invisible — will be toggled to opaque when needed
            alpha = 0f
            setBackgroundColor(0xFF1A1A2E.toInt())  // Dark background

            // "Blocked" message
            val textView = TextView(this@AppWatcherService).apply {
                text = "Tato aplikace je zablokovana\nVrat se k praci!"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            }
            addView(textView)

            // Click handler — go home when tapped
            setOnClickListener {
                goToHomeScreen()
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

        // Add the view IMMEDIATELY (but invisible) so we can toggle it instantly later
        try {
            windowManager?.addView(overlayView, overlayLayoutParams)
            Log.i(TAG, "Overlay pre-created and added (invisible) — ready for instant display")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: ${e.message}")
            overlayView = null
        }
    }

    /**
     * Show the overlay — toggles from transparent to opaque.
     * Because the view is already added to the window manager,
     * this is essentially instant (no addView latency).
     */
    fun showOverlay(appName: String) {
        if (overlayView == null) {
            Log.w(TAG, "Overlay not available — creating now (fallback)")
            createOverlay()
        }

        try {
            // Update the text to show which app is blocked
            (overlayView as? FrameLayout)?.let { frame ->
                (frame.getChildAt(0) as? TextView)?.let { tv ->
                    tv.text = "$appName je zablokovana\nVrat se k praci!"
                }
            }

            // Toggle to opaque
            overlayView?.alpha = 1f

            // Bring to front
            overlayLayoutParams?.let { params ->
                windowManager?.updateViewLayout(overlayView, params)
            }

            isOverlayShowing = true
            Log.d(TAG, "Overlay shown for: $appName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    /**
     * Hide the overlay — toggles back to transparent.
     */
    fun hideOverlay() {
        if (!isOverlayShowing) return

        try {
            overlayView?.alpha = 0f
            isOverlayShowing = false
            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay: ${e.message}")
        }
    }

    /**
     * Remove the overlay entirely (service shutdown).
     */
    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            // View might already be removed
        }
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

        // Pre-create the overlay for instant display
        createOverlay()

        // Load blocked apps from local storage (works even without Firebase)
        val localBlocked = loadBlockedApps(this)
        if (localBlocked.isNotEmpty() && FirebaseManager.blockedApps.value.isEmpty()) {
            Log.i(TAG, "Restoring ${localBlocked.size} blocked apps from local storage")
        }

        // If there are already blocked apps and a foreground app, check immediately
        if (localBlocked.isNotEmpty() || FirebaseManager.blockedApps.value.isNotEmpty()) {
            handler.postDelayed({ forceCheckAndKick() }, 500L)
        }

        Log.i(TAG, "AppWatcher connected — bulletproof blocking active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty()) return

        // Skip our own app
        if (packageName.startsWith("cz.julek.rails")) {
            currentForegroundPackage = packageName
            hideOverlay()
            return
        }

        // Skip launcher / system UI — never blocked
        if (isLauncher(packageName)) {
            currentForegroundPackage = packageName
            hideOverlay()
            return
        }

        // Update current foreground tracking
        currentForegroundPackage = packageName

        // Check if this app is blocked — check BOTH Firebase AND local cache
        if (!isBlocked(packageName)) return

        // ── BLOCKED APP DETECTED — Engage all defense layers ──

        val appName = FirebaseManager.getFriendlyAppName(packageName)
        Log.w(TAG, "BLOCKED: $appName ($packageName) — engaging all defense layers")

        // Layer 1: performGlobalAction — system-level home press (most reliable)
        val globalActionResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "  Layer 1: performGlobalAction(HOME) = $globalActionResult")

        // Layer 2: startActivity fallback (if global action failed)
        if (!globalActionResult) {
            Log.w(TAG, "  Layer 1 failed — falling back to startActivity(homeIntent)")
            goToHomeScreen()
        }

        // Layer 3: Show accessibility overlay (covers any remaining content)
        showOverlay(appName)

        // Layer 4: Notify other components
        notifyBlocked(appName)

        // Layer 5: Delayed re-checks (catch transition animation edge cases)
        scheduleRecheck(packageName, 300L)
        scheduleRecheck(packageName, 800L)
        scheduleRecheck(packageName, 1500L)

        // Layer 6: Auto-hide overlay after 2 seconds (user should be on home by then)
        handler.postDelayed({
            if (currentForegroundPackage != packageName) {
                hideOverlay()
            }
        }, 2000L)
    }

    /**
     * Check if a package is blocked — uses BOTH Firebase StateFlow AND local cache.
     * This ensures blocking works even when Firebase hasn't connected yet,
     * or when the StateFlow hasn't been updated.
     */
    fun isBlocked(packageName: String): Boolean {
        // Check Firebase StateFlow first (real-time)
        val firebaseBlocked = FirebaseManager.blockedApps.value
        if (firebaseBlocked.isNotEmpty()) {
            val match = firebaseBlocked.any { blockedApp ->
                packageName.equals(blockedApp, ignoreCase = true) ||
                FirebaseManager.getFriendlyAppName(packageName).equals(blockedApp, ignoreCase = true) ||
                packageName.contains(blockedApp, ignoreCase = true)
            }
            if (match) return true
        }

        // Fallback: check local SharedPreferences cache
        val localBlocked = loadBlockedApps(this)
        if (localBlocked.isNotEmpty()) {
            val match = localBlocked.any { blockedApp ->
                packageName.equals(blockedApp, ignoreCase = true) ||
                FirebaseManager.getFriendlyAppName(packageName).equals(blockedApp, ignoreCase = true) ||
                packageName.contains(blockedApp, ignoreCase = true)
            }
            if (match) return true
        }

        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppWatcher interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        Log.i(TAG, "AppWatcher destroyed")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Kick Mechanisms
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Navigate to home screen using startActivity.
     * Used as FALLBACK when performGlobalAction fails.
     */
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
     * Schedule a delayed re-check: if the blocked app is STILL in the foreground
     * after the kick (system restored it), kick again + show overlay again.
     */
    fun scheduleRecheck(blockedPackage: String, delayMs: Long) {
        handler.postDelayed({
            if (currentForegroundPackage == blockedPackage && isBlocked(blockedPackage)) {
                Log.w(TAG, "BLOCKED app STILL in foreground after ${delayMs}ms — kicking again!")

                val result = performGlobalAction(GLOBAL_ACTION_HOME)
                if (!result) {
                    goToHomeScreen()
                }

                val appName = FirebaseManager.getFriendlyAppName(blockedPackage)
                showOverlay(appName)

                handler.postDelayed({
                    if (currentForegroundPackage == blockedPackage) {
                        Log.w(TAG, "BLOCKED app STILL THERE after recheck — keeping overlay visible")
                    } else {
                        hideOverlay()
                    }
                }, 1000L)
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
