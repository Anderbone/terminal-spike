package com.yanjiyu.terminalspike.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

@Stable
class BufferedInputDraftState(
    initialText: String = "",
    private val persistText: (String) -> Unit = {},
) {
    var value by mutableStateOf(
        TextFieldValue(initialText, selection = TextRange(initialText.length)),
    )
        private set

    private var lastSentValue by mutableStateOf<TextFieldValue?>(null)

    val canRestoreLastSent: Boolean
        get() = lastSentValue != null

    var validationMessage by mutableStateOf<UiText?>(null)
        private set

    fun update(nextValue: TextFieldValue, activeSessionId: Long) {
        validationMessage = null
        lastSentValue = null
        val textChanged = value.text != nextValue.text
        value = nextValue
        if (textChanged) persistText(value.text)
    }

    internal fun finishComposition() {
        value = value.copy(composition = null)
    }

    fun restoreLastSent() {
        val restored = lastSentValue ?: return
        value = restored.copy(selection = TextRange(restored.text.length))
        persistText(value.text)
        lastSentValue = null
        validationMessage = null
    }

    /** Applies caller-targeted edits without pinning the shared draft to one terminal. */
    fun updateForTarget(nextValue: TextFieldValue, targetSessionId: Long): Boolean {
        update(nextValue, targetSessionId)
        return value == nextValue
    }

    fun dispatch(
        activeSessionId: Long,
        sendEnabled: Boolean,
        onSend: (Long, String) -> Boolean,
    ) {
        if (!value.isBufferedSendEligible(sendEnabled, validationMessage)) return
        val sentValue = value
        val accepted = onSend(activeSessionId, value.text)
        value = value.afterBufferedSend(accepted)
        if (accepted) {
            persistText(value.text)
            lastSentValue = sentValue
            validationMessage = null
        }
    }
}

/** A composing IME value is not committed user input and must never cross the transport boundary. */
internal fun TextFieldValue.isBufferedSendEligible(
    sendEnabled: Boolean,
    validationMessage: UiText?,
): Boolean = sendEnabled && text.isNotEmpty() && composition == null && validationMessage == null

internal fun String.requiresMultilineConfirmation(): Boolean = any { it == '\r' || it == '\n' }

internal fun TextFieldValue.afterBufferedSend(accepted: Boolean): TextFieldValue =
    if (accepted) TextFieldValue() else this
