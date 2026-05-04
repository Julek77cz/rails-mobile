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
import android.content.Context
import cz.julek.rails.service.AppWatcherService
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

    // Dedup buffer: remember last N outbox message texts to prevent duplicates
    // on orchestrator restart (Firebase re-sends the same value on reconnect)
    private val recentOutboxTexts = ArrayDeque<String>(maxSize = 10)
    private const val DEDUP_BUFFER_SIZE = 10

    private fun isDuplicateOutbox(text: String): Boolean {
        if (recentOutboxTexts.contains(text)) return true
        recentOutboxTexts.addLast(text)
        if (recentOutboxTexts.size > DEDUP_BUFFER_SIZE) {
            recentOutboxTexts.removeFirst()
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Intervention Listeners (Phase 2 — expanded)
    // ═══════════════════════════════════════════════════════════════════

    var onIntervene: ((message: String) -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onBlockApps: ((apps: List<String>, message: String) -> Unit)? = null
    var onUnblockApps: ((message: String) -> Unit)? = null
    var onLockScreen: ((message: String) -> Unit)? = null

    // Chat message callback — fired when AI sends a response (for system notification)
    var onChatMessage: ((text: String) -> Unit)? = null

    // Application context for SharedPreferences access
    private var appContext: Context? = null

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
    fun connect(context: Context? = null) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connecting/connected — ignoring duplicate connect()")
            return
        }

        appContext = context ?: appContext
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
                        // Load chat history on first connect
                        loadChatHistory()
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

                        // Persist locally so blocking works even without Firebase
                        appContext?.let { AppWatcherService.saveBlockedApps(it, appsList) }

                        addSystemMessage("🚫 Blokováno: ${appsList.joinToString(", ")}")

                        // CRITICAL: Force-check the current foreground app.
                        // If the blocked app is ALREADY open, no accessibility event
                        // will fire (the app is already in the foreground).
                        // We must actively check and kick NOW.
                        Log.i(TAG, "Calling AppWatcherService.forceCheckAndKick() for immediate blocking")
                        AppWatcherService.forceCheckAndKick()

                        onBlockApps?.invoke(appsList, message)
                    }

                    "UNBLOCK_APPS" -> {
                        Log.i(TAG, "UNBLOCK_APPS command received: $message")
                        _blockedApps.value = emptyList()

                        // Clear local persistence too
                        appContext?.let { AppWatcherService.clearBlockedApps(it) }

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

                        // Clear local persistence too
                        appContext?.let { AppWatcherService.clearBlockedApps(it) }

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
                // Use BOTH timestamp check AND content dedup for robustness
                if (text.isNotEmpty()) {
                    val isOldTimestamp = timestamp <= lastChatOutboxTimestamp && lastChatOutboxTimestamp > 0
                    val isDuplicate = isDuplicateOutbox(text)

                    if (!isOldTimestamp && !isDuplicate) {
                        lastChatOutboxTimestamp = timestamp
                        addOrchestratorMessage(text)
                        // Notify SensorService to show system notification
                        onChatMessage?.invoke(text)
                    } else {
                        Log.d(TAG, "Skipping duplicate outbox message: ${text.substring(0, minOf(40, text.length))}")
                    }
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
        // NOTE: Do NOT save to chat_history here — Node.js orchestrator already
        // persists user messages to chat_history. Dual-writing caused duplicates
        // because Android uses System.currentTimeMillis() while Node.js uses
        // ServerValue.TIMESTAMP, so dedup by timestamp failed.
    }

    private fun addOrchestratorMessage(text: String) {
        val msg = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            role = MessageRole.ORCHESTRATOR,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + msg
        // NOTE: Do NOT save to chat_history here — Node.js orchestrator already
        // persists AI responses to chat_history. Dual-writing caused duplicates.
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

    // ═══════════════════════════════════════════════════════════════════
    //  Chat History Persistence
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Save a chat message to Firebase chat_history for persistence.
     * This allows messages to survive app restarts.
     * Path: /rails/devices/my_phone/chat_history/{auto-id}
     * Keeps last 50 messages (oldest auto-deleted by orchestrator or cleanup).
     */
    private fun saveChatMessageToHistory(msg: ChatMessage) {
        val ref = deviceRef ?: return
        val roleStr = when (msg.role) {
            MessageRole.USER -> "user"
            MessageRole.ORCHESTRATOR -> "orchestrator"
            MessageRole.SYSTEM -> "system"
        }
        val entry = mapOf(
            "role" to roleStr,
            "text" to msg.text,
            "timestamp" to msg.timestamp
        )
        ref.child("chat_history").push().setValue(entry)
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to save chat history: ${e.message}")
            }
    }

    /**
     * Load chat history from Firebase on connect.
     * Reads the last N messages from /rails/devices/my_phone/chat_history.
     * Deduplicates by (role + text_prefix) to handle cases where the same
     * logical message was written by both Android and Node.js (legacy).
     * Merges with any real-time messages that arrived during the async load
     * to prevent data loss.
     */
    fun loadChatHistory() {
        val ref = deviceRef ?: return
        ref.child("chat_history").orderByChild("timestamp").limitToLast(100).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) return@addOnSuccessListener

                val historyMessages = mutableListOf<ChatMessage>()
                val seen = mutableSetOf<String>()  // dedup key: "role|text_prefix"

                for (child in snapshot.children) {
                    val roleStr = child.child("role").getValue(String::class.java) ?: continue
                    val text = child.child("text").getValue(String::class.java) ?: continue
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: continue

                    // Dedup by role + text prefix (NOT timestamp — Android and Node.js
                    // use different clock sources, so timestamps differ for the same message)
                    val dedupKey = "$roleStr|${text.substring(0, minOf(60, text.length))}"
                    if (!seen.add(dedupKey)) continue  // already seen, skip duplicate

                    val role = when (roleStr) {
                        "user" -> MessageRole.USER
                        "orchestrator" -> MessageRole.ORCHESTRATOR
                        else -> MessageRole.SYSTEM
                    }
                    historyMessages.add(ChatMessage(
                        id = messageIdCounter.incrementAndGet(),
                        role = role,
                        text = text,
                        timestamp = timestamp
                    ))
                }

                if (historyMessages.isNotEmpty()) {
                    // Merge: keep any real-time messages that arrived during the async load
                    val currentRealtime = _messages.value.filter { it.role == MessageRole.SYSTEM }
                    val merged = (historyMessages + currentRealtime).sortedBy { it.timestamp }
                    _messages.value = merged
                    Log.i(TAG, "Chat history loaded: ${historyMessages.size} messages from Firebase, " +
                            "${currentRealtime.size} real-time system messages preserved")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to load chat history: ${e.message}")
            }
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
