package cz.julek.rails.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import cz.julek.rails.network.FirebaseManager

/**
 * App Watcher Service — AccessibilityService for instant blocked app detection.
 *
 * Uses TYPE_WINDOW_STATE_CHANGED events to detect when the user opens
 * or switches to any app. If the app is in the blocked list, the user
 * is immediately kicked to the home screen.
 *
 * DESIGN PRINCIPLE — No cooldown, no flags, no "lastKickedPackage":
 *   Every single TYPE_WINDOW_STATE_CHANGED event that shows a blocked app
 *   triggers a kick. Period. This is 100% reliable because:
 *
 *   1. When we kick to home, the launcher generates its own WINDOW_STATE event
 *      → isLauncher() returns true → ignored ✓
 *   2. When the blocked app's splash screen transitions to its main activity,
 *      it generates another WINDOW_STATE event with the SAME package name
 *      → we kick again → the user never sees the app content ✓
 *   3. When the user tries to reopen the blocked app from recents,
 *      it generates a WINDOW_STATE event → we kick immediately ✓
 *
 *   The ONLY optimization: we track `currentForegroundPackage` so we don't
 *   fire duplicate kicks for the exact same event (same package, same event sequence).
 *   But if the user leaves and comes back, currentForegroundPackage changes
 *   (to launcher, then back to blocked app) → kick fires again.
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"

        /**
         * Track which package is currently in the foreground.
         * This is NOT a "lastKicked" flag — it's the ACTUAL current foreground app.
         * Updated on every WINDOW_STATE_CHANGED event.
         *
         * Used to avoid kicking twice for the same foreground transition
         * (e.g., Instagram splash → Instagram main both have the same package).
         * But if the user goes to launcher and back, this will have changed
         * to "launcher" first, then back to the blocked package → kick fires.
         */
        private var currentForegroundPackage: String = ""

        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning

        // Handler for delayed re-check (ensures blocked app can't slip through
        // during the transition animation window)
        private val handler = Handler(Looper.getMainLooper())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning = true

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0
        }

        Log.i(TAG, "AppWatcher connected — instant blocked app monitoring active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty()) return

        // Skip our own app
        if (packageName.startsWith("cz.julek.rails")) return

        // Skip launcher / system UI — these are never blocked
        if (isLauncher(packageName)) {
            currentForegroundPackage = packageName
            return
        }

        // Update current foreground tracking
        val wasAlreadyForeground = (packageName == currentForegroundPackage)
        currentForegroundPackage = packageName

        // Check if this app is blocked
        val blocked = FirebaseManager.blockedApps.value
        if (blocked.isEmpty()) return

        val isBlocked = blocked.any { blockedApp ->
            packageName.equals(blockedApp, ignoreCase = true) ||
            FirebaseManager.getFriendlyAppName(packageName).equals(blockedApp, ignoreCase = true) ||
            packageName.contains(blockedApp, ignoreCase = true)
        }

        if (isBlocked) {
            // ALWAYS kick when a blocked app is in the foreground.
            // No cooldown, no "already kicked" flag.
            // If the same package fires multiple WINDOW_STATE events
            // (e.g., splash → main activity), we kick on each one —
            // this ensures the user can NEVER see the app content.
            val appName = FirebaseManager.getFriendlyAppName(packageName)
            Log.w(TAG, "BLOCKED: $appName ($packageName) in foreground — kicking to home (wasAlready=$wasAlreadyForeground)")

            goToHomeScreen()
            notifyBlocked(appName)

            // Schedule a delayed re-check: after kicking to home, the transition
            // animation takes ~300ms. Sometimes the blocked app manages to bring
            // itself back to foreground during this window (e.g., via a pending intent
            // or because the system restores the previous task).
            // We re-check after 300ms and 800ms to catch this edge case.
            scheduleRecheck(packageName, 300L)
            scheduleRecheck(packageName, 800L)
        }
    }

    /**
     * Schedule a delayed re-check: if the blocked app is STILL in the foreground
     * after the kick (system restored it), kick again.
     *
     * This is NOT a polling mechanism — it fires at most 2 times per kick,
     * and only to handle the narrow transition animation window.
     * After that, the AccessibilityService events take over for any
     * subsequent app switches.
     */
    private fun scheduleRecheck(blockedPackage: String, delayMs: Long) {
        handler.postDelayed({
            // If the current foreground is STILL the blocked app, kick again
            if (currentForegroundPackage == blockedPackage) {
                val blocked = FirebaseManager.blockedApps.value
                val isStillBlocked = blocked.any { blockedApp ->
                    blockedPackage.equals(blockedApp, ignoreCase = true) ||
                    FirebaseManager.getFriendlyAppName(blockedPackage).equals(blockedApp, ignoreCase = true) ||
                    blockedPackage.contains(blockedApp, ignoreCase = true)
                }
                if (isStillBlocked) {
                    Log.w(TAG, "BLOCKED app still in foreground after ${delayMs}ms — kicking again!")
                    goToHomeScreen()
                }
            }
        }, delayMs)
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppWatcher interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "AppWatcher destroyed")
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun notifyBlocked(appName: String) {
        val intent = Intent("cz.julek.rails.ACTION_APP_BLOCKED").apply {
            putExtra("app_name", appName)
            setPackage("cz.julek.rails")
        }
        sendBroadcast(intent)
    }

    private fun isLauncher(packageName: String): Boolean {
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
