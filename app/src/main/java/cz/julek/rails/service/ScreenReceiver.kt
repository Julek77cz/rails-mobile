package cz.julek.rails.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for screen on/off events.
 *
 * When the screen turns on or off, this receiver forwards the event
 * to the SensorService via a callback, which then sends the updated
 * phone_state payload to the Orchestrator via WebSocket.
 */
class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Rails/ScreenReceiver"

        /**
         * Callback set by SensorService to receive screen state changes.
         * This avoids the need for IPC — the service sets this when it
         * registers the receiver.
         */
        var onScreenEvent: ((screenOn: Boolean) -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "Screen ON detected")
                onScreenEvent?.invoke(true)
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Screen OFF detected")
                onScreenEvent?.invoke(false)
            }
        }
    }
}
