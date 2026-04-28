package cz.julek.rails

import android.app.Application
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.FirebaseApp

/**
 * Application class for Rails Mobile.
 * Initializes Firebase Realtime Database on app startup.
 */
class RailsApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase (auto-configured from google-services.json)
        try {
            FirebaseApp.initializeApp(this)
            // Enable offline persistence — Firebase caches data locally
            // so the app works even when temporarily offline
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            // setPersistenceEnabled may throw if called multiple times
            // (e.g., on process restart) — safe to ignore
        }
    }
}
