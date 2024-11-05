package com.keymo.keymocast

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(context: Context) {
    private var webSocket: WebSocket? = null
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var currentPin: String = ""

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    fun getSavedPin(): String {
        return preferences.getString(SAVED_PIN, "") ?: ""
    }

    private fun savePin(pin: String) {
        preferences.edit().putString(SAVED_PIN, pin).apply()
    }

    fun connect(serverIp: String, port: Int, pin: String) {
        currentPin = pin
        disconnect()

        val request = Request.Builder()
            .url("ws://$serverIp:$port")
            .build()

        try {
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connectionState.value = ConnectionState.Connected
                    sendAuthenticationMessage(pin)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = ConnectionState.Disconnecting
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = ConnectionState.Disconnected
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                }
            })
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to create WebSocket: ${e.message}")
        }
    }

    private fun sendAuthenticationMessage(pin: String) {
        val authMessage = JSONObject().apply {
            put("type", "authenticate")
            put("pin", pin)
        }
        val sent = webSocket?.send(authMessage.toString()) ?: false
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
                        disconnect()
                    }
                }
                "error" -> {
                    val errorMsg = json.getString("message")
                    _connectionState.value = ConnectionState.Error(errorMsg)
                }
                "ack" -> {
                    json.getString("receivedType")
                }
                else -> {
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to parse server message")
        }
    }

    fun sendMouseMove(deltaX: Float, deltaY: Float, sensitivity: Float = 1.0f) {
        if (_connectionState.value !is ConnectionState.Authenticated) return

        val message = JSONObject().apply {
            put("type", "mouseMove")
            put("deltaX", deltaX)
            put("deltaY", deltaY)
            put("sensitivity", sensitivity)
        }
        webSocket?.send(message.toString())
    }

    fun sendMouseClick(isRight: Boolean = false, isDouble: Boolean = false) {
        if (_connectionState.value !is ConnectionState.Authenticated) return

        val message = JSONObject().apply {
            put("type", "mouseClick")
            put("button", if (isRight) "right" else "left")
            put("isDouble", isDouble)
        }
        webSocket?.send(message.toString())
    }

    fun sendScroll(deltaY: Int) {
        if (_connectionState.value !is ConnectionState.Authenticated) return

        val message = JSONObject().apply {
            put("type", "scroll")
            put("deltaY", deltaY)
        }
        webSocket?.send(message.toString())
    }

    fun sendKeyInput(text: String, isSpecial: Boolean = false) {
        if (_connectionState.value !is ConnectionState.Authenticated) {
            return
        }

        try {
            val message = JSONObject().apply {
                put("type", "keyInput")
                put("text", text)
                put("isSpecial", isSpecial)
            }

            webSocket?.send(message.toString()) ?: false

        } catch (_: Exception) {
        }
    }


    fun sendSpecialKey(keyCode: String) {
        if (_connectionState.value !is ConnectionState.Authenticated) {
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

            webSocket?.send(message.toString()) ?: false

        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User initiated disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    companion object {
        const val SAVED_PIN = "saved_pin"

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