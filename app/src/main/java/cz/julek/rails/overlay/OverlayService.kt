package cz.julek.rails.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.View

/**
 * Rails Overlay Service — App Blocking + Lock Screen (Phase 2)
 *
 * When the Orchestrator sends an INTERVENE/BLOCK_APPS/LOCK_SCREEN command,
 * this service displays a full-screen overlay that blocks interaction with
 * the underlying app.
 *
 * The overlay is dismissed only when:
 *   - A CLEAR command arrives
 *   - An UNBLOCK_APPS command arrives
 *   - The user switches to a non-blocked app (handled by SensorService)
 *
 * Overlay types:
 *   - BLOCK: Red warning overlay ("Tato aplikace je zablokována")
 *   - LOCK: Dark overlay with lock icon ("Zamkni telefon a vrať se k práci")
 *   - INTERVENE: Orange warning overlay ("Prokrastinuješ!")
 */
class OverlayService : Service() {

    companion object {
        const val TAG = "Rails/Overlay"
        const val ACTION_INTERVENE = "cz.julek.rails.action.INTERVENE"
        const val ACTION_CLEAR = "cz.julek.rails.action.CLEAR"
        const val ACTION_LOCK_SCREEN = "cz.julek.rails.action.LOCK_SCREEN"
        const val EXTRA_MESSAGE = "message"
    }

    private var overlayView: View? = null
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INTERVENE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Zpatky do prace!"
                Log.w(TAG, "Showing BLOCK overlay: $message")
                showOverlay(OverlayType.BLOCK, message)
            }
            ACTION_LOCK_SCREEN -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Zamkni telefon!"
                Log.w(TAG, "Showing LOCK overlay: $message")
                showOverlay(OverlayType.LOCK, message)
            }
            ACTION_CLEAR -> {
                Log.i(TAG, "Clearing overlay")
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

    // ═══════════════════════════════════════════════════════════════════
    //  Overlay Display
    // ═══════════════════════════════════════════════════════════════════

    private enum class OverlayType {
        BLOCK,      // Red — app is blocked
        LOCK,       // Dark — lock screen request
        INTERVENE   // Orange — warning
    }

    private fun showOverlay(type: OverlayType, message: String) {
        // Remove existing overlay first
        removeOverlay()

        val (backgroundColor, titleText, iconEmoji) = when (type) {
            OverlayType.BLOCK -> Triple(Color.parseColor("#CC0000"), "BLOKOVANO", "🚫")
            OverlayType.LOCK -> Triple(Color.parseColor("#1a1a2e"), "ZAMKNI TELEFON", "🔒")
            OverlayType.INTERVENE -> Triple(Color.parseColor("#CC6600"), "VAROVANI", "⚠️")
        }

        // Create overlay layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 96)

            // Icon
            addView(TextView(this@OverlayService).apply {
                text = iconEmoji
                textSize = 64f
                gravity = Gravity.CENTER
            })

            // Spacer
            addView(View(this@OverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    32
                )
            })

            // Title
            addView(TextView(this@OverlayService).apply {
                text = titleText
                textSize = 28f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })

            // Spacer
            addView(View(this@OverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    24
                )
            })

            // Message
            addView(TextView(this@OverlayService).apply {
                text = message
                textSize = 18f
                setTextColor(Color.parseColor("#E0E0E0"))
                gravity = Gravity.CENTER
                setLineSpacing(8f, 1f)
            })

            // Spacer
            addView(View(this@OverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    48
                )
            })

            // Subtitle
            addView(TextView(this@OverlayService).apply {
                text = "Zpatky k praci!"
                textSize = 14f
                setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
            })
        }

        // Set up window parameters
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Show overlay
        try {
            windowManager.addView(layout, params)
            overlayView = layout
            Log.w(TAG, "Overlay successfully displayed: type=$type")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}. " +
                    "Check SYSTEM_ALERT_WINDOW permission in Settings!")
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View might already be removed
            }
            overlayView = null
        }
    }
}
