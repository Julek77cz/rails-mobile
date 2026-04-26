package cz.julek.flowpilot

import android.app.Application

/**
 * Application class for FlowPilot Mobile.
 * Will be used for DI, global state, and service lifecycle management.
 */
class FlowPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: initialize Koin/Dagger, WebSocket manager, etc.
    }
}
