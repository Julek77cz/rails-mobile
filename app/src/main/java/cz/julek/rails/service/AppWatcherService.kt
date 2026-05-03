package cz.julek.rails.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
 * This is how AppBlock, Forest, and similar apps work — event-driven,
 * not polling-based. Zero delay between app open and block action.
 *
 * Must be enabled manually: Settings > Accessibility > Rails Blokování
 * The onboarding flow guides the user through this.
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "Rails/AppWatcher"

        // Track the last package we kicked to home, to avoid re-kicking
        // (the HOME intent itself causes a WINDOW_STATE_CHANGED for the launcher)
        private var lastKickedPackage: String = ""

        private var _isRunning: Boolean = false
        val isRunning: Boolean get() = _isRunning
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

        // Skip launcher / system UI (result of our own HOME intent)
        if (isLauncher(packageName)) {
            lastKickedPackage = ""
            return
        }

        // Skip if this is the same package we just kicked
        if (packageName == lastKickedPackage) return

        // Check if this app is blocked
        val blocked = FirebaseManager.blockedApps.value
        if (blocked.isEmpty()) return

        val isBlocked = blocked.any { blockedApp ->
            packageName.equals(blockedApp, ignoreCase = true) ||
            FirebaseManager.getFriendlyAppName(packageName).equals(blockedApp, ignoreCase = true) ||
            packageName.contains(blockedApp, ignoreCase = true)
        }

        if (isBlocked) {
            val appName = FirebaseManager.getFriendlyAppName(packageName)
            Log.w(TAG, "BLOCKED: $appName opened — kicking to home IMMEDIATELY")

            lastKickedPackage = packageName
            goToHomeScreen()
            notifyBlocked(appName)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppWatcher interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
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
