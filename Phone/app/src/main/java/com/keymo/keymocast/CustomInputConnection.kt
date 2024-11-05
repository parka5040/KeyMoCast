package com.keymo.keymocast

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

class CustomInputConnection(
    target: InputConnection,
    mutable: Boolean,
    private val webSocketManager: WebSocketManager,
    private val editText: EditText
) : InputConnectionWrapper(target, mutable) {

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength == 1 && afterLength == 0) {
            webSocketManager.sendSpecialKey("BACK")
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    webSocketManager.sendSpecialKey("BACK")
                }
                KeyEvent.KEYCODE_ENTER -> {
                    webSocketManager.sendSpecialKey("RETURN")
                    editText.post { editText.setText("") }
                    return true
                }
            }
        }
        return super.sendKeyEvent(event)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == "\n") {
            webSocketManager.sendSpecialKey("RETURN")
            editText.post { editText.setText("") }
            return true
        }
        return super.commitText(text, newCursorPosition)
    }

    companion object {
        private const val TAG = "CustomInputConnection"
    }
}