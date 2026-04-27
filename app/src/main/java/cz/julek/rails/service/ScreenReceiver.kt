package cz.julek.rails.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for screen on/off events and device lock/unlock.
 *
 * Monitors three system broadcasts:
 *   - ACTION_SCREEN_ON  → screen turned on (may still be locked)
 *   - ACTION_SCREEN_OFF → screen turned off
 *   - ACTION_USER_PRESENT → device unlocked by user (keyguard dismissed)
 *
 * The SensorService uses these to determine:
 *   - screen_on: is the display active?
 *   - device_locked: is the keyguard showing (user hasn't unlocked)?
 *
 * Note: After SCREEN_ON, the device may still be locked.
 *       USER_PRESENT is the definitive signal that the user has unlocked.
 *       If screen is ON but USER_PRESENT hasn't fired → device is locked.
 */
class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Rails/ScreenReceiver"

        /**
         * Callback set by SensorService to receive screen state changes.
         * Parameters: (screenOn: Boolean, deviceLocked: Boolean)
         */
        var onScreenEvent: ((screenOn: Boolean, deviceLocked: Boolean) -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "Screen ON detected — device likely locked until USER_PRESENT")
                // Screen is on, but device is locked until USER_PRESENT fires
                onScreenEvent?.invoke(true, true)
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Screen OFF detected — device locked")
                // Screen off implies locked
                onScreenEvent?.invoke(false, true)
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "USER_PRESENT — device unlocked by user")
                // User just unlocked the device — screen is on, device is NOT locked
                onScreenEvent?.invoke(true, false)
            }
        }
    }
}
