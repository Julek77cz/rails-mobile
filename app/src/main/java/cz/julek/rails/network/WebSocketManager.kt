package cz.julek.rails.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket Manager — bi-directional communication hub for the Orchestrator.
 *
 * This singleton handles two distinct data channels over a single WebSocket:
 *
 *   1. SENSOR channel — telemetry (screen_on/off, foreground app changes)
 *      Outgoing only: phone → Orchestrator
 *      Format: { "type": "phone_state", "screen_on": true, "app": "Instagram" }
 *
 *   2. CHAT channel — user ↔ AI text messages
 *      Bidirectional: user text → Orchestrator, AI response → user
 *      Format: { "type": "chat", "text": "..." }
 *
 *   3. COMMAND channel — Orchestrator → phone interventions
 *      Incoming only: INTERVENE / CLEAR overlay commands
 *      Format: { "type": "command", "action": "INTERVENE", "message": "..." }
 *
 * Full OkHttp WebSocket implementation comes in Phase 2.
 * This skeleton provides the state flow architecture and message models
 * so that UI (DashboardScreen, ChatScreen) can already observe and react.
 */
object WebSocketManager {

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
    //  Sensor State (latest reading)
    // ═══════════════════════════════════════════════════════════════════

    private var lastScreenOn: Boolean = false
    private var lastForegroundApp: String = ""

    // ═══════════════════════════════════════════════════════════════════
    //  Connection Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connect to the Orchestrator WebSocket server.
     * @param address Format: "192.168.1.50:3000"
     */
    fun connect(address: String) {
        _connectionState.value = ConnectionState.CONNECTING

        // TODO: OkHttp WebSocket connection to ws://<address>/ws
        // On success: _connectionState.value = ConnectionState.CONNECTED
        // On failure: _connectionState.value = ConnectionState.DISCONNECTED

        // ── Simulated for Phase 1 (UI testing) ──
        // Simulate connection delay then mark connected
        Thread {
            Thread.sleep(800)
            _connectionState.value = ConnectionState.CONNECTED
            addSystemMessage("Připojeno k serveru $address")

            // Simulate a welcome message from the Orchestrator
            Thread.sleep(1200)
            addOrchestratorMessage(
                "Ahoj! Jsem Rails, tvůj AI asistent. " +
                "Sleduji tvou pozornost a pomůžu ti zůstat v pásmu. " +
                "Co potřebuješ?"
            )
        }.start()
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        // TODO: Close WebSocket connection
        addSystemMessage("Odpojeno od serveru")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sensor Channel — send telemetry
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send phone sensor state to the Orchestrator.
     * Called by SensorService when screen_on or foreground app changes.
     */
    fun sendState(screenOn: Boolean, foregroundApp: String) {
        lastScreenOn = screenOn
        lastForegroundApp = foregroundApp

        if (_connectionState.value != ConnectionState.CONNECTED) return

        // TODO: Send JSON via WebSocket
        // val json = """{"type":"phone_state","screen_on":$screenOn,"app":"$foregroundApp"}"""
        // webSocket?.send(json)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Chat Channel — send/receive messages
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send a chat message from the user to the Orchestrator.
     */
    fun sendChatMessage(text: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        if (text.isBlank()) return

        // Add user message to local list immediately
        addUserMessage(text)

        // TODO: Send via WebSocket
        // val json = """{"type":"chat","text":"${text.replace("\"", "\\\"")}"}"""
        // webSocket?.send(json)

        // ── Simulated AI response for Phase 1 (UI testing) ──
        Thread {
            Thread.sleep(1500)
            addOrchestratorMessage(simulateResponse(text))
        }.start()
    }

    /**
     * Called when a chat message arrives from the Orchestrator via WebSocket.
     * (Will be wired to WebSocket onMessage in Phase 2)
     */
    private fun onChatMessageReceived(text: String) {
        addOrchestratorMessage(text)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command Channel — receive interventions
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called when an INTERVENE/CLEAR command arrives from the Orchestrator.
     * (Will be wired to WebSocket onMessage in Phase 2)
     */
    private fun onCommandReceived(action: String, message: String) {
        when (action) {
            "INTERVENE" -> {
                // TODO: Start OverlayService with the message
                addSystemMessage("INTERVENCE: $message")
            }
            "CLEAR" -> {
                // TODO: Stop OverlayService
                addSystemMessage("Intervence zrušena")
            }
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

    /**
     * Clear all chat messages.
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Simulation (Phase 1 only — removed when WebSocket is implemented)
    // ═══════════════════════════════════════════════════════════════════

    private fun simulateResponse(userText: String): String {
        val lower = userText.lowercase()
        return when {
            lower.contains("plán") || lower.contains("plan") ->
                "Dnes máš naplánovaný deep work blok 14:00–16:00. " +
                "Zatím jsi v pásmu — pokračuj!"

            lower.contains("focus") || lower.contains("skóre") || lower.contains("score") ->
                "Tvé aktuální Focus Score je 0.85 — jsi v zóně deep focus. " +
                "VS Code na popředí, žádný mobil. Jen tak dál!"

            lower.contains("cíl") || lower.contains("goal") ->
                "Máš 3 aktivní cíle: 1) dokončit refaktor Orchestrátoru, " +
                "2) napsat testy pro tracker, 3) připravit demo. " +
                "Který chceš rozebrat?"

            lower.contains("přestáv") || lower.contains("break") ->
                "Ještě ne — tvůj focus score je stabilní. " +
                "Doporučuji počkat na přirozený pokles. " +
                "Upozorním tě, až bude čas na pauzu."

            lower.contains("ahoj") || lower.contains("čau") || lower.contains("hello") ->
                "Ahoj! Jsem Rails, tvůj produktivní parťák. " +
                "Můžu sledovat tvůj focus, spravovat cíle, nebo tě nakopat, " +
                "když se začneš dívat na YouTube. Co potřebuješ?"

            else ->
                "Přijato. Zpracovávám tvůj požadavek... " +
                "Jako tvůj AI supervizor ti doporučuji zůstat v práci — " +
                "tvůj focus score je momentálně stabilní."
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
