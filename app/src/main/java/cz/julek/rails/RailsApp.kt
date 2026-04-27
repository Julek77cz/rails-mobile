package cz.julek.rails

import android.app.Application

/**
 * Application class for Rails Mobile.
 * Will be used for DI, global state, and service lifecycle management.
 */
class RailsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: initialize Koin/Dagger, WebSocket manager, etc.
    }
}
