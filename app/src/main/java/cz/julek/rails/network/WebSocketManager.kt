package cz.julek.rails.network

/**
 * WebSocket Manager — handles bi-directional communication with Orchestrator.
 *
 * Outgoing: screen_on/off events, foreground app changes
 * Incoming: INTERVENE / CLEAR commands
 *
 * Will be implemented in Phase 2 with OkHttp WebSocket.
 */
object WebSocketManager {

    var isConnected: Boolean = false
        private set

    /**
     * Connect to the Orchestrator WebSocket server.
     * @param address Format: "192.168.1.50:3000"
     */
    fun connect(address: String) {
        // TODO: OkHttp WebSocket connection
        // ws://<address>/ws
    }

    /**
     * Send sensor data to the Orchestrator.
     */
    fun sendState(screenOn: Boolean, foregroundApp: String) {
        // TODO: Send JSON via WebSocket
        // { "type": "phone_state", "screen_on": true, "app": "Instagram" }
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        // TODO: Close WebSocket connection
        isConnected = false
    }
}
