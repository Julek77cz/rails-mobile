package cz.julek.rails.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Rails Overlay Service — Intervention Display
 *
 * When the Orchestrator sends an INTERVENE command, this service
 * displays a full-screen, non-dismissible red overlay with a message.
 *
 * The overlay is dismissed only when a CLEAR command arrives.
 *
 * Will be implemented in Phase 2.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_INTERVENE = "cz.julek.rails.action.INTERVENE"
        const val ACTION_CLEAR = "cz.julek.rails.action.CLEAR"
        const val EXTRA_MESSAGE = "message"
    }

    private var overlayView: android.view.View? = null
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INTERVENE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Zpátky do práce!"
                showOverlay(message)
            }
            ACTION_CLEAR -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    private fun showOverlay(message: String) {
        if (overlayView != null) return // Already showing

        // TODO: Implement full-screen red overlay with WindowManager
        // This will use TYPE_APPLICATION_OVERLAY with FLAG_NOT_TOUCHABLE
        // or FLAG_NOT_FOCUSABLE depending on desired behavior
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
}
