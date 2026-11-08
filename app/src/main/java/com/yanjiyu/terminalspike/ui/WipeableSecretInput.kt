package com.yanjiyu.terminalspike.ui

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Owns secret characters only inside a mutable Android [Editable]. Compose observes non-secret
 * length/change metadata, never content. [takeChars] transfers a mutable copy and destroys the
 * editor contents immediately.
 */
@Stable
internal class WipeableSecretInputState {
    private var editor: EditText? = null
    private var onPresenceChanged: (Boolean) -> Unit = {}
    private val contentMetadata = mutableStateOf(SecretContentMetadata())

    val hasValue: Boolean
        get() = contentMetadata.value.characterCount > 0

    /** Non-secret UTF-16 character count used for bounded validation without materializing text. */
    val characterCount: Int
        get() = contentMetadata.value.characterCount

    internal fun bind(
        candidate: EditText,
        onPresenceChanged: (Boolean) -> Unit,
    ) {
        if (editor !== candidate) {
            editor?.text?.wipeSecretEditable()
            editor = candidate
        }
        this.onPresenceChanged = onPresenceChanged
        updateContentMetadata(candidate.text, contentChanged = false)
    }

    internal fun requestFocus() {
        val current = editor ?: return
        if (!current.isEnabled) return
        current.requestFocus()
        current.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.showSoftInput(current, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    internal fun onTextChanged(editable: Editable?) {
        updateContentMetadata(editable, contentChanged = true)
    }

    /** Caller owns and wipes this copy; the open editor retains its input for test/save retries. */
    fun copyChars(): CharArray {
        val editable = editor?.text ?: return CharArray(0)
        return CharArray(editable.length) { index -> editable[index] }
    }

    fun takeChars(): CharArray {
        val editable = editor?.text
        if (editable == null || editable.isEmpty()) return CharArray(0)
        val result = CharArray(editable.length) { index -> editable[index] }
        wipe()
        return result
    }

    fun contentEquals(other: WipeableSecretInputState): Boolean {
        // Reading both non-secret revisions makes same-length edits observable to Compose callers.
        val leftMetadata = contentMetadata.value
        val rightMetadata = other.contentMetadata.value
        val left = editor?.text
        val right = other.editor?.text
        val leftLength = leftMetadata.characterCount
        val rightLength = rightMetadata.characterCount
        if (leftLength != rightLength) return false
        var equal = true
        for (index in 0 until leftLength) {
            equal = equal and (left?.get(index) == right?.get(index))
        }
        return equal
    }

    fun wipe() {
        editor?.text?.wipeSecretEditable()
        updateContentMetadata(null, contentChanged = true)
    }

    internal fun dispose() {
        editor?.text?.wipeSecretEditable()
        editor = null
        updateContentMetadata(null, contentChanged = true)
    }

    private fun updateContentMetadata(editable: Editable?, contentChanged: Boolean) {
        val previous = contentMetadata.value
        val nextCount = editable?.length ?: 0
        val nextRevision = if (contentChanged) {
            if (previous.revision == Long.MAX_VALUE) 0L else previous.revision + 1L
        } else {
            previous.revision
        }
        val next = SecretContentMetadata(nextCount, nextRevision)
        if (next != previous) contentMetadata.value = next
        val wasPresent = previous.characterCount > 0
        val isPresent = nextCount > 0
        if (wasPresent != isPresent) onPresenceChanged(isPresent)
    }

    private data class SecretContentMetadata(
        val characterCount: Int = 0,
        val revision: Long = 0L,
    )
}

@Composable
internal fun WipeableSecretInput(
    state: WipeableSecretInputState,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    masked: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    maxCharacters: Int = 4_096,
    imeAction: ImeAction = ImeAction.Done,
    supportingText: String? = null,
    onPresenceChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f).toArgb()
    val interactionSource = remember { MutableInteractionSource() }
    val input: @Composable () -> Unit = {
        AndroidView(
            factory = {
                EditText(context).apply {
                    tag = testTag
                    contentDescription = label
                    background = null
                    setPadding(0, 0, 0, 0)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 16f)
                    minimumHeight = 0
                    var focusInteraction: FocusInteraction.Focus? = null
                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            FocusInteraction.Focus().also {
                                focusInteraction = it
                                interactionSource.tryEmit(it)
                            }
                        } else {
                            focusInteraction?.let { interactionSource.tryEmit(FocusInteraction.Unfocus(it)) }
                            focusInteraction = null
                        }
                    }
                    isEnabled = enabled
                    isSingleLine = true
                    inputType = secretInputType(masked)
                    imeOptions = imeAction.toEditorInfoAction() or
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    filters = arrayOf(InputFilter.LengthFilter(maxCharacters))
                    setTextColor(textColor)
                    addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(
                                text: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int,
                            ) = Unit

                            override fun onTextChanged(
                                text: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int,
                            ) = Unit

                            override fun afterTextChanged(editable: Editable?) {
                                state.onTextChanged(editable)
                            }
                        },
                    )
                    state.bind(this, onPresenceChanged)
                }
            },
            update = { editor ->
                if (editor.tag != testTag) editor.tag = testTag
                if (editor.contentDescription != label) editor.contentDescription = label
                if (editor.isEnabled != enabled) editor.isEnabled = enabled
                val lengthFilter = editor.filters.singleOrNull() as? InputFilter.LengthFilter
                if (lengthFilter?.max != maxCharacters) {
                    editor.filters = arrayOf(InputFilter.LengthFilter(maxCharacters))
                }
                if (editor.currentTextColor != textColor) editor.setTextColor(textColor)
                val nextInputType = secretInputType(masked)
                if (editor.inputType != nextInputType) {
                    editor.inputType = nextInputType
                    editor.setSelection(editor.text?.length ?: 0)
                }
                val nextImeOptions = imeAction.toEditorInfoAction() or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                if (editor.imeOptions != nextImeOptions) editor.imeOptions = nextImeOptions
                if (editor.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS) {
                    editor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
                state.bind(editor, onPresenceChanged)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 18.dp else 24.dp)
                .testTag(testTag),
        )
    }
    if (compact) {
        androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
            val colors = MaterialTheme.colorScheme
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(colors.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    .border(1.dp, if (isError) colors.error else colors.outlineVariant.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .pointerInput(state, enabled) { detectTapGestures { if (enabled) state.requestFocus() } },
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp), color = colors.onSurfaceVariant)
                input()
            }
            supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
    } else {
        Box(modifier = modifier.fillMaxWidth().padding(top = 8.dp)
            .pointerInput(state, enabled) { detectTapGestures { if (enabled) state.requestFocus() } }) {
            OutlinedTextFieldDefaults.DecorationBox(
                // Decoration observes presence only; content stays in the native Editable.
                value = if (state.hasValue) "•" else "",
                enabled = enabled,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = isError,
                label = { Text(label) },
                supportingText = supportingText?.let { message -> ({ Text(message) }) },
                innerTextField = input,
            )
        }
    }

    DisposableEffect(state) {
        onDispose {
            state.dispose()
        }
    }
}

private fun secretInputType(masked: Boolean): Int =
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or if (masked) {
        InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else {
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

private fun ImeAction.toEditorInfoAction(): Int = when (this) {
    ImeAction.None -> EditorInfo.IME_ACTION_NONE
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    ImeAction.Send -> EditorInfo.IME_ACTION_SEND
    ImeAction.Previous -> EditorInfo.IME_ACTION_PREVIOUS
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Done -> EditorInfo.IME_ACTION_DONE
    else -> EditorInfo.IME_ACTION_UNSPECIFIED
}

private fun Editable.wipeSecretEditable() {
    for (index in indices) replace(index, index + 1, "\u0000")
    clear()
}
