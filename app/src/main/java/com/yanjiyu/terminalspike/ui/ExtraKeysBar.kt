package com.yanjiyu.terminalspike.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierState
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryDispatch
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.resolve
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val MAX_BUFFERED_INPUT_CHARACTERS = 4_096

private const val TARGET_SHORTCUT_COLUMNS = 10
private const val MIN_COMPACT_SHORTCUT_KEY_WIDTH_DP = 29f
private const val SHORTCUT_KEY_SPACING_DP = 2f
private const val SHORTCUT_HORIZONTAL_PADDING_DP = 3f

@Stable
class BufferedInputDraftState {
    var value by mutableStateOf(TextFieldValue())
        private set

    private var lastSentValue by mutableStateOf<TextFieldValue?>(null)

    val canRestoreLastSent: Boolean
        get() = lastSentValue != null

    var validationMessage by mutableStateOf<UiText?>(null)
        private set

    fun update(nextValue: TextFieldValue, activeSessionId: Long) {
        if (nextValue.text.length > MAX_BUFFERED_INPUT_CHARACTERS) {
            validationMessage = uiText(
                R.string.terminal_buffer_limit_unchanged,
                MAX_BUFFERED_INPUT_CHARACTERS,
            )
            return
        }
        validationMessage = null
        lastSentValue = null
        value = nextValue
    }

    fun restoreLastSent() {
        val restored = lastSentValue ?: return
        value = restored.copy(selection = TextRange(restored.text.length))
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
            lastSentValue = sentValue
            validationMessage = null
        }
    }
}

@Composable
fun ExtraKeysBar(
    keys: List<TerminalExtraKey>,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    layout: KeyboardLayout = KeyboardLayout.TWO_ROWS,
    inputMode: TerminalInputMode = TerminalInputMode.RAW,
    hapticFeedbackEnabled: Boolean = false,
    keyRepeatEnabled: Boolean = true,
    multilinePasteConfirmationEnabled: Boolean = true,
    voiceInputLanguageTag: String = "",
    customizationEnabled: Boolean,
    inputTargetId: Long,
    bufferedInputSendEnabled: Boolean,
    bufferedInputDraftState: BufferedInputDraftState,
    onKey: (TerminalExtraKey) -> Unit,
    onCustomize: () -> Unit,
    onSendBufferedInput: (Long, String) -> Boolean,
    onSubmitBufferedInput: (Long, String) -> Boolean = onSendBufferedInput,
    onBufferedInputModeChanged: (Boolean) -> Unit,
    onDirectInputMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TerminalAccessoryBar(
        actions = keys.map { it.toAccessoryAction() },
        modifiers = AccessoryModifierSnapshot(
            control = if (ctrlArmed) AccessoryModifierState.ARMED else AccessoryModifierState.OFF,
            alt = if (altArmed) AccessoryModifierState.ARMED else AccessoryModifierState.OFF,
        ),
        layout = layout,
        inputMode = inputMode,
        hapticFeedbackEnabled = hapticFeedbackEnabled,
        keyRepeatEnabled = keyRepeatEnabled,
        multilinePasteConfirmationEnabled = multilinePasteConfirmationEnabled,
        voiceInputLanguageTag = voiceInputLanguageTag,
        customizationEnabled = customizationEnabled,
        inputTargetId = inputTargetId,
        bufferedInputSendEnabled = bufferedInputSendEnabled,
        bufferedInputDraftState = bufferedInputDraftState,
        onAction = { action ->
            when (action) {
                is TerminalAccessoryAction.Key -> onKey(action.key)
                is TerminalAccessoryAction.Modifier -> when (action.modifier) {
                    TerminalAccessoryModifier.CONTROL -> onKey(TerminalExtraKey.CTRL)
                    TerminalAccessoryModifier.ALT -> onKey(TerminalExtraKey.ALT)
                    TerminalAccessoryModifier.SHIFT -> Unit
                }
                is TerminalAccessoryAction.Local -> if (
                    action.action == TerminalLocalAccessoryAction.HIDE_KEYBOARD
                ) {
                    onKey(TerminalExtraKey.HIDE_KEYBOARD)
                }
                is TerminalAccessoryAction.TmuxPrefix -> Unit
            }
        },
        onCustomize = onCustomize,
        onSendBufferedInput = onSendBufferedInput,
        onSubmitBufferedInput = onSubmitBufferedInput,
        onBufferedInputModeChanged = onBufferedInputModeChanged,
        onDirectInputMode = onDirectInputMode,
        modifier = modifier,
    )
}

/** Typed accessory surface used by live sessions. */
@Composable
fun TerminalAccessoryBar(
    actions: List<TerminalAccessoryAction>,
    modifiers: AccessoryModifierSnapshot,
    layout: KeyboardLayout = KeyboardLayout.TWO_ROWS,
    inputMode: TerminalInputMode = TerminalInputMode.RAW,
    hapticFeedbackEnabled: Boolean = false,
    keyRepeatEnabled: Boolean = true,
    multilinePasteConfirmationEnabled: Boolean = true,
    voiceInputLanguageTag: String = "",
    customizationEnabled: Boolean,
    inputTargetId: Long,
    bufferedInputSendEnabled: Boolean,
    bufferedInputDraftState: BufferedInputDraftState,
    onAction: (TerminalAccessoryAction) -> Unit,
    onCustomize: () -> Unit,
    onSendBufferedInput: (Long, String) -> Boolean,
    onSubmitBufferedInput: (Long, String) -> Boolean = onSendBufferedInput,
    onBufferedInputModeChanged: (Boolean) -> Unit,
    onDirectInputMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val currentOnBufferedInputModeChanged by rememberUpdatedState(onBufferedInputModeChanged)
    val currentOnDirectInputMode by rememberUpdatedState(onDirectInputMode)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val escapeDescription = TerminalExtraKey.ESC.accessibilityDescription.resolve()
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            var bufferedInputMode by remember { mutableStateOf(true) }
            var bufferedInputModeWasActive by remember { mutableStateOf(false) }
            LaunchedEffect(keyboardVisible) {
                if (keyboardVisible) bufferedInputMode = true
            }
            val columnCount = (terminalShortcutColumnCount(maxWidth.value) - 1).coerceAtLeast(1)
            val rowCount = layout.rowCount
            val shortcutActions = actions.filterNot {
                it.defaultDeckKeyOrNull() == TerminalExtraKey.ESC ||
                    it.defaultDeckKeyOrNull() == TerminalExtraKey.TAB
            }
            val shortcutPages = terminalAccessoryPages(
                shortcutActions, columnCount, rowCount,
                customizationEnabled && !actions.matchDefaultAccessoryDeck(),
            )
            val pagerState = rememberPagerState { shortcutPages.size }
            LaunchedEffect(bufferedInputMode, keyboardVisible) {
                currentOnBufferedInputModeChanged(bufferedInputMode && keyboardVisible)
                if (!bufferedInputMode && bufferedInputModeWasActive) {
                    focusManager.clearFocus(force = true)
                    currentOnDirectInputMode()
                }
                bufferedInputModeWasActive = bufferedInputMode
            }
            DisposableEffect(Unit) {
                onDispose { currentOnBufferedInputModeChanged(false) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(106.dp),
            ) {
                if (bufferedInputMode) {
                    BufferedInputPage(
                        inputTargetId = inputTargetId,
                        sendEnabled = bufferedInputSendEnabled,
                        draftState = bufferedInputDraftState,
                        active = keyboardVisible,
                        multilineConfirmationEnabled = multilinePasteConfirmationEnabled,
                        voiceInputLanguageTag = voiceInputLanguageTag,
                        onSend = onSendBufferedInput,
                        onSubmit = onSubmitBufferedInput,
                        onEnter = { onAction(TerminalExtraKey.ENTER.toAccessoryAction()) },
                        onToggleTyping = { bufferedInputMode = false },
                    )
                } else {
                    Column(Modifier.width(52.dp).fillMaxHeight().padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ExtraKeyButton(
                            label = stringResource(R.string.terminal_escape_short),
                            description = escapeDescription,
                            onClick = { onAction(TerminalExtraKey.ESC.toAccessoryAction()) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        TypingToggle(active = false, onClick = { bufferedInputMode = true },
                            modifier = Modifier.weight(1f))
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("terminal_input_pager"),
                    ) { page ->
                        ExtraKeyPage(
                            cells = shortcutPages[page],
                            columnCount = columnCount,
                            rowCount = rowCount,
                            modifiers = modifiers,
                            customizationEnabled = customizationEnabled,
                            keyRepeatEnabled = keyRepeatEnabled,
                            onPressFeedback = {
                                if (hapticFeedbackEnabled) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onAction = onAction,
                            onCustomize = onCustomize,
                        )
                    }
                }
            }
        }
    }
}

/** Uses the requested ten-key row at phone widths, reducing only in narrower split windows. */
internal fun terminalShortcutColumnCount(availableWidthDp: Float): Int {
    require(availableWidthDp.isFinite() && availableWidthDp > 0f) {
        "Shortcut deck width must be finite and positive."
    }
    val widthForKeys = availableWidthDp - (SHORTCUT_HORIZONTAL_PADDING_DP * 2) +
        SHORTCUT_KEY_SPACING_DP
    return (widthForKeys / (MIN_COMPACT_SHORTCUT_KEY_WIDTH_DP + SHORTCUT_KEY_SPACING_DP))
        .toInt()
        .coerceIn(1, TARGET_SHORTCUT_COLUMNS)
}

/**
 * When requested, null is the one customize cell. All configured keys remain in their exact order
 * when pages are read top-to-bottom, left-to-right. The shipped 20-key deck omits that extra live
 * cell because its editor already lives in Settings > Keyboard.
 */
internal fun terminalShortcutPages(
    keys: List<TerminalExtraKey>,
    columnCount: Int,
    rowCount: Int = 2,
    includeCustomizeAction: Boolean = true,
): List<List<TerminalExtraKey?>> {
    return terminalPages(keys, columnCount, rowCount, includeCustomizeAction)
}

private fun terminalAccessoryPages(
    actions: List<TerminalAccessoryAction>,
    columnCount: Int,
    rowCount: Int,
    includeCustomizeAction: Boolean,
): List<List<TerminalAccessoryAction?>> =
    terminalPages(actions, columnCount, rowCount, includeCustomizeAction)

private fun <T> terminalPages(
    values: List<T>,
    columnCount: Int,
    rowCount: Int,
    includeCustomizeAction: Boolean,
): List<List<T?>> {
    require(columnCount > 0) { "Shortcut deck must have at least one column." }
    require(rowCount in 1..2) { "Shortcut deck supports one or two rows." }
    val cells: List<T?> = if (includeCustomizeAction) values + listOf(null) else values
    return cells.chunked(columnCount * rowCount).ifEmpty { listOf(emptyList()) }
}

private fun List<TerminalAccessoryAction>.matchDefaultAccessoryDeck(): Boolean =
    map(TerminalAccessoryAction::stableId) == KeyboardAction.DEFAULT_ORDER.map(KeyboardAction::wireCode) ||
        map { it.defaultDeckKeyOrNull() } == TerminalExtraKey.DEFAULT_ORDER

private fun TerminalAccessoryAction.defaultDeckKeyOrNull(): TerminalExtraKey? = when (this) {
    is TerminalAccessoryAction.Key -> key
    is TerminalAccessoryAction.Modifier -> when (modifier) {
        TerminalAccessoryModifier.CONTROL -> TerminalExtraKey.CTRL
        TerminalAccessoryModifier.ALT -> TerminalExtraKey.ALT
        TerminalAccessoryModifier.SHIFT -> null
    }
    is TerminalAccessoryAction.Local -> when (action) {
        TerminalLocalAccessoryAction.HIDE_KEYBOARD -> TerminalExtraKey.HIDE_KEYBOARD
        TerminalLocalAccessoryAction.TMUX_SESSIONS -> null
        TerminalLocalAccessoryAction.SELECT_IMAGES -> null
        else -> null
    }
    is TerminalAccessoryAction.TmuxPrefix ->
        if (chord.equals("C-b", ignoreCase = true)) TerminalExtraKey.CTRL_B else null
}

@Composable
private fun TypingToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtraKeyButton(
        label = stringResource(R.string.terminal_typing_toggle),
        description = stringResource(R.string.terminal_typing_toggle),
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("terminal_typing_toggle").semantics {
            selected = active
        },
    )
}

@Composable
private fun BufferedInputPage(
    inputTargetId: Long,
    sendEnabled: Boolean,
    draftState: BufferedInputDraftState,
    active: Boolean,
    multilineConfirmationEnabled: Boolean,
    voiceInputLanguageTag: String,
    onSend: (Long, String) -> Boolean,
    onSubmit: (Long, String) -> Boolean,
    onEnter: () -> Unit,
    onToggleTyping: () -> Unit,
) {
    val draft = draftState.value
    val validationMessage = draftState.validationMessage
    val canRestoreLastSent = draftState.canRestoreLastSent
    val enterDescription = stringResource(R.string.terminal_buffered_input_enter)
    val inputDescription = stringResource(R.string.terminal_buffered_input_description)
    val restoreInputDescription = stringResource(R.string.terminal_restore_last_sent_input)
    val focusRequester = remember { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val currentInputTargetId by rememberUpdatedState(inputTargetId)
    val currentVoiceLanguageTag by rememberUpdatedState(voiceInputLanguageTag)
    var confirmingMultilinePaste by remember { mutableStateOf(false) }
    var pendingSubmit by remember { mutableStateOf(false) }
    var voicePhase by remember { mutableStateOf(VoiceInputPhase.IDLE) }
    var partialTranscript by remember { mutableStateOf("") }
    var voiceFailure by remember { mutableStateOf<VoiceInputFailure?>(null) }
    val voiceRecognizer = remember(context) {
        AndroidVoiceInputRecognizer(
            context = context,
            onPhase = { voicePhase = it },
            onPartial = { partialTranscript = it },
            onResult = { transcript ->
                voiceFailure = null
                draftState.updateForTarget(
                    draftState.value.withVoiceTranscript(transcript),
                    currentInputTargetId,
                )
            },
            onFailure = { voiceFailure = it },
        )
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceFailure = null
            voiceRecognizer.start(currentVoiceLanguageTag)
        } else {
            voiceFailure = VoiceInputFailure.PERMISSION_DENIED
        }
    }

    DisposableEffect(voiceRecognizer) {
        onDispose { voiceRecognizer.destroy() }
    }

    LaunchedEffect(inputTargetId) {
        if (voicePhase != VoiceInputPhase.IDLE) voiceRecognizer.cancel()
    }

    fun sendDraft(submit: Boolean = false) {
        if (submit && sendEnabled && draft.text.isEmpty() && validationMessage == null) {
            onEnter()
            return
        }
        if (!draft.isBufferedSendEligible(sendEnabled, validationMessage)) return
        if (multilineConfirmationEnabled && draft.text.requiresMultilineConfirmation()) {
            pendingSubmit = submit
            confirmingMultilinePaste = true
        } else {
            draftState.dispatch(inputTargetId, sendEnabled, if (submit) onSubmit else onSend)
        }
    }

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
            softwareKeyboardController?.show()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(44.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ExtraKeyButton(
                label = stringResource(R.string.terminal_enter_short),
                description = enterDescription,
                onClick = { sendDraft(submit = true) },
                enabled = sendEnabled && validationMessage == null && draft.composition == null,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("buffered_input_enter"),
            )
            TypingToggle(active = true, onClick = onToggleTyping, modifier = Modifier.weight(1f))
        }
        val voiceStatus = voiceInputStatus(voicePhase, partialTranscript, voiceFailure)
        OutlinedTextField(
            value = draft,
            onValueChange = { nextValue -> draftState.update(nextValue, inputTargetId) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .testTag("buffered_terminal_input")
                .semantics { contentDescription = inputDescription },
            placeholder = { Text(stringResource(R.string.terminal_buffered_input_placeholder)) },
            minLines = 1,
            maxLines = 3,
            isError = validationMessage != null,
            supportingText = (validationMessage?.resolve() ?: voiceStatus)?.let { message ->
                { Text(message, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            trailingIcon = {
                val voiceActive = voicePhase != VoiceInputPhase.IDLE
                if (canRestoreLastSent) {
                    IconButton(
                        onClick = draftState::restoreLastSent,
                        modifier = Modifier
                            .size(44.dp)
                            .semantics { contentDescription = restoreInputDescription },
                    ) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.RESTORE,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    val voiceState = stringResource(
                        if (voiceActive) {
                            R.string.terminal_voice_state_on
                        } else {
                            R.string.terminal_voice_state_off
                        },
                    )
                    val voiceDescription = stringResource(R.string.terminal_voice_input)
                    Surface(
                        shape = CircleShape,
                        color = if (voiceActive) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (voiceActive) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.semantics {
                            contentDescription = voiceDescription
                            stateDescription = voiceState
                        },
                    ) {
                        IconButton(
                            onClick = {
                                if (voiceActive) {
                                    voiceRecognizer.stop()
                                } else if (!voiceRecognizer.available) {
                                    voiceFailure = VoiceInputFailure.UNAVAILABLE
                                } else if (
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    voiceFailure = null
                                    voiceRecognizer.start(currentVoiceLanguageTag)
                                } else {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            ConnectionsGlyphIcon(
                                glyph = if (voiceActive) ConnectionsGlyph.STOP else ConnectionsGlyph.MIC,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { sendDraft() }),
        )

    }

    if (confirmingMultilinePaste) {
        AlertDialog(
            onDismissRequest = { confirmingMultilinePaste = false },
            title = { Text(stringResource(R.string.terminal_multiline_paste_title)) },
            text = {
                Text(stringResource(R.string.terminal_multiline_paste_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingMultilinePaste = false
                        draftState.dispatch(
                            inputTargetId, sendEnabled, if (pendingSubmit) onSubmit else onSend,
                        )
                    },
                ) {
                    Text(stringResource(R.string.terminal_paste))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingMultilinePaste = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun voiceInputStatus(
    phase: VoiceInputPhase,
    partialTranscript: String,
    failure: VoiceInputFailure?,
): String? = when (phase) {
    VoiceInputPhase.LISTENING -> partialTranscript.takeIf(String::isNotBlank)?.let { transcript ->
        stringResource(R.string.terminal_voice_listening_with_words, transcript)
    } ?: stringResource(R.string.terminal_voice_listening)
    VoiceInputPhase.PROCESSING -> stringResource(R.string.terminal_voice_processing)
    VoiceInputPhase.IDLE -> failure?.let { value ->
        stringResource(
            when (value) {
                VoiceInputFailure.UNAVAILABLE -> R.string.terminal_voice_unavailable
                VoiceInputFailure.PERMISSION_DENIED -> R.string.terminal_voice_permission_denied
                VoiceInputFailure.NO_MATCH -> R.string.terminal_voice_no_match
                VoiceInputFailure.BUSY -> R.string.terminal_voice_busy
                VoiceInputFailure.OFFLINE_UNAVAILABLE -> R.string.terminal_voice_offline_unavailable
                VoiceInputFailure.UNKNOWN -> R.string.terminal_voice_failed
            },
        )
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

@Composable
private fun ExtraKeyPage(
    cells: List<TerminalAccessoryAction?>,
    columnCount: Int,
    rowCount: Int,
    modifiers: AccessoryModifierSnapshot,
    customizationEnabled: Boolean,
    keyRepeatEnabled: Boolean,
    onPressFeedback: () -> Unit,
    onAction: (TerminalAccessoryAction) -> Unit,
    onCustomize: () -> Unit,
) {
    val rows = cells.chunked(columnCount)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(rowCount) { rowIndex ->
            ExtraKeyRow(
                cells = rows.getOrElse(rowIndex) { emptyList() },
                columnCount = columnCount,
                modifiers = modifiers,
                customizationEnabled = customizationEnabled,
                keyRepeatEnabled = keyRepeatEnabled,
                onPressFeedback = onPressFeedback,
                onAction = onAction,
                onCustomize = onCustomize,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExtraKeyRow(
    cells: List<TerminalAccessoryAction?>,
    columnCount: Int,
    modifiers: AccessoryModifierSnapshot,
    customizationEnabled: Boolean,
    keyRepeatEnabled: Boolean,
    onPressFeedback: () -> Unit,
    onAction: (TerminalAccessoryAction) -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editLabel = stringResource(R.string.terminal_edit_keys_short)
    val customizeDescription = stringResource(R.string.terminal_customize_keys)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEach { cell ->
            if (cell == null) {
                ExtraKeyButton(
                    modifier = Modifier.weight(1f),
                    label = editLabel,
                    modifierState = null,
                    enabled = customizationEnabled,
                    description = customizeDescription,
                    disabledReason = null,
                    repeatEnabled = false,
                    glyph = null,
                    onPressFeedback = onPressFeedback,
                    onClick = onCustomize,
                )
            } else {
                val dispatch = cell.resolve(modifiers)
                val modifierState = (cell as? TerminalAccessoryAction.Modifier)
                    ?.let { modifiers.stateOf(it.modifier) }
                val disabledReason = (dispatch as? TerminalAccessoryDispatch.Unsupported)
                    ?.explanation
                    ?.resolve()
                ExtraKeyButton(
                    modifier = Modifier.weight(1f),
                    label = cell.label.resolve(),
                    modifierState = modifierState,
                    enabled = disabledReason == null,
                    description = cell.accessibilityDescription.resolve(),
                    disabledReason = disabledReason,
                    repeatEnabled = keyRepeatEnabled && cell.supportsLongPressRepeat,
                    glyph = when ((cell as? TerminalAccessoryAction.Local)?.action) {
                        TerminalLocalAccessoryAction.TMUX_SESSIONS -> ConnectionsGlyph.WINDOWS
                        TerminalLocalAccessoryAction.SELECT_IMAGES -> ConnectionsGlyph.IMAGE
                        TerminalLocalAccessoryAction.HIDE_KEYBOARD -> ConnectionsGlyph.KEYBOARD
                        else -> null
                    },
                    onPressFeedback = onPressFeedback,
                    onClick = { onAction(cell) },
                )
            }
        }
        repeat(columnCount - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    modifierState: AccessoryModifierState? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    repeatEnabled: Boolean = false,
    glyph: ConnectionsGlyph? = null,
    onPressFeedback: () -> Unit = {},
) {
    val baseFontSizeSp = terminalShortcutLabelFontSizeSp(label.length)
    val modifierStateDescription = modifierState?.let { state ->
        stringResource(
            when (state) {
                AccessoryModifierState.OFF -> R.string.terminal_modifier_off
                AccessoryModifierState.ARMED -> R.string.terminal_modifier_armed
                AccessoryModifierState.LOCKED -> R.string.terminal_modifier_locked
            },
            description,
        )
    }
    val containerColor = when (modifierState) {
        AccessoryModifierState.ARMED -> MaterialTheme.colorScheme.primaryContainer
        AccessoryModifierState.LOCKED -> MaterialTheme.colorScheme.tertiaryContainer
        AccessoryModifierState.OFF, null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (modifierState) {
        AccessoryModifierState.ARMED -> MaterialTheme.colorScheme.onPrimaryContainer
        AccessoryModifierState.LOCKED -> MaterialTheme.colorScheme.onTertiaryContainer
        AccessoryModifierState.OFF, null -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .accessoryKeyInput(
                enabled = enabled,
                repeatEnabled = repeatEnabled,
                onPressFeedback = onPressFeedback,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                if (modifierState != null) {
                    selected = modifierState.isActive
                    stateDescription = checkNotNull(modifierStateDescription)
                } else if (disabledReason != null) {
                    stateDescription = disabledReason
                }
            },
        shape = RoundedCornerShape(7.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 1.dp), contentAlignment = Alignment.Center) {
            if (glyph != null) {
                ConnectionsGlyphIcon(
                    glyph = glyph,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = baseFontSizeSp.sp,
                        lineHeight = (baseFontSizeSp + 2f).sp,
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            if (modifierState == AccessoryModifierState.LOCKED) {
                Text(
                    text = "🔒",
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 1.dp, top = 1.dp),
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                )
            }
        }
    }
}

@Composable
private fun Modifier.accessoryKeyInput(
    enabled: Boolean,
    repeatEnabled: Boolean,
    onPressFeedback: () -> Unit,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val currentOnPressFeedback by rememberUpdatedState(onPressFeedback)
    val currentOnClick by rememberUpdatedState(onClick)
    return this
        .indication(interactionSource, indication)
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
            onClick {
                if (!enabled) return@onClick false
                currentOnPressFeedback()
                currentOnClick()
                true
            }
        }
        .pointerInput(enabled, repeatEnabled) {
            if (!enabled) return@pointerInput
            kotlinx.coroutines.coroutineScope {
                val repeatScope = this
                this@pointerInput.awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    currentOnPressFeedback()
                    var repeated = false
                    val repeatJob = if (repeatEnabled) {
                        repeatScope.launch {
                            delay(LONG_PRESS_REPEAT_INITIAL_DELAY_MS)
                            while (isActive) {
                                repeated = true
                                currentOnClick()
                                delay(LONG_PRESS_REPEAT_INTERVAL_MS)
                            }
                        }
                    } else {
                        null
                    }
                    val released = waitForUpOrCancellation() != null
                    repeatJob?.cancel()
                    interactionSource.tryEmit(
                        if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                    )
                    if (released && !repeated) currentOnClick()
                }
            }
        }
}

internal fun terminalShortcutLabelFontSizeSp(labelLength: Int): Float {
    require(labelLength > 0) { "Shortcut labels must not be empty." }
    return when {
        labelLength >= 7 -> 6f
        labelLength >= 5 -> 7f
        labelLength == 4 -> 8f
        labelLength == 3 -> 9f
        else -> 12f
    }
}

private const val LONG_PRESS_REPEAT_INITIAL_DELAY_MS = 400L
private const val LONG_PRESS_REPEAT_INTERVAL_MS = 70L
