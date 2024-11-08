package com.keymo.keymocast

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import java.io.IOException

class WebSocketManager(context: Context) : CoroutineScope {
    private var webSocket: WebSocket? = null
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var currentPin: String = ""
    private var currentServerIp: String = ""
    private var currentPort: Int = 0
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var isManuallyDisconnected = false
    private var lastKeepAliveResponse = 0L
    private var lastPingSent = 0L
    private var keepAliveMonitorJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var reconnectAttempts = 0
    private var consecutiveFailures = 0
    private var lastMessageSentTime = 0L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + SupervisorJob()

    fun getSavedPin(): String {
        return preferences.getString(SAVED_PIN, "") ?: ""
    }

    private fun savePin(pin: String) {
        preferences.edit().putString(SAVED_PIN, pin).apply()
    }

    fun connect(serverIp: String, port: Int, pin: String) {
        currentServerIp = serverIp
        currentPort = port
        currentPin = pin
        isManuallyDisconnected = false
        reconnectAttempts = 0
        consecutiveFailures = 0
        disconnect(isManual = false)
        establishConnection()
    }

    private fun establishConnection() {
        if (isManuallyDisconnected) return

        val request = Request.Builder()
            .url("ws://$currentServerIp:$currentPort")
            .build()

        try {
            webSocket = client.newWebSocket(request, createWebSocketListener())
            startKeepAlive()
            startKeepAliveMonitor()
            startConnectionMonitor()
        } catch (e: Exception) {
            handleConnectionError("Failed to create WebSocket: ${e.message}")
        }
    }

    private fun handleConnectionError(message: String) {
        _connectionState.value = ConnectionState.Error(message)
        consecutiveFailures++

        if (!isManuallyDisconnected) {
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                disconnect(isManual = true)
                _connectionState.value = ConnectionState.Error("Connection lost permanently after $MAX_CONSECUTIVE_FAILURES failures")
            } else {
                reconnectWithExponentialBackoff()
            }
        }
    }

    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = launch {
            while (isActive && !isManuallyDisconnected) {
                delay(CONNECTION_MONITOR_INTERVAL)
                if (_connectionState.value is ConnectionState.Authenticated) {
                    checkConnectionHealth()
                }
            }
        }
    }

    private fun checkConnectionHealth() {
        val now = System.currentTimeMillis()
        val timeSinceLastResponse = now - lastKeepAliveResponse
        val timeSinceLastMessage = now - lastMessageSentTime

        if (timeSinceLastResponse > KEEP_ALIVE_TIMEOUT) {
            handleConnectionError("No response received for ${KEEP_ALIVE_TIMEOUT/1000} seconds")
            return
        }

        if (timeSinceLastMessage > PING_INTERVAL) {
            sendPing()
        }
    }

    private fun sendPing() {
        try {
            val message = JSONObject().apply {
                put("type", "ping")
                put("timestamp", System.currentTimeMillis())
            }
            sendWebSocketMessage(message.toString())
            lastPingSent = System.currentTimeMillis()
        } catch (e: Exception) {
            handleConnectionError("Failed to send ping: ${e.message}")
        }
    }

    private fun sendWebSocketMessage(message: String): Boolean {
        return try {
            val ws = webSocket
            if (ws != null) {
                val sent = ws.send(message)
                if (sent) {
                    lastMessageSentTime = System.currentTimeMillis()
                    consecutiveFailures = 0  // Reset failure count on successful send
                }
                sent
            } else {
                false
            }
        } catch (e: Exception) {
            handleConnectionError("Send failed: ${e.message}")
            false
        }
    }

    private fun startKeepAliveMonitor() {
        keepAliveMonitorJob?.cancel()
        keepAliveMonitorJob = launch {
            while (isActive && !isManuallyDisconnected) {
                delay(KEEP_ALIVE_MONITOR_INTERVAL)
                if (_connectionState.value is ConnectionState.Authenticated) {
                    val timeSinceLastResponse = System.currentTimeMillis() - lastKeepAliveResponse
                    if (timeSinceLastResponse > KEEP_ALIVE_TIMEOUT) {
                        handleConnectionError("Keep-alive timeout exceeded")
                    }
                }
            }
        }
    }

    private fun reconnectWithExponentialBackoff() {
        if (isManuallyDisconnected) return

        reconnectJob?.cancel()
        reconnectJob = launch {
            while (isActive && !isManuallyDisconnected &&
                _connectionState.value !is ConnectionState.Authenticated &&
                consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {

                val delayTime = minOf(
                    INITIAL_RECONNECT_DELAY * (1 shl minOf(reconnectAttempts, 5)),
                    MAX_RECONNECT_DELAY
                )
                delay(delayTime)

                if (!isManuallyDisconnected) {
                    establishConnection()
                    reconnectAttempts++
                }
            }
        }
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isManuallyDisconnected) {
                _connectionState.value = ConnectionState.Connected
                sendAuthenticationMessage(currentPin)
                lastKeepAliveResponse = System.currentTimeMillis()
                lastPingSent = System.currentTimeMillis()
                lastMessageSentTime = System.currentTimeMillis()
                reconnectAttempts = 0
                consecutiveFailures = 0
            } else {
                webSocket.close(1000, "Manual disconnect active")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                lastKeepAliveResponse = System.currentTimeMillis()
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "authResponse" -> {
                        val success = json.getBoolean("success")
                        if (success) {
                            _connectionState.value = ConnectionState.Authenticated
                            savePin(currentPin)
                            consecutiveFailures = 0
                        } else {
                            _connectionState.value = ConnectionState.AuthenticationFailed
                            _connectionState.value = ConnectionState.Error("error_invalid_pin")
                            disconnect(isManual = true)
                        }
                    }
                    "error" -> {
                        val errorMsg = json.getString("message")
                        _connectionState.value = ConnectionState.Error(errorMsg)
                    }
                    "ack", "pong" -> {
                        lastKeepAliveResponse = System.currentTimeMillis()
                        consecutiveFailures = 0
                    }
                }
            } catch (e: Exception) {
                handleConnectionError("Failed to parse server message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isManuallyDisconnected) {
                _connectionState.value = ConnectionState.Disconnecting
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isManuallyDisconnected) {
                _connectionState.value = ConnectionState.Disconnected
                reconnectWithExponentialBackoff()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (t is IOException && t.message?.contains("Broken pipe") == true) {
                handleConnectionError("Connection broken: ${t.message}")
            } else {
                handleConnectionError(t.message ?: "Unknown error")
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        lastKeepAliveResponse = System.currentTimeMillis()
        lastMessageSentTime = System.currentTimeMillis()
        keepAliveJob = launch {
            while (isActive && !isManuallyDisconnected) {
                delay(KEEP_ALIVE_INTERVAL)
                if (_connectionState.value is ConnectionState.Authenticated) {
                    sendKeepAlive()
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        keepAliveMonitorJob?.cancel()
        keepAliveMonitorJob = null
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
    }

    private fun sendKeepAlive() {
        if (!isManuallyDisconnected) {
            val message = JSONObject().apply {
                put("type", "keepAlive")
                put("timestamp", System.currentTimeMillis())
            }
            sendWebSocketMessage(message.toString())
        }
    }

    private fun sendAuthenticationMessage(pin: String) {
        val authMessage = JSONObject().apply {
            put("type", "authenticate")
            put("pin", pin)
        }
        sendWebSocketMessage(authMessage.toString())
    }

    fun sendMouseMove(deltaX: Float, deltaY: Float, sensitivity: Float = 1.0f) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) return

        val message = JSONObject().apply {
            put("type", "mouseMove")
            put("deltaX", deltaX)
            put("deltaY", deltaY)
            put("sensitivity", sensitivity)
        }
        sendWebSocketMessage(message.toString())
    }

    fun sendMouseClick(isRight: Boolean = false, isDouble: Boolean = false) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) return

        val message = JSONObject().apply {
            put("type", "mouseClick")
            put("button", if (isRight) "right" else "left")
            put("isDouble", isDouble)
        }
        sendWebSocketMessage(message.toString())
    }

    fun sendScroll(deltaY: Int) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) return

        val message = JSONObject().apply {
            put("type", "scroll")
            put("deltaY", deltaY)
        }
        sendWebSocketMessage(message.toString())
    }

    fun sendKeyInput(text: String, isSpecial: Boolean = false) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) {
            return
        }

        try {
            val message = JSONObject().apply {
                put("type", "keyInput")
                put("text", text)
                put("isSpecial", isSpecial)
            }
            sendWebSocketMessage(message.toString())
        } catch (_: Exception) {
        }
    }

    fun sendSpecialKey(keyCode: String) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) {
            return
        }

        val normalizedKeyCode = keyCode.uppercase()
        if (!VALID_SPECIAL_KEYS.contains(normalizedKeyCode)) {
            return
        }

        try {
            val message = JSONObject().apply {
                put("type", "keyInput")
                put("text", normalizedKeyCode)
                put("isSpecial", true)
            }
            sendWebSocketMessage(message.toString())
        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        disconnect(isManual = true)
    }

    private fun disconnect(isManual: Boolean) {
        if (isManual) {
            isManuallyDisconnected = true
            reconnectAttempts = 0
            consecutiveFailures = 0
        }

        stopKeepAlive()
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, if (isManual) "User initiated disconnect" else "System disconnect")
        webSocket = null

        if (isManual) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    companion object {
        const val SAVED_PIN = "saved_pin"
        const val KEEP_ALIVE_INTERVAL = 15000L
        const val KEEP_ALIVE_MONITOR_INTERVAL = 5000L
        const val KEEP_ALIVE_TIMEOUT = 45000L
        const val INITIAL_RECONNECT_DELAY = 1000L
        const val MAX_RECONNECT_DELAY = 10000L
        const val CONNECTION_MONITOR_INTERVAL = 5000L
        const val PING_INTERVAL = 30000L
        const val MAX_CONSECUTIVE_FAILURES = 5

        private val VALID_SPECIAL_KEYS = setOf(
            "BACK", "RETURN", "TAB", "ESCAPE", "DELETE"
        )
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connected : ConnectionState()
        data object Authenticated : ConnectionState()
        data object AuthenticationFailed : ConnectionState()
        data object Disconnecting : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
}