package com.keymo.keymocast

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.InputFilter
import android.text.method.PasswordTransformationMethod
import android.view.WindowManager
import android.view.View
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.keymo.keymocast.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import kotlin.math.abs
import android.view.ViewGroup


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences
    private var isKeyboardMode = false
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var connectionManager: ConnectionManager

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasMoved = false

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        connectionManager = ConnectionManager(this)
        webSocketManager = WebSocketManager(this)


        val savedPin = webSocketManager.getSavedPin()
        binding.pinInputLayout.editText?.setText(savedPin)

        val customEditText = CustomEditText(this, webSocketManager = webSocketManager).apply {
            id = binding.keyboardInput.id
            layoutParams = binding.keyboardInput.layoutParams
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = binding.keyboardInput.hint
        }

        (binding.keyboardInput.parent as ViewGroup).let { parent ->
            val index = parent.indexOfChild(binding.keyboardInput)
            parent.removeView(binding.keyboardInput)
            parent.addView(customEditText, index)
        }

        ActivityMainBinding::class.java.getDeclaredField("keyboardInput").apply {
            isAccessible = true
            set(binding, customEditText)
        }

        setupUI()
        setupKeyboardButton()
        observeConnectionState()
    }

    private fun setupUI() {
        binding.apply {
            pinInputLayout.editText?.apply {
                filters = arrayOf(InputFilter.LengthFilter(4))
                transformationMethod = PasswordTransformationMethod.getInstance()
                isFocusableInTouchMode = true
                isFocusable = true
            }

            touchpadArea.apply {
                isClickable = true
                isFocusable = true
                isEnabled = false
                setBackgroundColor(ContextCompat.getColor(this@MainActivity,
                    android.R.color.darker_gray))
            }

            settingsButton.setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            touchpadCard.isEnabled = false
        }
        setupTouchpad()
        setupKeyboardHandling()

        // Might need to get rid of this for next version.
        binding.keyboardInput.apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = false
            maxLines = 3
        }
    }

    private fun setupKeyboardButton() {
        binding.apply {
            keyboardButton.setOnClickListener {
                toggleKeyboardMode()
            }

            keyboardInput.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    showKeyboard(view)
                }
            }

            keyboardButton.isEnabled = false
            keyboardInputLayout.visibility = View.GONE
        }
    }

    private fun toggleKeyboardMode() {
        isKeyboardMode = !isKeyboardMode
        binding.apply {
            if (isKeyboardMode) {
                touchpadCard.visibility = View.GONE
                keyboardInputContainer.visibility = View.VISIBLE
                keyboardInput.requestFocus()
                showKeyboard(keyboardInput)
            } else {
                touchpadCard.visibility = View.VISIBLE
                keyboardInputContainer.visibility = View.GONE
                hideKeyboard(keyboardInput)
            }
        }
    }

    private var backspaceHandler: Handler? = null
    private val backspaceRunnable = object : Runnable {
        override fun run() {
            webSocketManager.sendSpecialKey("BACK")
            backspaceHandler?.postDelayed(this, BACKSPACE_REPEAT_DELAY)
        }
    }

    private fun startRepeatingBackspace() {
        backspaceHandler = Handler(Looper.getMainLooper()).also { handler ->
            handler.postDelayed(backspaceRunnable, BACKSPACE_REPEAT_DELAY)
        }
    }

    private fun stopRepeatingBackspace() {
        backspaceHandler?.removeCallbacks(backspaceRunnable)
        backspaceHandler = null
    }

    companion object {
        private const val BACKSPACE_REPEAT_DELAY = 100L // ms
    }

    private fun handleConnectionRequest() {
        val pin = binding.pinInputLayout.editText?.text?.toString()
        if (pin.isNullOrEmpty() || pin.length != 4) {
            showMessage(getString(R.string.error_invalid_pin))
            return
        }

        updateUIForSearching(true)

        lifecycleScope.launch {
            try {
                val serverIp = connectionManager.findServer()
                if (serverIp != null) {
                    webSocketManager.connect(
                        serverIp,
                        connectionManager.getServerPort(),
                        pin
                    )
                } else {
                    showMessage(getString(R.string.error_server_not_found))
                    updateUIForSearching(false)
                }
            } catch (e: Exception) {
                showMessage(getString(R.string.error_connection_failed))
                updateUIForSearching(false)
            }
        }
    }

    private fun updateUIForSearching(searching: Boolean) {
        binding.apply {
            connectButton.isEnabled = !searching
            pinInputLayout.isEnabled = !searching
            connectionStatus.text = if (searching) {
                getString(R.string.status_searching)
            } else {
                getString(R.string.status_not_connected)
            }
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                webSocketManager.connectionState.collect { state ->
                    updateConnectionStatus(state)
                }
            }
        }
    }

    private fun updateConnectionStatus(state: WebSocketManager.ConnectionState) {
        binding.apply {
            val statusText = when (state) {
                is WebSocketManager.ConnectionState.Disconnected -> {
                    disableControls()
                    getString(R.string.status_not_connected)
                }
                is WebSocketManager.ConnectionState.Connected -> {
                    getString(R.string.status_connecting)
                }
                is WebSocketManager.ConnectionState.Authenticated -> {
                    enableControls()
                    showMessage(getString(R.string.success_connected))
                    getString(R.string.status_authenticated)
                }
                is WebSocketManager.ConnectionState.AuthenticationFailed -> {
                    disableControls()
                    showMessage(getString(R.string.error_auth_failed))
                    getString(R.string.status_auth_failed)
                }
                is WebSocketManager.ConnectionState.Disconnecting -> {
                    disableControls()
                    getString(R.string.status_disconnecting)
                }
                is WebSocketManager.ConnectionState.Error -> {
                    disableControls()
                    if (state.message == "error_invalid_pin") {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.status_auth_failed)
                            .setMessage(R.string.error_invalid_pin)
                            .setPositiveButton(android.R.string.ok) { dialog, _  -> dialog.dismiss()}
                            .show()
                    } else {
                        showMessage("Error: ${state.message}")
                    }
                    getString(R.string.status_error)
                }
            }

            connectionStatus.text = statusText
            connectButton.apply {
                isEnabled = true
                text = if (state is WebSocketManager.ConnectionState.Authenticated) {
                    getString(R.string.button_disconnect)
                } else {
                    getString(R.string.button_connect)
                }

                setOnClickListener {
                    if (state is WebSocketManager.ConnectionState.Authenticated) {
                        webSocketManager.disconnect()
                        disableControls()
                        showMessage(getString(R.string.status_disconnected))
                    } else {
                        handleConnectionRequest()
                    }
                }
            }
        }
    }

    private fun enableControls() {
        binding.apply {
            touchpadCard.isEnabled = true
            touchpadArea.isEnabled = true
            keyboardButton.isEnabled = true
            pinInputLayout.isEnabled = false
        }
    }

    private fun setupTouchpad() {
        binding.touchpadArea.setOnTouchListener(fun(_: View, event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    touchStartX = event.x
                    touchStartY = event.y
                    hasMoved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val moveThreshold = SettingsActivity.getMoveThreshold(preferences)
                    val totalMoveX = abs(event.x - touchStartX)
                    val totalMoveY = abs(event.y - touchStartY)

                    if (totalMoveX > moveThreshold || totalMoveY > moveThreshold) {
                        hasMoved = true
                    }

                    if (hasMoved) {
                        if (event.pointerCount == 2) {
                            val scrollScaling = SettingsActivity.getScrollScaling(preferences)
                            val deltaY = (event.y - lastTouchY) * scrollScaling
                            webSocketManager.sendScroll(deltaY.toInt())
                        } else if (event.pointerCount == 1) {
                            val deltaX = event.x - lastTouchX
                            val deltaY = event.y - lastTouchY
                            webSocketManager.sendMouseMove(deltaX, deltaY)
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val currentTime = System.currentTimeMillis()
                    val touchDuration = currentTime - lastTouchTime

                    val clickTimeout = SettingsActivity.getClickTimeout(preferences)
                    val doubleTapTime = SettingsActivity.getDoubleTapTime(preferences)

                    if (!hasMoved && event.pointerCount == 1 &&
                        touchDuration > clickTimeout
                    ) {
                        if (currentTime - lastTouchTime < doubleTapTime) {
                            webSocketManager.sendMouseClick(isDouble = true)
                            lastTouchTime = 0
                        } else {
                            webSocketManager.sendMouseClick()
                            lastTouchTime = currentTime
                        }
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (!hasMoved && event.pointerCount == 2) {
                        webSocketManager.sendMouseClick(isRight = true)
                    }
                    true
                }

                else -> false
            }
        })
    }

    private fun setupKeyboardHandling() {
        binding.backspaceButton.apply {
            setOnLongClickListener {
                startRepeatingBackspace()
                true
            }

            setOnTouchListener(fun(_: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopRepeatingBackspace()
                    }
                }
                return false
            })

            setOnClickListener {
                webSocketManager.sendSpecialKey("BACK")
            }
        }

        binding.enterButton.apply {
            setOnClickListener {
                webSocketManager.sendSpecialKey("RETURN")
                binding.keyboardInput.setText("")
            }
        }

        binding.keyboardInput.apply {
            addTextChangedListener(object : TextWatcher {
                private var isInternalChange = false

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!isInternalChange && count > 0 && before == 0) {
                        val newText = s?.subSequence(start, start + count).toString()
                        if (newText != "\n") {
                            webSocketManager.sendKeyInput(newText)
                            isInternalChange = true
                            setText("")
                            isInternalChange = false
                        }
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }



    private fun disableControls() {
        binding.apply {
            touchpadCard.isEnabled = false
            keyboardButton.isEnabled = false
            pinInputLayout.isEnabled = true
            if (isKeyboardMode) {
                toggleKeyboardMode()
            }
        }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRepeatingBackspace()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}