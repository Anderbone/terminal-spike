package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanjiyu.terminalspike.R
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val MAX_BUFFERED_INPUT_CHARACTERS = 4_096

private const val COMPOSE_PAGE = 0
private const val FIRST_SHORTCUT_PAGE = 1
private const val TARGET_SHORTCUT_COLUMNS = 9
private const val MIN_COMPACT_SHORTCUT_KEY_WIDTH_DP = 30f
private const val SHORTCUT_KEY_SPACING_DP = 2f
private const val SHORTCUT_HORIZONTAL_PADDING_DP = 3f

@Stable
class BufferedInputDraftState {
    var value by mutableStateOf(TextFieldValue())
        private set

    var validationMessage by mutableStateOf<UiText?>(null)
        private set

    private var targetSessionId: Long? = null

    fun update(nextValue: TextFieldValue, activeSessionId: Long) {
        if (nextValue.text.length > MAX_BUFFERED_INPUT_CHARACTERS) {
            validationMessage = uiText(
                R.string.terminal_buffer_limit_unchanged,
                MAX_BUFFERED_INPUT_CHARACTERS,
            )
            return
        }
        validationMessage = null
        if (value.text.isEmpty() || nextValue.text.isEmpty()) {
            targetSessionId = activeSessionId
        }
        value = nextValue
    }

    /** Refuses to move a non-empty draft to another terminal implicitly. */
    fun updateForTarget(nextValue: TextFieldValue, targetSessionId: Long): Boolean {
        if (value.text.isNotEmpty() &&
            this.targetSessionId != null &&
            this.targetSessionId != targetSessionId
        ) {
            return false
        }
        update(nextValue, targetSessionId)
        return value == nextValue
    }

    fun dispatch(
        activeSessionId: Long,
        sendEnabled: Boolean,
        onSend: (Long, String) -> Boolean,
    ) {
        if (!value.isBufferedSendEligible(sendEnabled, validationMessage)) return
        val accepted = onSend(targetSessionId ?: activeSessionId, value.text)
        value = value.afterBufferedSend(accepted)
        if (accepted) {
            validationMessage = null
            targetSessionId = activeSessionId
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
    customizationEnabled: Boolean,
    inputTargetId: Long,
    bufferedInputSendEnabled: Boolean,
    bufferedInputDraftState: BufferedInputDraftState,
    onKey: (TerminalExtraKey) -> Unit,
    onCustomize: () -> Unit,
    onSendBufferedInput: (Long, String) -> Boolean,
    onBufferedInputModeChanged: (Boolean) -> Unit,
    onDirectInputMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsed by remember(inputTargetId) { mutableStateOf(false) }
    TerminalAccessoryBar(
        actions = keys.map { it.toAccessoryAction() },
        modifiers = AccessoryModifierSnapshot(
            control = if (ctrlArmed) AccessoryModifierState.ARMED else AccessoryModifierState.OFF,
            alt = if (altArmed) AccessoryModifierState.ARMED else AccessoryModifierState.OFF,
        ),
        layout = layout,
        inputMode = inputMode,
        collapsed = collapsed,
        hapticFeedbackEnabled = hapticFeedbackEnabled,
        keyRepeatEnabled = keyRepeatEnabled,
        multilinePasteConfirmationEnabled = multilinePasteConfirmationEnabled,
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
        onBufferedInputModeChanged = onBufferedInputModeChanged,
        onDirectInputMode = onDirectInputMode,
        onCollapsedChange = { collapsed = it },
        modifier = modifier,
    )
}

/** Typed accessory surface used by live sessions. Collapse ownership remains outside Compose. */
@Composable
fun TerminalAccessoryBar(
    actions: List<TerminalAccessoryAction>,
    modifiers: AccessoryModifierSnapshot,
    layout: KeyboardLayout = KeyboardLayout.TWO_ROWS,
    inputMode: TerminalInputMode = TerminalInputMode.RAW,
    collapsed: Boolean,
    hapticFeedbackEnabled: Boolean = false,
    keyRepeatEnabled: Boolean = true,
    multilinePasteConfirmationEnabled: Boolean = true,
    customizationEnabled: Boolean,
    inputTargetId: Long,
    bufferedInputSendEnabled: Boolean,
    bufferedInputDraftState: BufferedInputDraftState,
    onAction: (TerminalAccessoryAction) -> Unit,
    onCustomize: () -> Unit,
    onSendBufferedInput: (Long, String) -> Boolean,
    onBufferedInputModeChanged: (Boolean) -> Unit,
    onDirectInputMode: () -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
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
            val columnCount = terminalShortcutColumnCount(maxWidth.value)
            val rowCount = layout.rowCount
            val shortcutPages = remember(actions, columnCount, rowCount, customizationEnabled) {
                terminalAccessoryPages(
                    actions = actions,
                    columnCount = columnCount,
                    rowCount = rowCount,
                    includeCustomizeAction = customizationEnabled &&
                        !actions.matchDefaultAccessoryDeck(),
                )
            }
            val pageCount = shortcutPages.size + FIRST_SHORTCUT_PAGE
            val pagerState = rememberPagerState(
                initialPage = if (inputMode == TerminalInputMode.TEXT) {
                    COMPOSE_PAGE
                } else {
                    FIRST_SHORTCUT_PAGE
                },
            ) { pageCount }
            val bufferedInputMode =
                pagerState.isScrollInProgress || pagerState.settledPage == COMPOSE_PAGE
            var bufferedInputModeWasActive by remember { mutableStateOf(false) }
            val pagerDescription = stringResource(R.string.terminal_input_pages_description)
            val pagerStateDescription = if (pagerState.currentPage == COMPOSE_PAGE) {
                stringResource(R.string.terminal_input_text_page)
            } else {
                stringResource(
                    R.string.terminal_input_raw_page,
                    pagerState.currentPage,
                    pageCount - 1,
                )
            }

            LaunchedEffect(inputMode) {
                val preferredPage = if (inputMode == TerminalInputMode.TEXT) {
                    COMPOSE_PAGE
                } else {
                    FIRST_SHORTCUT_PAGE
                }
                if (pagerState.currentPage != preferredPage) pagerState.scrollToPage(preferredPage)
            }
            LaunchedEffect(bufferedInputMode) {
                currentOnBufferedInputModeChanged(bufferedInputMode)
                if (!bufferedInputMode && bufferedInputModeWasActive) {
                    focusManager.clearFocus(force = true)
                    currentOnDirectInputMode()
                }
                bufferedInputModeWasActive = bufferedInputMode
            }
            DisposableEffect(Unit) {
                onDispose { currentOnBufferedInputModeChanged(false) }
            }

            // A focused Text composer stays mounted; this prevents an externally restored collapse
            // flag from unexpectedly tearing down its IME connection.
            if (collapsed && pagerState.currentPage != COMPOSE_PAGE) {
                DeckExpandHandle(
                    onExpand = { onCollapsedChange(false) },
                    modifier = Modifier.fillMaxWidth(),
                )
                return@BoxWithConstraints
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                DeckCollapseControl(
                    collapseEnabled = pagerState.currentPage != COMPOSE_PAGE,
                    onCollapse = { onCollapsedChange(true) },
                )
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (rowCount == 1) 54.dp else 106.dp)
                        .testTag("terminal_input_pager")
                        .semantics {
                            contentDescription = pagerDescription
                            stateDescription = pagerStateDescription
                        },
                ) { page ->
                    if (page == COMPOSE_PAGE) {
                        BufferedInputPage(
                            inputTargetId = inputTargetId,
                            sendEnabled = bufferedInputSendEnabled,
                            draftState = bufferedInputDraftState,
                            active = pagerState.settledPage == COMPOSE_PAGE,
                            multilineConfirmationEnabled = multilinePasteConfirmationEnabled,
                            onSend = onSendBufferedInput,
                        )
                    } else {
                        ExtraKeyPage(
                            cells = shortcutPages[page - FIRST_SHORTCUT_PAGE],
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

/** Uses the requested nine-key row at phone widths, reducing only in narrower split windows. */
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
 * when pages are read top-to-bottom, left-to-right. The shipped 18-key deck omits that extra live
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
        else -> null
    }
    is TerminalAccessoryAction.TmuxPrefix -> null
}

@Composable
private fun DeckCollapseControl(
    collapseEnabled: Boolean,
    onCollapse: () -> Unit,
) {
    val collapseDescription = stringResource(R.string.terminal_accessory_collapse)
    val collapseUnavailableDescription =
        stringResource(R.string.terminal_accessory_collapse_unavailable)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp)
            .accessoryKeyInput(
                enabled = collapseEnabled,
                repeatEnabled = false,
                onPressFeedback = {},
                onClick = onCollapse,
            )
            .testTag("terminal_accessory_collapse")
            .semantics {
                contentDescription = if (collapseEnabled) {
                    collapseDescription
                } else {
                    collapseUnavailableDescription
                }
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text("⌄") }
    }
}

@Composable
private fun DeckExpandHandle(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandDescription = stringResource(R.string.terminal_accessory_expand_description)
    Surface(
        modifier = modifier
            .height(48.dp)
            .accessoryKeyInput(
                enabled = true,
                repeatEnabled = false,
                onPressFeedback = {},
                onClick = onExpand,
            )
            .testTag("terminal_accessory_expand")
            .semantics { contentDescription = expandDescription },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.terminal_accessory_expand), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BufferedInputPage(
    inputTargetId: Long,
    sendEnabled: Boolean,
    draftState: BufferedInputDraftState,
    active: Boolean,
    multilineConfirmationEnabled: Boolean,
    onSend: (Long, String) -> Boolean,
) {
    val draft = draftState.value
    val validationMessage = draftState.validationMessage
    val inputDescription = stringResource(R.string.terminal_buffered_input_description)
    val focusRequester = remember { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    var confirmingMultilinePaste by remember { mutableStateOf(false) }

    fun sendDraft() {
        if (!draft.isBufferedSendEligible(sendEnabled, validationMessage)) return
        if (multilineConfirmationEnabled && draft.text.requiresMultilineConfirmation()) {
            confirmingMultilinePaste = true
        } else {
            draftState.dispatch(inputTargetId, sendEnabled, onSend)
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
            supportingText = validationMessage?.let { message ->
                { Text(message.resolve()) }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { sendDraft() }),
        )
        Button(
            onClick = { sendDraft() },
            enabled = draft.isBufferedSendEligible(sendEnabled, validationMessage),
            modifier = Modifier
                .widthIn(min = 72.dp)
                .heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.terminal_send))
        }
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
                        draftState.dispatch(inputTargetId, sendEnabled, onSend)
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
                    label = editLabel,
                    modifierState = null,
                    enabled = customizationEnabled,
                    description = customizeDescription,
                    disabledReason = null,
                    repeatEnabled = false,
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
                    label = cell.label.resolve(),
                    modifierState = modifierState,
                    enabled = disabledReason == null,
                    description = cell.accessibilityDescription.resolve(),
                    disabledReason = disabledReason,
                    repeatEnabled = keyRepeatEnabled && cell.supportsLongPressRepeat,
                    onPressFeedback = onPressFeedback,
                    onClick = { onAction(cell) },
                )
            }
        }
        repeat(columnCount - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun RowScope.ExtraKeyButton(
    label: String,
    modifierState: AccessoryModifierState?,
    enabled: Boolean,
    description: String,
    disabledReason: String?,
    repeatEnabled: Boolean,
    onPressFeedback: () -> Unit,
    onClick: () -> Unit,
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
        modifier = Modifier
            .weight(1f)
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
