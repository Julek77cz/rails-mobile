package cz.julek.flowpilot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver for screen on/off events.
 *
 * When the screen turns on or off, this receiver forwards the event
 * to the SensorService which then sends it to the Orchestrator.
 *
 * Will be wired into SensorService in Phase 2.
 */
class ScreenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_ON -> {
                // TODO: Forward to SensorService → WebSocket → Orchestrator
            }
            Intent.ACTION_SCREEN_OFF -> {
                // TODO: Forward to SensorService → WebSocket → Orchestrator
            }
        }
    }
}
