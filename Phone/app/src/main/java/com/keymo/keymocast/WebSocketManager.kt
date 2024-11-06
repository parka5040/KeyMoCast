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

class WebSocketManager(context: Context) : CoroutineScope {
    private var webSocket: WebSocket? = null
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private var currentPin: String = ""
    private var currentServerIp: String = ""
    private var currentPort: Int = 0
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var keepAliveJob: Job? = null
    private var isManuallyDisconnected = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + Job()

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
        reconnectAttempts = 0
        isManuallyDisconnected = false

        disconnect(isManual = false)
        establishConnection()
    }

    private fun establishConnection() {
        // Check if it's user disconnect or the Websocket timed out
        if (isManuallyDisconnected) {
            return
        }

        val request = Request.Builder()
            .url("ws://$currentServerIp:$currentPort")
            .build()

        try {
            webSocket = client.newWebSocket(request, createWebSocketListener())
            startKeepAlive()
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to create WebSocket: ${e.message}")
            handleReconnection()
        }
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isManuallyDisconnected) {
                _connectionState.value = ConnectionState.Connected
                sendAuthenticationMessage(currentPin)
                reconnectAttempts = 0
            } else {
                webSocket.close(1000, "Manual disconnect active")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.Disconnecting
            stopKeepAlive()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.Disconnected
            if (!isManuallyDisconnected) {
                handleReconnection()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
            if (!isManuallyDisconnected) {
                handleReconnection()
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
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
    }

    private fun sendKeepAlive() {
        if (!isManuallyDisconnected) {
            val message = JSONObject().apply {
                put("type", "keepAlive")
            }
            webSocket?.send(message.toString())
        }
    }

    private fun handleReconnection() {
        if (!isManuallyDisconnected &&
            reconnectAttempts < MAX_RECONNECT_ATTEMPTS &&
            _connectionState.value !is ConnectionState.AuthenticationFailed) {

            reconnectJob?.cancel()
            reconnectJob = launch {
                delay(RECONNECT_DELAY)
                reconnectAttempts++
                _connectionState.value = ConnectionState.Disconnected
                establishConnection()
            }
        }
    }

    private fun sendAuthenticationMessage(pin: String) {
        if (!isManuallyDisconnected) {
            val authMessage = JSONObject().apply {
                put("type", "authenticate")
                put("pin", pin)
            }
            webSocket?.send(authMessage.toString())
        }
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("type")) {
                "authResponse" -> {
                    val success = json.getBoolean("success")
                    if (success) {
                        _connectionState.value = ConnectionState.Authenticated
                        savePin(currentPin)
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
                "ack" -> {
                    json.getString("receivedType")
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to parse server message")
        }
    }

    fun sendMouseMove(deltaX: Float, deltaY: Float, sensitivity: Float = 1.0f) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) return

        val message = JSONObject().apply {
            put("type", "mouseMove")
            put("deltaX", deltaX)
            put("deltaY", deltaY)
            put("sensitivity", sensitivity)
        }
        webSocket?.send(message.toString())
    }

    fun sendMouseClick(isRight: Boolean = false, isDouble: Boolean = false) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) return

        val message = JSONObject().apply {
            put("type", "mouseClick")
            put("button", if (isRight) "right" else "left")
            put("isDouble", isDouble)
        }
        webSocket?.send(message.toString())
    }

    fun sendScroll(deltaY: Int) {
        if (_connectionState.value !is ConnectionState.Authenticated || isManuallyDisconnected) return

        val message = JSONObject().apply {
            put("type", "scroll")
            put("deltaY", deltaY)
        }
        webSocket?.send(message.toString())
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
            webSocket?.send(message.toString())
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
            webSocket?.send(message.toString())
        } catch (_: Exception) {
        }
    }

    // This is for the manual disconnect
    fun disconnect() {
        disconnect(isManual = true)
    }

    // This is the private one for if it's manual or not
    private fun disconnect(isManual: Boolean) {
        if (isManual) {
            isManuallyDisconnected = true
        }

        stopKeepAlive()
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, if (isManual) "User initiated disconnect" else "System disconnect")
        webSocket = null

        _connectionState.value = ConnectionState.Disconnected
    }

    companion object {
        const val SAVED_PIN = "saved_pin"
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val KEEP_ALIVE_INTERVAL = 5000L // 5 seconds
        const val RECONNECT_DELAY = 2000L // 2 seconds

        private val VALID_SPECIAL_KEYS = setOf(
            "BACK",
            "RETURN",
            "TAB",
            "ESCAPE",
            "DELETE"
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