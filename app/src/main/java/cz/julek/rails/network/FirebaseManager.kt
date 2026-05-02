package cz.julek.rails.network

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Firebase Manager — cloud relay for communication with Rails Orchestrator.
 *
 * Replaces the previous WebSocket + HTTP POST architecture.
 * Firebase Realtime Database serves as a central relay between
 * the Android app and the Node.js orchestrator, eliminating
 * firewall/NAT issues and enabling mobile data connectivity.
 *
 * Data paths in Firebase RTDB:
 *   /rails/devices/my_phone/phone_state   <- Written by Android (sensor data)
 *   /rails/devices/my_phone/command       <- Written by Node.js (INTERVENE/BLOCK_APPS/UNBLOCK_APPS/LOCK_SCREEN/CLEAR)
 *   /rails/devices/my_phone/chat_inbox    <- Written by Android (user messages)
 *   /rails/devices/my_phone/chat_outbox   <- Written by Node.js (AI responses)
 *   /rails/devices/my_phone/focus_state   <- Written by Node.js (focus score)
 *
 * Communication channels:
 *   1. SENSOR  — phone_state telemetry (screen_on, fg app, device_locked, battery, notifications)
 *   2. CHAT    — user <-> AI text messages (inbox/outbox pattern)
 *   3. COMMAND — server-initiated interventions (INTERVENE/BLOCK_APPS/UNBLOCK_APPS/LOCK_SCREEN/CLEAR)
 */
object FirebaseManager {

    private const val TAG = "Rails/Firebase"
    private const val DEVICE_ID = "my_phone"
    private const val BASE_PATH = "rails/devices/$DEVICE_ID"

    // ═══════════════════════════════════════════════════════════════════
    //  Connection State
    // ═══════════════════════════════════════════════════════════════════

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    //  Chat Messages
    // ═══════════════════════════════════════════════════════════════════

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val messageIdCounter = AtomicLong(0)

    // ═══════════════════════════════════════════════════════════════════
    //  Blocked Apps State (Phase 2)
    // ═══════════════════════════════════════════════════════════════════

    private val _blockedApps = MutableStateFlow<List<String>>(emptyList())
    val blockedApps: StateFlow<List<String>> = _blockedApps.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    //  Firebase References
    // ═══════════════════════════════════════════════════════════════════

    private var database: FirebaseDatabase? = null
    private var deviceRef: DatabaseReference? = null

    // Listeners (stored for cleanup on disconnect)
    private var connectionListener: ValueEventListener? = null
    private var commandListener: ValueEventListener? = null
    private var chatOutboxListener: ValueEventListener? = null

    // Track last processed timestamps to avoid re-processing on reconnect
    private var lastChatOutboxTimestamp: Long = 0
    private var lastCommandTimestamp: Long = 0

    // ═══════════════════════════════════════════════════════════════════
    //  Intervention Listeners (Phase 2 — expanded)
    // ═══════════════════════════════════════════════════════════════════

    var onIntervene: ((message: String) -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onBlockApps: ((apps: List<String>, message: String) -> Unit)? = null
    var onUnblockApps: ((message: String) -> Unit)? = null
    var onLockScreen: ((message: String) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════════
    //  App Name Mapping
    // ═══════════════════════════════════════════════════════════════════

    private val APP_FRIENDLY_NAMES = mapOf(
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook Lite",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.twitter.android" to "X (Twitter)",
        "com.whatsapp" to "WhatsApp",
        "com.telegram.messenger" to "Telegram",
        "com.snapchat.android" to "Snapchat",
        "com.reddit.frontpage" to "Reddit",
        "com.google.android.youtube" to "YouTube",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify",
        "com.discord" to "Discord",
        "com.tumblr" to "Tumblr",
        "com.pinterest" to "Pinterest",
        "com.twitch.android.app" to "Twitch",
        "tv.twitch.android.app" to "Twitch",
        "com.valvesoftware.steamlink" to "Steam Link",
        "com.android.chrome" to "Chrome",
        "org.mozilla.firefox" to "Firefox",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.apps.docs" to "Google Docs",
        "com.microsoft.office.outlook" to "Outlook",
        "com.microsoft.teams" to "Teams",
        "com.slack" to "Slack",
        "com.notion.android" to "Notion",
        "md.obsidian" to "Obsidian",
        "com.ichi2.anki" to "Anki",
    )

    fun getFriendlyAppName(packageName: String): String {
        return APP_FRIENDLY_NAMES[packageName] ?: packageName
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Connection Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connect to Firebase Realtime Database.
     * No IP address needed — Firebase handles routing automatically.
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connecting/connected — ignoring duplicate connect()")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to Firebase Realtime Database...")

        try {
            database = FirebaseDatabase.getInstance()
            deviceRef = database?.getReference(BASE_PATH)

            // ── Listen to Firebase connection state (.info/connected) ──
            val connectedRef = database?.getReference(".info/connected")
            connectionListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (connected) {
                        _connectionState.value = ConnectionState.CONNECTED
                        addSystemMessage("Připojeno k Firebase cloudu")
                        Log.i(TAG, "Firebase connected!")
                    } else {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            Log.w(TAG, "Firebase disconnected — will auto-reconnect")
                        }
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase connection listener cancelled: ${error.message}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    addSystemMessage("Chyba Firebase: ${error.message}")
                }
            }
            connectedRef?.addValueEventListener(connectionListener!!)

            // ── Start data listeners ──
            startCommandListener()
            startChatOutboxListener()

        } catch (e: Exception) {
            Log.e(TAG, "Firebase connection failed: ${e.message}")
            Log.e(TAG, "Is google-services.json present in app/ folder?")
            _connectionState.value = ConnectionState.DISCONNECTED
            addSystemMessage("Chyba připojení: ${e.message}")
        }
    }

    /**
     * Disconnect from Firebase and remove all listeners.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from Firebase...")

        // Remove all listeners
        connectionListener?.let {
            database?.getReference(".info/connected")?.removeEventListener(it)
        }
        commandListener?.let {
            deviceRef?.child("command")?.removeEventListener(it)
        }
        chatOutboxListener?.let {
            deviceRef?.child("chat_outbox")?.removeEventListener(it)
        }

        connectionListener = null
        commandListener = null
        chatOutboxListener = null

        // Clear device state in Firebase (signal that device is offline)
        deviceRef?.child("phone_state")?.removeValue()

        deviceRef = null
        database = null

        _connectionState.value = ConnectionState.DISCONNECTED
        addSystemMessage("Odpojeno od Firebase")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command Listener (Phase 2 — expanded command set)
    // ═══════════════════════════════════════════════════════════════════

    private fun startCommandListener() {
        commandListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.value as? Map<String, Any> ?: return
                val action = data["action"] as? String ?: return
                val timestamp = (data["timestamp"] as? Number)?.toLong() ?: 0L

                // Skip already-processed commands (prevents re-processing on reconnect)
                if (timestamp <= lastCommandTimestamp && lastCommandTimestamp > 0) return
                lastCommandTimestamp = timestamp

                val message = data["message"] as? String ?: ""

                when (action) {
                    "INTERVENE" -> {
                        Log.w(TAG, "INTERVENE command received: $message")
                        addSystemMessage("🚨 INTERVENCE: $message")
                        onIntervene?.invoke(message)
                    }

                    "BLOCK_APPS" -> {
                        // Extract apps list from command data
                        val appsList = (data["appsToBlock"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        Log.w(TAG, "BLOCK_APPS command received: apps=$appsList message=$message")
                        _blockedApps.value = appsList
                        addSystemMessage("🚫 Blokováno: ${appsList.joinToString(", ")}")
                        onBlockApps?.invoke(appsList, message)
                    }

                    "UNBLOCK_APPS" -> {
                        Log.i(TAG, "UNBLOCK_APPS command received: $message")
                        _blockedApps.value = emptyList()
                        addSystemMessage("✅ Aplikace odblokovány")
                        onUnblockApps?.invoke(message)
                    }

                    "LOCK_SCREEN" -> {
                        Log.w(TAG, "LOCK_SCREEN command received: $message")
                        addSystemMessage("🔒 Zamkni telefon: $message")
                        onLockScreen?.invoke(message)
                    }

                    "CLEAR" -> {
                        Log.i(TAG, "CLEAR command received")
                        _blockedApps.value = emptyList()
                        addSystemMessage("Intervence zrušena")
                        onClear?.invoke()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command listener cancelled: ${error.message}")
            }
        }
        deviceRef?.child("command")?.addValueEventListener(commandListener!!)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Chat Outbox Listener (AI responses from orchestrator)
    // ═══════════════════════════════════════════════════════════════════

    private fun startChatOutboxListener() {
        chatOutboxListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.value as? Map<String, Any> ?: return
                val text = data["text"] as? String ?: return
                val timestamp = (data["timestamp"] as? Number)?.toLong() ?: 0L

                // Skip already-processed messages (prevents re-processing on reconnect)
                if (timestamp <= lastChatOutboxTimestamp && lastChatOutboxTimestamp > 0) return
                lastChatOutboxTimestamp = timestamp

                if (text.isNotEmpty()) {
                    addOrchestratorMessage(text)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Chat outbox listener cancelled: ${error.message}")
            }
        }
        deviceRef?.child("chat_outbox")?.addValueEventListener(chatOutboxListener!!)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Phone State Writer (Phase 3 — extended sensor data)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send phone sensor state to the Orchestrator via Firebase.
     * Called by SensorService when screen_on, foreground app, lock state, or battery changes.
     *
     * Writes to: /rails/devices/my_phone/phone_state
     * Payload format:
     *   {
     *     "screen_on": true,
     *     "app": "com.instagram.android",
     *     "app_name": "Instagram",
     *     "device_locked": false,
     *     "timestamp": 1234567890,
     *     "battery_level": 85,           // Phase 3: battery percentage
     *     "notifications": ["..."],       // Phase 3: notification texts (if available)
     *     "screen_text": ""              // Phase 3: screen content text (if available)
     *   }
     */
    fun sendState(
        screenOn: Boolean,
        foregroundApp: String,
        deviceLocked: Boolean = true,
        batteryLevel: Int? = null,
        notifications: List<String> = emptyList(),
        screenText: String = ""
    ) {
        val ref = deviceRef ?: run {
            Log.w(TAG, "Cannot send state — Firebase not connected")
            return
        }

        val friendlyName = if (foregroundApp.isNotEmpty()) getFriendlyAppName(foregroundApp) else ""

        val state = mutableMapOf<String, Any?>(
            "screen_on" to screenOn,
            "app" to foregroundApp,
            "app_name" to friendlyName,
            "device_locked" to deviceLocked,
            "timestamp" to ServerValue.TIMESTAMP,
            "battery_level" to batteryLevel,
        )

        // Only include notifications if we have data (saves bandwidth)
        if (notifications.isNotEmpty()) {
            state["notifications"] = notifications
        }

        // Only include screen_text if non-empty (saves bandwidth)
        if (screenText.isNotEmpty()) {
            state["screen_text"] = screenText
        }

        ref.child("phone_state").setValue(state)
            .addOnSuccessListener {
                Log.d(TAG, "Phone state -> Firebase: screen_on=$screenOn, app=$foregroundApp, " +
                        "device_locked=$deviceLocked, battery=$batteryLevel, " +
                        "notifs=${notifications.size}, screen_text_len=${screenText.length}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write phone state: ${e.message}")
            }
    }

    /**
     * Legacy overload — backward compatible with old SensorService calls.
     */
    fun sendState(screenOn: Boolean, foregroundApp: String, deviceLocked: Boolean = true) {
        sendState(
            screenOn = screenOn,
            foregroundApp = foregroundApp,
            deviceLocked = deviceLocked,
            batteryLevel = null,
            notifications = emptyList(),
            screenText = ""
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Chat Channel
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send a chat message from the user to the Orchestrator via Firebase.
     *
     * Writes to: /rails/devices/my_phone/chat_inbox
     * The Node.js orchestrator listens on this path and responds via chat_outbox.
     */
    fun sendChatMessage(text: String) {
        val ref = deviceRef ?: return
        if (text.isBlank()) return

        addUserMessage(text)

        val message = mapOf(
            "text" to text,
            "timestamp" to ServerValue.TIMESTAMP,
        )

        ref.child("chat_inbox").setValue(message)
            .addOnSuccessListener {
                Log.d(TAG, "Chat -> Firebase: ${text.substring(0, minOf(80, text.length))}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send chat message: ${e.message}")
                addSystemMessage("Chyba odesilani: ${e.message}")
            }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Message Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun addUserMessage(text: String) {
        val msg = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            role = MessageRole.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + msg
    }

    private fun addOrchestratorMessage(text: String) {
        val msg = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            role = MessageRole.ORCHESTRATOR,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + msg
    }

    private fun addSystemMessage(text: String) {
        val msg = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            role = MessageRole.SYSTEM,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + msg
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Data Models
// ═══════════════════════════════════════════════════════════════════════

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

enum class MessageRole {
    USER,
    ORCHESTRATOR,
    SYSTEM,
}

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val timestamp: Long,
) {
    val timestampText: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
