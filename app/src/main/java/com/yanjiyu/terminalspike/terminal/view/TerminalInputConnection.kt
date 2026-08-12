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
    private val composition = TerminalImeCompositionState()

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composition.commit(text?.toString().orEmpty()).sendIfNotEmpty()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composition.update(text?.toString().orEmpty()).sendIfNotEmpty()
        return true
    }

    override fun finishComposingText(): Boolean {
        composition.finish().sendIfNotEmpty()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (composition.hasPendingText) {
            composition.deleteCodeUnits(beforeLength).sendIfNotEmpty()
            return true
        }
        encodeTerminalDeletion(beforeLength, afterLength).takeIf(ByteArray::isNotEmpty)?.let(sink::send)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (composition.hasPendingText) {
            composition.deleteCodePoints(beforeLength).sendIfNotEmpty()
            return true
        }
        encodeTerminalDeletion(beforeLength, afterLength).takeIf(ByteArray::isNotEmpty)?.let(sink::send)
        return true
    }

    /** Drops an IME-owned staged value before an accessory or hardware control chord is sent. */
    fun resetComposingInput() {
        composition.cancel()
    }

    private fun ByteArray.sendIfNotEmpty() {
        if (isNotEmpty()) sink.send(this)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        TerminalKeySequences.forModifiedKeyCode(
            keyCode = event.keyCode,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            ctrl = event.isCtrlPressed,
        )?.let {
            sink.send(it)
            return true
        }
        if (event.isCtrlPressed) {
            TerminalKeySequences.controlByteForKeyCode(event.keyCode, event.isShiftPressed)?.let { control ->
                val bytes = byteArrayOf(control)
                sink.send(if (event.isAltPressed) TerminalKeySequences.ESCAPE + bytes else bytes)
                return true
            }
        }
        val unicode = event.unicodeChar
        if (unicode > 0 && !Character.isISOControl(unicode)) {
            val bytes = String(Character.toChars(unicode)).toByteArray(StandardCharsets.UTF_8)
            sink.send(if (event.isAltPressed) TerminalKeySequences.ESCAPE + bytes else bytes)
        }
        return true
    }

    companion object {
        fun configureEditorInfo(outAttrs: EditorInfo) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }
}

/**
 * Tracks the text Android keyboards repeatedly publish for one composing word.
 *
 * Simple Latin input is streamed as an append-only delta so the remote shell and cursor remain in
 * lock-step while the user types. Text that may still be transformed by a complex-script IME stays
 * private until commit/finish, preserving the existing no-intermediate-composition contract.
 */
internal class TerminalImeCompositionState {
    private var pendingText: String? = null
    private var emittedText = ""
    private var suppressedFinalization: String? = null

    val hasPendingText: Boolean
        get() = pendingText != null

    fun update(text: String): ByteArray {
        suppressedFinalization = null
        pendingText = text
        return if (text.isDirectlyStreamableComposition()) {
            correction(from = emittedText, to = text).also { emittedText = text }
        } else {
            correction(from = emittedText, to = "").also { emittedText = "" }
        }
    }

    fun commit(text: String): ByteArray {
        if (text == suppressedFinalization) {
            suppressedFinalization = null
            pendingText = null
            emittedText = ""
            return ByteArray(0)
        }
        val result = correction(from = emittedText, to = text)
        pendingText = null
        emittedText = ""
        suppressedFinalization = null
        return result
    }

    fun finish(): ByteArray {
        val text = pendingText ?: return ByteArray(0)
        return commit(text)
    }

    fun deleteCodeUnits(count: Int): ByteArray {
        val current = pendingText ?: return ByteArray(0)
        val next = current.dropLast(count.coerceIn(0, current.length))
        pendingText = next
        return if (current.isDirectlyStreamableComposition()) {
            correction(from = emittedText, to = next).also { emittedText = next }
        } else {
            ByteArray(0)
        }
    }

    fun deleteCodePoints(count: Int): ByteArray {
        val current = pendingText ?: return ByteArray(0)
        val next = current.dropLastCodePoints(count)
        pendingText = next
        return if (current.isDirectlyStreamableComposition()) {
            correction(from = emittedText, to = next).also { emittedText = next }
        } else {
            ByteArray(0)
        }
    }

    fun cancel() {
        suppressedFinalization = pendingText
        pendingText = null
        emittedText = ""
    }

    private fun correction(from: String, to: String): ByteArray {
        val prefixLength = commonCodeUnitPrefix(from, to)
        val removedCodePoints = from.codePointCount(prefixLength, from.length)
        val deletion = ByteArray(removedCodePoints) { TerminalKeySequences.BACKSPACE.single() }
        val insertion = to.substring(prefixLength).toByteArray(StandardCharsets.UTF_8)
        return deletion + insertion
    }
}

private fun String.isDirectlyStreamableComposition(): Boolean {
    if (isEmpty()) return true
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val directlyStreamable = codePoint in 0x20..0x7e ||
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
        if (!directlyStreamable || Character.isISOControl(codePoint)) return false
        index += Character.charCount(codePoint)
    }
    return true
}

private fun commonCodeUnitPrefix(first: String, second: String): Int {
    var index = 0
    val limit = minOf(first.length, second.length)
    while (index < limit && first[index] == second[index]) index += 1
    if (index > 0 && index < first.length && Character.isLowSurrogate(first[index])) index -= 1
    return index
}

internal fun encodeTerminalDeletion(
    beforeLength: Int,
    afterLength: Int,
): ByteArray {
    val before = beforeLength.coerceIn(0, MAX_IME_DELETE_CODE_UNITS)
    val after = afterLength.coerceIn(0, MAX_IME_DELETE_CODE_UNITS)
    if (before == 0 && after == 0) return ByteArray(0)
    return ByteArray(before) { TerminalKeySequences.BACKSPACE.single() } +
        ByteArray(after * TerminalKeySequences.DELETE.size).also { result ->
            repeat(after) { index ->
                TerminalKeySequences.DELETE.copyInto(result, destinationOffset = index * TerminalKeySequences.DELETE.size)
            }
        }
}

private const val MAX_IME_DELETE_CODE_UNITS = 256

internal fun String.dropLastCodePoints(count: Int): String {
    if (count <= 0 || isEmpty()) return this
    val available = codePointCount(0, length)
    if (count >= available) return ""
    return substring(0, offsetByCodePoints(length, -count))
}
