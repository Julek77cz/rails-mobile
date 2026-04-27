package cz.julek.rails.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket Manager — bidirectional communication hub with Rails Orchestrator.
 *
 * Connects via OkHttp WebSocket to ws://<server>:3000/ws
 *
 * Three data channels over a single connection:
 *
 *   1. SENSOR — phone_state telemetry (screen_on, foreground app)
 *      Phone → Server: { "type": "phone_state", "screen_on": true, "app": "Instagram" }
 *
 *   2. CHAT — user ↔ AI text messages
 *      Phone → Server: { "type": "chat", "text": "..." }
 *      Server → Phone: { "type": "chat", "text": "..." }
 *
 *   3. COMMAND — server-initiated interventions
 *      Server → Phone: { "type": "command", "action": "INTERVENE", "message": "..." }
 *      Server → Phone: { "type": "command", "action": "CLEAR" }
 */
object WebSocketManager {

    private const val TAG = "Rails/WS"

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
    //  OkHttp WebSocket
    // ═══════════════════════════════════════════════════════════════════

    private val client = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentAddress: String = ""

    // ═══════════════════════════════════════════════════════════════════
    //  Sensor State
    // ═══════════════════════════════════════════════════════════════════

    private var lastScreenOn: Boolean = false
    private var lastForegroundApp: String = ""
    private var lastDeviceLocked: Boolean = true

    // ═══════════════════════════════════════════════════════════════════
    //  Intervention Listener
    // ═══════════════════════════════════════════════════════════════════

    var onIntervene: ((message: String) -> Unit)? = null
    var onClear: (() -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════════
    //  Connection Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connect to the Orchestrator WebSocket server.
     * @param address Format: "192.168.1.50:3000"
     */
    fun connect(address: String) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connecting/connected — ignoring duplicate connect()")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        currentAddress = address

        val wsUrl = "ws://$address/ws"
        Log.i(TAG, "Connecting to $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected!")
                _connectionState.value = ConnectionState.CONNECTED
                addSystemMessage("Připojeno k serveru $address")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS ← $text")
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary messages not used
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code")
                _connectionState.value = ConnectionState.DISCONNECTED
                addSystemMessage("Spojení uzavřeno")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                addSystemMessage("Chyba připojení: ${t.message}")
            }
        })
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        addSystemMessage("Odpojeno od serveru")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Incoming Message Handler
    // ═══════════════════════════════════════════════════════════════════

    private fun handleIncomingMessage(raw: String) {
        try {
            // Simple JSON parsing without kotlinx.serialization for now
            val msg = parseJsonToMap(raw) ?: return
            val type = msg["type"] as? String ?: return

            when (type) {
                "welcome" -> {
                    val zone = msg["argusZone"] as? String ?: "?"
                    val score = msg["focusScore"] as? Number ?: 0
                    addOrchestratorMessage(
                        "Připojeno! Aktuální stav: zóna=$zone, focus=${score.toDouble().toFixed(2)}"
                    )
                }
                "ack" -> {
                    Log.d(TAG, "Ack received for: ${msg["for"]}")
                }
                "chat" -> {
                    val text = msg["text"] as? String ?: ""
                    if (text.isNotEmpty()) {
                        addOrchestratorMessage(text)
                    }
                }
                "command" -> {
                    val action = msg["action"] as? String ?: ""
                    val message = msg["message"] as? String ?: ""
                    when (action) {
                        "INTERVENE" -> {
                            Log.w(TAG, "INTERVENE command received: $message")
                            addSystemMessage("🚨 INTERVENCE: $message")
                            onIntervene?.invoke(message)
                        }
                        "CLEAR" -> {
                            Log.i(TAG, "CLEAR command received")
                            addSystemMessage("Intervence zrušena")
                            onClear?.invoke()
                        }
                    }
                }
                "pong" -> {
                    // Keepalive response — ignore
                }
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming message: ${e.message}")
        }
    }

    /**
     * Minimal JSON parser for flat key-value maps.
     * Handles {"key":"value","key2":123} format.
     */
    private fun parseJsonToMap(json: String): Map<String, Any>? {
        return try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val content = trimmed.substring(1, trimmed.length - 1)
            val result = mutableMapOf<String, Any>()
            // Simple regex-based parsing for flat JSON
            val regex = """"([^"]+)"\s*:\s*(?:"([^"]*)"|([\d.]+)|(\w+))""".toRegex()
            regex.findAll(content).forEach { match ->
                val key = match.groupValues[1]
                val value: Any = when {
                    match.groupValues[2].isNotEmpty() -> match.groupValues[2] // string
                    match.groupValues[3].isNotEmpty() -> { // number
                        val numStr = match.groupValues[3]
                        numStr.toDoubleOrNull() ?: numStr
                    }
                    match.groupValues[4].isNotEmpty() -> match.groupValues[4] // bool/null
                    else -> ""
                }
                result[key] = value
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sensor Channel
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send phone sensor state to the Orchestrator.
     * Called by SensorService when screen_on, foreground app, or lock state changes.
     *
     * Payload format (backward compatible):
     * { "type": "phone_state", "screen_on": true, "app": "Instagram", "device_locked": false }
     *
     * The 'device_locked' field is NEW — older orchestrator versions that don't
     * know about it will simply ignore it (safe default: assumes not locked).
     *
     * Lock state semantics:
     *   device_locked = true  → keyguard is showing OR screen is off
     *   device_locked = false → screen is on AND user has unlocked the device
     */
    fun sendState(screenOn: Boolean, foregroundApp: String, deviceLocked: Boolean = true) {
        lastScreenOn = screenOn
        lastForegroundApp = foregroundApp
        lastDeviceLocked = deviceLocked

        if (_connectionState.value != ConnectionState.CONNECTED) return

        val escapedApp = foregroundApp.replace("\"", "\\\"")
        val json = """{"type":"phone_state","screen_on":$screenOn,"app":"$escapedApp","device_locked":$deviceLocked}"""
        sendRaw(json)
        Log.d(TAG, "WS → phone_state: screen_on=$screenOn, app=$foregroundApp, device_locked=$deviceLocked")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Chat Channel
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send a chat message from the user to the Orchestrator.
     *
     * Payload: { "type": "chat", "text": "..." }
     */
    fun sendChatMessage(text: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        if (text.isBlank()) return

        addUserMessage(text)

        val json = """{"type":"chat","text":"${text.replace("\"", "\\\"").replace("\n", "\\n")}"}"""
        sendRaw(json)
        Log.d(TAG, "WS → chat: ${text.substring(0, minOf(80, text.length))}")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Raw Send
    // ═══════════════════════════════════════════════════════════════════

    private fun sendRaw(json: String): Boolean {
        return try {
            val ws = webSocket
            if (ws != null) {
                ws.send(json)
            } else {
                Log.w(TAG, "Cannot send — WebSocket is null")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
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

// Helper extension for Double formatting
private fun Double.toFixed(digits: Int): String =
    String.format("%.${digits}f", this)
