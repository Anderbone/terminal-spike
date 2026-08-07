package com.yanjiyu.terminalspike.terminal.view

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import java.nio.charset.StandardCharsets

class TerminalInputConnection(
    targetView: View,
    private val sink: TerminalInputSink,
) : BaseInputConnection(targetView, false) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!text.isNullOrEmpty()) {
            sink.send(text.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // Phase 0 deliberately waits for commitText. Some IMEs therefore show composition only
        // in their own UI; buffered/direct input modes can be split after parser integration.
        return true
    }

    override fun finishComposingText(): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) {
            // DEL (0x7f) is the conventional terminal backspace byte used by this spike.
            sink.send(TerminalKeySequences.BACKSPACE)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        TerminalKeySequences.forKeyCode(event.keyCode)?.let(sink::send)
        return true
    }

    companion object {
        fun configureEditorInfo(outAttrs: EditorInfo) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
    }
}
