package com.yanjiyu.terminalspike.ui

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

    internal fun onTextChanged(editable: Editable?) {
        updateContentMetadata(editable, contentChanged = true)
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
    masked: Boolean = true,
    enabled: Boolean = true,
    maxCharacters: Int = 4_096,
    imeAction: ImeAction = ImeAction.Done,
    supportingText: String? = null,
    onPresenceChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AndroidView(
            factory = {
                EditText(context).apply {
                    tag = testTag
                    hint = label
                    isEnabled = enabled
                    isSingleLine = true
                    inputType = secretInputType(masked)
                    imeOptions = imeAction.toEditorInfoAction() or
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    filters = arrayOf(InputFilter.LengthFilter(maxCharacters))
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
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
                if (editor.hint != label) editor.hint = label
                if (editor.isEnabled != enabled) editor.isEnabled = enabled
                val lengthFilter = editor.filters.singleOrNull() as? InputFilter.LengthFilter
                if (lengthFilter?.max != maxCharacters) {
                    editor.filters = arrayOf(InputFilter.LengthFilter(maxCharacters))
                }
                if (editor.currentTextColor != textColor) editor.setTextColor(textColor)
                if (editor.currentHintTextColor != hintColor) editor.setHintTextColor(hintColor)
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
                .heightIn(min = 52.dp)
                .testTag(testTag),
        )
        supportingText?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
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
