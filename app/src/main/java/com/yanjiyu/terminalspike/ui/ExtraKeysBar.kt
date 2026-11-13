package com.yanjiyu.terminalspike.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.yanjiyu.terminalspike.ui.theme.AppDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierState
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryDispatch
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.TerminalImageContentCallback
import com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.resolve
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val LocalButtonColors = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

private const val TARGET_SHORTCUT_COLUMNS = 10
private const val MIN_COMPACT_SHORTCUT_KEY_WIDTH_DP = 29f
private const val SHORTCUT_KEY_SPACING_DP = 2f
private const val SHORTCUT_HORIZONTAL_PADDING_DP = 3f
private val defaultSnippetToggle = TerminalAccessoryAction.Local(
    TerminalLocalAccessoryAction.SNIPPETS,
    stableId = "default:toggle_pinned_snippets",
)

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
    voiceInputEnabled: Boolean = false,
    inputKeyboardLayout: String = "",
    inputButtonsOnRight: Boolean = false,
    onInputButtonsOnRight: (Boolean) -> Unit = {},
    voiceInputLanguageTag: String = "",
    customizationEnabled: Boolean,
    inputTargetId: Long,
    bufferedInputSendEnabled: Boolean,
    bufferedInputDraftState: BufferedInputDraftState,
    inputContext: TerminalInputContext = TerminalInputContext(),
    showCodexInputStatus: Boolean = false,
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
        voiceInputEnabled = voiceInputEnabled,
        inputKeyboardLayout = inputKeyboardLayout,
        inputButtonsOnRight = inputButtonsOnRight,
        onInputButtonsOnRight = onInputButtonsOnRight,
        voiceInputLanguageTag = voiceInputLanguageTag,
        customizationEnabled = customizationEnabled,
        inputTargetId = inputTargetId,
        bufferedInputSendEnabled = bufferedInputSendEnabled,
        bufferedInputDraftState = bufferedInputDraftState,
        inputContext = inputContext,
        showCodexInputStatus = showCodexInputStatus,
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
    voiceInputEnabled: Boolean = false,
    inputKeyboardLayout: String = "",
    inputButtonsOnRight: Boolean = false,
    onInputButtonsOnRight: (Boolean) -> Unit = {},
    voiceInputLanguageTag: String = "",
    customizationEnabled: Boolean,
    inputTargetId: Long,
    bufferedInputSendEnabled: Boolean,
    bufferedInputDraftState: BufferedInputDraftState,
    inputContext: TerminalInputContext = TerminalInputContext(),
    showCodexInputStatus: Boolean = false,
    onAction: (TerminalAccessoryAction) -> Unit,
    onCustomize: () -> Unit,
    onSendBufferedInput: (Long, String) -> Boolean,
    onSubmitBufferedInput: (Long, String) -> Boolean = onSendBufferedInput,
    onBufferedInputModeChanged: (Boolean) -> Unit,
    onDirectInputMode: () -> Unit,
    imageContentCallback: TerminalImageContentCallback? = null,
    snippetsContent: (@Composable () -> Unit)? = null,
    snippetsAboveKeyboard: Boolean = false,
    onSelectFiles: (() -> Unit)? = null,
    onEditAiAgents: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    buttonColors: Map<String, String> = emptyMap(),
) {
    CompositionLocalProvider(LocalButtonColors provides buttonColors) {
        var expandedKeyboard by remember(inputTargetId) { mutableStateOf(false) }
        BackHandler(enabled = expandedKeyboard) { expandedKeyboard = false }
        val dispatchAction: (TerminalAccessoryAction) -> Unit = { action ->
            if ((action as? TerminalAccessoryAction.Local)?.action == TerminalLocalAccessoryAction.EXPANDED_KEYBOARD) {
                expandedKeyboard = !expandedKeyboard
            } else {
                onAction(action)
            }
        }
        val context = LocalContext.current
        val snippetPreferences = remember(context) {
            context.applicationContext.getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
        }
        var pinnedSnippetsExpanded by remember(snippetPreferences) {
            mutableStateOf(snippetPreferences.getBoolean("expanded", true))
        }
        var pinSnippets by rememberSaveable(snippetsAboveKeyboard) { mutableStateOf(snippetsAboveKeyboard) }
        val onToggleSnippets: (() -> Unit)? = if (snippetsContent != null) {
            {
                pinnedSnippetsExpanded = !pinSnippets || !pinnedSnippetsExpanded
                pinSnippets = true
                snippetPreferences.edit().putBoolean("expanded", pinnedSnippetsExpanded).apply()
            }
        } else null
        val focusManager = LocalFocusManager.current
        val hapticFeedback = LocalHapticFeedback.current
        val currentOnBufferedInputModeChanged by rememberUpdatedState(onBufferedInputModeChanged)
        val currentOnDirectInputMode by rememberUpdatedState(onDirectInputMode)

        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val keyboardVisible = rememberTerminalKeyboardVisible()
                var manualInputMode by remember(inputTargetId) { mutableStateOf<Boolean?>(null) }
                var inputContextIdentity by remember(inputTargetId) { mutableStateOf(inputContext.identity) }
                var bufferedInputMode by remember(inputTargetId) {
                    mutableStateOf(inputContext.agent || bufferedInputDraftState.value.text.isNotEmpty())
                }
                var bufferedInputPageWasActive by remember { mutableStateOf(false) }
                LaunchedEffect(inputTargetId, inputContext, manualInputMode, bufferedInputDraftState.value.text) {
                    // Failed metadata reads have no identity. Only a confirmed pane change may
                    // reset the user's choice; agent flags and pane geometry are live metadata.
                    if (inputContext.identity.isNotEmpty() && inputContext.identity != inputContextIdentity) {
                        if (inputContextIdentity.isNotEmpty()) manualInputMode = null
                        inputContextIdentity = inputContext.identity
                    }
                    val manual = manualInputMode
                    // Never hide an in-progress draft because a metadata refresh or pane switch arrives.
                    if (manual != null) bufferedInputMode = manual
                    else if (inputContext.identity.isNotEmpty() &&
                        (!bufferedInputMode || bufferedInputDraftState.value.text.isEmpty())) {
                        bufferedInputMode = inputContext.agent
                    }
                }
                val columnCount = (terminalShortcutColumnCount(maxWidth.value) - 1).coerceAtLeast(1)
                val keyWidth = terminalAccessoryKeyWidth(maxWidth)
                val rowCount = layout.rowCount
                val displayedActions = displayedAccessoryActions(actions)
                val shortcutActions = displayedActions.filterNot {
                    it.defaultDeckKeyOrNull() == TerminalExtraKey.ESC ||
                        it.defaultDeckKeyOrNull() == TerminalExtraKey.TAB
                }
                val shortcutPages = terminalAccessoryPages(
                    shortcutActions, columnCount, rowCount,
                    customizationEnabled && !actions.matchDefaultAccessoryDeck(),
                )
                val inputPageCount = if (bufferedInputMode) 1 else shortcutPages.size
                val pagerState = rememberPagerState { inputPageCount + if (snippetsContent != null && !pinSnippets) 1 else 0 }
                // Snippets send into the terminal, so the hidden draft must not keep owning the IME.
                val bufferedInputPageActive = bufferedInputMode && (pinSnippets || pagerState.currentPage == 0)
                LaunchedEffect(bufferedInputPageActive, keyboardVisible) {
                    currentOnBufferedInputModeChanged(bufferedInputPageActive && keyboardVisible)
                    if (!bufferedInputPageActive && bufferedInputPageWasActive) {
                        focusManager.clearFocus(force = true)
                        currentOnDirectInputMode()
                    }
                    bufferedInputPageWasActive = bufferedInputPageActive
                }
                DisposableEffect(Unit) {
                    onDispose { currentOnBufferedInputModeChanged(false) }
                }

                val bufferedContent: @Composable () -> Unit = {
                    Row(Modifier.fillMaxSize()) {
                        BufferedImageInput(imageContentCallback) {
                            BufferedInputPage(
                                keyWidth = keyWidth,
                                inputTargetId = inputTargetId,
                                sendEnabled = bufferedInputSendEnabled,
                                draftState = bufferedInputDraftState,
                                codexStatus = if (showCodexInputStatus && inputContext.agent && inputContext.agentId == "codex")
                                    inputContext.codexStatus ?: com.yanjiyu.terminalspike.terminal.CodexInputStatus() else null,
                                active = keyboardVisible && bufferedInputPageActive,
                                multilineConfirmationEnabled = multilinePasteConfirmationEnabled,
                                voiceInputEnabled = voiceInputEnabled,
                                inputKeyboardLayout = inputKeyboardLayout,
                                inputButtonsOnRight = inputButtonsOnRight,
                                onInputButtonsOnRight = onInputButtonsOnRight,
                                voiceInputLanguageTag = voiceInputLanguageTag,
                                onSend = onSendBufferedInput,
                                onSubmit = onSubmitBufferedInput,
                                keyRepeatEnabled = keyRepeatEnabled,
                                availableActions = actions,
                                modifiers = modifiers,
                                onAction = dispatchAction,
                                onEnter = { onAction(TerminalExtraKey.ENTER.toAccessoryAction()) },
                                onToggleTyping = { manualInputMode = false },
                                onInputInteraction = { manualInputMode = true },
                                snippetsExpanded = pinSnippets && pinnedSnippetsExpanded,
                                onToggleSnippets = onToggleSnippets,
                            )
                        }
                    }
                }
                val shortcutContent: @Composable (Int) -> Unit = { page ->
                    ShortcutDeckPage(
                        shortcutPages, page, keyWidth, columnCount, rowCount, modifiers,
                        customizationEnabled, keyRepeatEnabled,
                        onPressFeedback = {
                            if (hapticFeedbackEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        },
                        onAction = dispatchAction, onCustomize = onCustomize,
                        onTyping = { manualInputMode = true }, onSelectFiles = onSelectFiles,
                        onEditAiAgents = onEditAiAgents,
                        snippetsExpanded = pinSnippets && pinnedSnippetsExpanded, onToggleSnippets = onToggleSnippets,
                    )
                }

                Column {
                    if (expandedKeyboard) {
                        ExpandedTerminalKeyboard(
                            modifiers = modifiers,
                            keyRepeatEnabled = keyRepeatEnabled,
                            onPressFeedback = {
                                if (hapticFeedbackEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            },
                            onAction = onAction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!expandedKeyboard && pinSnippets && snippetsContent != null && pinnedSnippetsExpanded) {
                        Box(Modifier.fillMaxWidth().heightIn(max = 106.dp).testTag("terminal_pinned_snippets")) {
                            snippetsContent()
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(106.dp)) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("terminal_input_pager"),
                        ) { page ->
                            if (!pinSnippets && snippetsContent != null && page == inputPageCount) {
                                snippetsContent()
                            } else if (bufferedInputMode) {
                                bufferedContent()
                            } else {
                                shortcutContent(page)
                            }
                        }
                    }
                }

            }
        }
    }
}

private fun displayedAccessoryActions(actions: List<TerminalAccessoryAction>): List<TerminalAccessoryAction> =
    if (actions.matchDefaultAccessoryDeck()) {
        actions.flatMap { action ->
            when (action.defaultDeckKeyOrNull()) {
                TerminalExtraKey.CTRL_W -> listOf(TerminalAccessoryAction.Local(
                    TerminalLocalAccessoryAction.EXPANDED_KEYBOARD,
                    stableId = action.stableId,
                ))
                TerminalExtraKey.PAGE_UP -> emptyList()
                TerminalExtraKey.BACKSPACE -> listOf(action, defaultSnippetToggle)
                TerminalExtraKey.CTRL -> listOf(TerminalExtraKey.SHIFT_LEFT.toAccessoryAction())
                else -> listOf(action)
            }
        }
    } else actions

/** A non-modal panel keeps key dispatch and modifier state attached to the active terminal. */
@Composable
private fun ExpandedTerminalKeyboard(
    modifiers: AccessoryModifierSnapshot,
    keyRepeatEnabled: Boolean,
    onPressFeedback: () -> Unit,
    onAction: (TerminalAccessoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keys = remember {
        val first = listOf(TerminalExtraKey.ESC, TerminalExtraKey.TAB) +
            TerminalExtraKey.entries.filter { it.name.matches(Regex("F[0-9]+")) }
        (first + TerminalExtraKey.entries.filter { !it.isLocalAction }).distinct()
            .map { it.toAccessoryAction() } +
            TerminalAccessoryAction.Modifier(TerminalAccessoryModifier.SHIFT)
    }
    Column(modifier.fillMaxWidth().fillMaxHeight().testTag("terminal_expanded_keyboard")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val columns = (maxWidth.value / 52f).toInt().coerceIn(1, 10)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                keys.chunked(columns).forEach { row ->
                    ExtraKeyRow(
                        cells = row, columnCount = columns, modifiers = modifiers,
                        customizationEnabled = false, keyRepeatEnabled = keyRepeatEnabled,
                        onPressFeedback = onPressFeedback, onAction = onAction,
                        onCustomize = {}, snippetsExpanded = false, onToggleSnippets = null,
                        onSelectFiles = null, modifier = Modifier.height(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutDeckPage(
    shortcutPages: List<List<TerminalAccessoryAction?>>,
    page: Int,
    keyWidth: Dp,
    columnCount: Int,
    rowCount: Int,
    modifiers: AccessoryModifierSnapshot,
    customizationEnabled: Boolean,
    keyRepeatEnabled: Boolean,
    onPressFeedback: () -> Unit,
    onAction: (TerminalAccessoryAction) -> Unit,
    onCustomize: () -> Unit,
    onTyping: () -> Unit,
    onSelectFiles: (() -> Unit)?,
    onEditAiAgents: (() -> Unit)? = null,
    snippetsExpanded: Boolean,
    onToggleSnippets: (() -> Unit)?,
    keyModifier: (TerminalAccessoryAction) -> Modifier = { Modifier },
) {
    val escapeDescription = TerminalExtraKey.ESC.accessibilityDescription.resolve()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(keyWidth + 3.dp).fillMaxHeight().padding(vertical = 3.dp)
            .padding(start = 3.dp),
            verticalArrangement = Arrangement.spacedBy(TerminalAccessoryRowGap)) {
            ExtraKeyButton(
                label = stringResource(R.string.terminal_escape_short),
                description = escapeDescription,
                onClick = { onAction(TerminalExtraKey.ESC.toAccessoryAction()) },
                modifier = keyModifier(TerminalExtraKey.ESC.toAccessoryAction()).weight(1f).fillMaxWidth(),
            )
            TypingToggle(
                active = false,
                onClick = onTyping,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().testTag("buffered_text_viewport")) {
            ExtraKeyPage(
                cells = shortcutPages[page],
                columnCount = columnCount,
                rowCount = rowCount,
                modifiers = modifiers,
                customizationEnabled = customizationEnabled,
                keyRepeatEnabled = keyRepeatEnabled,
                onPressFeedback = onPressFeedback,
                onAction = onAction,
                onCustomize = onCustomize,
                onSelectFiles = onSelectFiles,
                onEditAiAgents = onEditAiAgents,
                snippetsExpanded = snippetsExpanded,
                keyModifier = keyModifier,
                onToggleSnippets = onToggleSnippets,
            )
        }
    }
}

/** Shares action labels, icons and persisted colors with the live shortcut row. */
@Composable
internal fun TerminalShortcutEditorKey(
    action: TerminalAccessoryAction,
    buttonColors: Map<String, String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalButtonColors provides buttonColors) {
        ExtraKeyRow(
            cells = listOf(action), columnCount = 1,
            modifiers = AccessoryModifierSnapshot(), customizationEnabled = true,
            keyRepeatEnabled = false, onPressFeedback = {}, onAction = { onClick() },
            onCustomize = onClick, snippetsExpanded = false, onToggleSnippets = null,
            onSelectFiles = null, modifier = modifier.semantics(mergeDescendants = true) {},
        )
    }
}

/** Settings uses the live shortcut renderer without starting terminal input or IME state. */
@Composable
internal fun TerminalShortcutPreview(
    actions: List<TerminalAccessoryAction>,
    layout: KeyboardLayout,
    keyTagPrefix: String,
    onKeyClick: (Int) -> Unit,
    onEditKeys: () -> Unit,
    buttonColors: Map<String, String> = emptyMap(),
) {
    CompositionLocalProvider(LocalButtonColors provides buttonColors) {
    fun sourceIndex(action: TerminalAccessoryAction): Int = actions.indexOfFirst {
        it.stableId == action.stableId ||
            (action.defaultDeckKeyOrNull() != null && it.defaultDeckKeyOrNull() == action.defaultDeckKeyOrNull())
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(106.dp)) {
            val columns = (terminalShortcutColumnCount(maxWidth.value) - 1).coerceAtLeast(1)
            val keyWidth = terminalAccessoryKeyWidth(maxWidth)
            val shortcuts = displayedAccessoryActions(actions).filterNot {
                it.defaultDeckKeyOrNull() == TerminalExtraKey.ESC ||
                    it.defaultDeckKeyOrNull() == TerminalExtraKey.TAB
            }
            val pages = terminalAccessoryPages(shortcuts, columns, layout.rowCount,
                !actions.matchDefaultAccessoryDeck())
            val pager = rememberPagerState { pages.size }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                ShortcutDeckPage(
                    pages, page, keyWidth, columns, layout.rowCount, AccessoryModifierSnapshot(),
                    customizationEnabled = true, keyRepeatEnabled = false, onPressFeedback = {},
                    onAction = { action ->
                        val index = sourceIndex(action)
                        if (index >= 0) onKeyClick(index) else onEditKeys()
                    },
                    onCustomize = onEditKeys, onTyping = onEditKeys, onSelectFiles = null,
                    snippetsExpanded = false, onToggleSnippets = null,
                    keyModifier = { action ->
                        val index = sourceIndex(action)
                        if (index >= 0) Modifier.testTag("$keyTagPrefix$index") else Modifier
                    },
                )
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
private fun TypingToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    ExtraKeyButton(
        label = stringResource(R.string.terminal_typing_toggle),
        description = stringResource(R.string.terminal_typing_toggle),
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.settings_input_buttons_right_summary),
        glyph = ConnectionsGlyph.WRITE,
        emphasized = true,
        modifier = modifier.fillMaxWidth().testTag("terminal_typing_toggle").semantics {
            selected = active
        },
    )
}

@Composable
private fun BufferedInputPage(
    keyWidth: Dp,
    inputTargetId: Long,
    sendEnabled: Boolean,
    draftState: BufferedInputDraftState,
    codexStatus: com.yanjiyu.terminalspike.terminal.CodexInputStatus?,
    active: Boolean,
    multilineConfirmationEnabled: Boolean,
    voiceInputEnabled: Boolean,
    inputKeyboardLayout: String,
    inputButtonsOnRight: Boolean,
    onInputButtonsOnRight: (Boolean) -> Unit,
    voiceInputLanguageTag: String,
    onSend: (Long, String) -> Boolean,
    onSubmit: (Long, String) -> Boolean,
    keyRepeatEnabled: Boolean,
    availableActions: List<TerminalAccessoryAction>,
    modifiers: AccessoryModifierSnapshot,
    onAction: (TerminalAccessoryAction) -> Unit,
    onEnter: () -> Unit,
    onToggleTyping: () -> Unit,
    onInputInteraction: () -> Unit,
    snippetsExpanded: Boolean,
    onToggleSnippets: (() -> Unit)?,
) {
    val draft = draftState.value
    val validationMessage = draftState.validationMessage
    val canRestoreLastSent = draftState.canRestoreLastSent
    val enterDescription = stringResource(R.string.terminal_buffered_input_enter)
    val inputDescription = stringResource(R.string.terminal_buffered_input_description)
    val restoreInputDescription = stringResource(R.string.terminal_restore_last_sent_input)
    val focusRequester = remember { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    val currentInputTargetId by rememberUpdatedState(inputTargetId)
    val currentVoiceLanguageTag by rememberUpdatedState(voiceInputLanguageTag)
    val currentVoiceInputEnabled by rememberUpdatedState(voiceInputEnabled)
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
                if (!currentVoiceInputEnabled) return@AndroidVoiceInputRecognizer
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
        if (!currentVoiceInputEnabled) return@rememberLauncherForActivityResult
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

    LaunchedEffect(inputTargetId, voiceInputEnabled) {
        if (voicePhase != VoiceInputPhase.IDLE) voiceRecognizer.cancel()
        if (!voiceInputEnabled) {
            partialTranscript = ""
            voiceFailure = null
        }
    }

    fun sendDraft(submit: Boolean = false) {
        // Explicit Send/Enter confirms the word even if the IME still marks it composing.
        draftState.finishComposition()
        val draft = draftState.value
        val validationMessage = draftState.validationMessage
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

    val interactionSource = remember { MutableInteractionSource() }
    val inputFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(active) {
        // Insets-driven composition can lag behind a system Back dismissal. Do not let
        // a pending focus transfer reopen a keyboard that Android has already hidden.
        if (active && !inputFocused &&
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            focusRequester.requestFocus()
            softwareKeyboardController?.show()
        }
    }

    val customLayout = com.yanjiyu.terminalspike.core.model.InputKeyboardLayout.parse(inputKeyboardLayout)
    val tokens = customLayout ?: com.yanjiyu.terminalspike.core.model.InputKeyboardLayout.defaultTokens(inputButtonsOnRight)
    InputKeyboardFrame(
        tokens = tokens,
        modifier = Modifier.fillMaxSize(),
        legacyKeyWidth = keyWidth,
        key = { _, token, keyModifier ->
            when (token) {
                "toggle" -> TypingToggle(
                    active = true, onClick = onToggleTyping, modifier = keyModifier,
                    onLongClick = if (customLayout == null) ({ onInputButtonsOnRight(!inputButtonsOnRight) }) else null,
                )
                "enter" -> ExtraKeyButton(
                    label = stringResource(R.string.terminal_enter_short), description = enterDescription,
                    enabled = sendEnabled && validationMessage == null, onClick = { sendDraft(true) },
                    onLongClick = if (customLayout == null) ({ onInputButtonsOnRight(!inputButtonsOnRight) }) else null,
                    onLongClickLabel = stringResource(R.string.settings_input_buttons_right_summary),
                    modifier = keyModifier.testTag("buffered_input_enter"),
                )
                else -> {
                    val action = KeyboardAction.entries.firstOrNull { it.wireCode == token }?.let { code ->
                        availableActions.firstOrNull { it.stableId == code.wireCode }
                            ?: com.yanjiyu.terminalspike.ui.settings.toInputAccessoryAction(code)
                    }
                    if (action != null) ExtraKeyRow(
                        cells = listOf(action), columnCount = 1, modifiers = modifiers,
                        customizationEnabled = false, keyRepeatEnabled = keyRepeatEnabled, onPressFeedback = {},
                        onAction = onAction, onCustomize = {}, snippetsExpanded = snippetsExpanded,
                        onToggleSnippets = onToggleSnippets, onSelectFiles = null, modifier = keyModifier,
                    )
                }
            }
        },
        input = { _, fieldModifier ->
            val voiceStatus = if (voiceInputEnabled) {
                voiceInputStatus(voicePhase, partialTranscript, voiceFailure)
            } else null
            BasicTextField(
                value = draft,
                onValueChange = { nextValue -> draftState.update(nextValue, inputTargetId) },
                modifier = fieldModifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onInputInteraction() }
                    .testTag("buffered_terminal_input")
                    .semantics { contentDescription = inputDescription },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interactionSource,
                maxLines = Int.MAX_VALUE,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = true,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                decorationBox = { innerTextField ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.extraSmall,
                        border = BorderStroke(
                            if (inputFocused) 2.dp else 1.dp,
                            when {
                                validationMessage != null -> MaterialTheme.colorScheme.error
                                inputFocused -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        ),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                                Row(Modifier.weight(1f).fillMaxHeight()) {
                                    Box(Modifier.weight(1f).fillMaxHeight().testTag("buffered_text_viewport")) {
                                        if (draft.text.isEmpty()) {
                                            Text(
                                                stringResource(R.string.terminal_buffered_input_placeholder),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (onToggleSnippets != null) {
                                        val toggleDescription = stringResource(
                                            if (snippetsExpanded) R.string.terminal_hide_pinned_snippets
                                            else R.string.terminal_show_pinned_snippets,
                                        )
                                        IconButton(
                                            onClick = onToggleSnippets,
                                            modifier = Modifier.size(40.dp)
                                                .testTag("terminal_toggle_pinned_snippets")
                                                .semantics { contentDescription = toggleDescription },
                                        ) {
                                            ConnectionsGlyphIcon(
                                                glyph = ConnectionsGlyph.BACK,
                                                modifier = Modifier.size(18.dp)
                                                    .rotate(if (snippetsExpanded) -90f else 90f),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (voiceInputEnabled || canRestoreLastSent) Column(
                                    modifier = Modifier.width(48.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (voiceInputEnabled) {
                                        val voiceActive = voicePhase != VoiceInputPhase.IDLE
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
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    onInputInteraction()
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
                                                modifier = Modifier.size(44.dp).semantics {
                                                    contentDescription = voiceDescription
                                                    stateDescription = voiceState
                                                },
                                            ) {
                                                ConnectionsGlyphIcon(
                                                    glyph = if (voiceActive) ConnectionsGlyph.STOP else ConnectionsGlyph.MIC,
                                                    modifier = Modifier.size(21.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (canRestoreLastSent) {
                                        IconButton(
                                            onClick = draftState::restoreLastSent,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .semantics { contentDescription = restoreInputDescription },
                                        ) {
                                            ConnectionsGlyphIcon(
                                                glyph = ConnectionsGlyph.UNDO,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }

                                }
                            }
                            (validationMessage?.resolve() ?: voiceStatus)?.let { message ->
                                Text(
                                    message,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    color = if (validationMessage != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            codexStatus?.let { status ->
                                val description = stringResource(R.string.codex_input_status_description,
                                    status.contextRemaining?.toString() ?: "—",
                                    status.weeklyRemaining?.toString() ?: "—")
                                Text(status.compactText(),
                                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp, vertical = 2.dp)
                                        .testTag("codex_input_status").semantics { contentDescription = description },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
            )
        },
    )

    if (confirmingMultilinePaste) {
        AppDialog(
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
    snippetsExpanded: Boolean,
    onToggleSnippets: (() -> Unit)?,
    onSelectFiles: (() -> Unit)?,
    onEditAiAgents: (() -> Unit)? = null,
    keyModifier: (TerminalAccessoryAction) -> Modifier = { Modifier },
) {
    val rows = cells.chunked(columnCount)

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(TerminalAccessoryRowGap),
    ) {
        repeat(rowCount) { rowIndex ->
            ExtraKeyRow(
                keyModifier = keyModifier,
                cells = rows.getOrElse(rowIndex) { emptyList() },
                columnCount = columnCount,
                modifiers = modifiers,
                customizationEnabled = customizationEnabled,
                keyRepeatEnabled = keyRepeatEnabled,
                onPressFeedback = onPressFeedback,
                onAction = onAction,
                onCustomize = onCustomize,
                onSelectFiles = onSelectFiles,
                onEditAiAgents = onEditAiAgents,
                snippetsExpanded = snippetsExpanded,
                onToggleSnippets = onToggleSnippets,
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
    snippetsExpanded: Boolean,
    onToggleSnippets: (() -> Unit)?,
    onSelectFiles: (() -> Unit)?,
    onEditAiAgents: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    keyModifier: (TerminalAccessoryAction) -> Modifier = { Modifier },
) {
    val editLabel = stringResource(R.string.terminal_edit_keys_short)
    val customizeDescription = stringResource(R.string.terminal_customize_keys)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TerminalAccessoryColumnGap),
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
            } else if (cell == defaultSnippetToggle) {
                val expanded = onToggleSnippets != null && snippetsExpanded
                val description = stringResource(
                    if (expanded) R.string.terminal_hide_pinned_snippets
                    else R.string.terminal_show_pinned_snippets,
                )
                ExtraKeyButton(
                    modifier = keyModifier(cell).weight(1f).testTag("terminal_toggle_pinned_snippets"),
                    label = description,
                    description = description,
                    customBackground = LocalButtonColors.current[cell.stableId]?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() },
                    glyph = ConnectionsGlyph.BACK,
                    glyphRotation = if (expanded) -90f else 90f,
                    onPressFeedback = onPressFeedback,
                    onClick = onToggleSnippets ?: { onAction(cell) },
                )
            } else {
                val dispatch = cell.resolve(modifiers)
                val modifierState = (cell as? TerminalAccessoryAction.Modifier)
                    ?.let { modifiers.stateOf(it.modifier) }
                val disabledReason = (dispatch as? TerminalAccessoryDispatch.Unsupported)
                    ?.explanation
                    ?.resolve()
                ExtraKeyButton(
                    modifier = keyModifier(cell).weight(1f),
                    label = cell.label.resolve(),
                    customBackground = LocalButtonColors.current[cell.stableId]?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                        ?: if ((cell as? TerminalAccessoryAction.Local)?.action == TerminalLocalAccessoryAction.AI_AGENTS) Color(0xFF6750A4) else null,
                    modifierState = modifierState,
                    enabled = disabledReason == null,
                    description = cell.accessibilityDescription.resolve(),
                    disabledReason = disabledReason,
                    repeatEnabled = keyRepeatEnabled && cell.supportsLongPressRepeat,
                    glyph = when ((cell as? TerminalAccessoryAction.Local)?.action) {
                        TerminalLocalAccessoryAction.TMUX_SESSIONS -> ConnectionsGlyph.WINDOWS
                        TerminalLocalAccessoryAction.SELECT_IMAGES -> ConnectionsGlyph.IMAGE
                        TerminalLocalAccessoryAction.HIDE_KEYBOARD -> ConnectionsGlyph.KEYBOARD
                        TerminalLocalAccessoryAction.EXPANDED_KEYBOARD -> ConnectionsGlyph.EXPANDED_KEYBOARD
                        else -> null
                    },
                    onPressFeedback = onPressFeedback,
                    onClick = { onAction(cell) },
                    onLongClick = when ((cell as? TerminalAccessoryAction.Local)?.action) {
                        TerminalLocalAccessoryAction.SELECT_IMAGES -> onSelectFiles
                        TerminalLocalAccessoryAction.AI_AGENTS -> onEditAiAgents
                        else -> null
                    },
                    onLongClickLabel = stringResource(
                        if ((cell as? TerminalAccessoryAction.Local)?.action == TerminalLocalAccessoryAction.AI_AGENTS)
                            R.string.ai_agents_choose else R.string.terminal_select_files,
                    ),
                )
            }
        }
        repeat(columnCount - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
internal fun ExtraKeyButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    modifierState: AccessoryModifierState? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    repeatEnabled: Boolean = false,
    glyph: ConnectionsGlyph? = null,
    glyphRotation: Float = 0f,
    emphasized: Boolean = false,
    customBackground: Color? = null,
    onPressFeedback: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
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
    val containerColor by animateColorAsState(targetValue = when (modifierState) {
        AccessoryModifierState.ARMED -> MaterialTheme.colorScheme.primaryContainer
        AccessoryModifierState.LOCKED -> MaterialTheme.colorScheme.tertiaryContainer
        AccessoryModifierState.OFF, null -> if (emphasized) MaterialTheme.colorScheme.primary
            else customBackground ?: MaterialTheme.colorScheme.surfaceContainerLow
    }, label = "accessory-key-state")
    val contentColor by animateColorAsState(targetValue = when (modifierState) {
        AccessoryModifierState.ARMED -> MaterialTheme.colorScheme.onPrimaryContainer
        AccessoryModifierState.LOCKED -> MaterialTheme.colorScheme.onTertiaryContainer
        AccessoryModifierState.OFF, null -> if (emphasized) MaterialTheme.colorScheme.onPrimary
            else customBackground?.let { if (it.luminance() > 0.179f) Color.Black else Color.White }
                ?: MaterialTheme.colorScheme.onSurface
    }, label = "accessory-key-content")

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .accessoryKeyInput(
                enabled = enabled,
                repeatEnabled = repeatEnabled,
                onPressFeedback = onPressFeedback,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
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
        border = if (modifierState?.isActive == true) {
            BorderStroke(1.dp, contentColor.copy(alpha = 0.45f))
        } else null,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 1.dp), contentAlignment = Alignment.Center) {
            if (glyph != null) {
                ConnectionsGlyphIcon(
                    glyph = glyph,
                    modifier = Modifier.size(20.dp).rotate(glyphRotation),
                )
            } else {
                Text(
                    text = label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                ConnectionsGlyphIcon(
                    glyph = ConnectionsGlyph.LOCK,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp, top = 3.dp).size(9.dp)
                        .testTag("terminal-modifier-lock"),
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
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val currentOnPressFeedback by rememberUpdatedState(onPressFeedback)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    return this
        .indication(interactionSource, indication)
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
            if (onLongClick != null) {
                onLongClick(label = onLongClickLabel) {
                    if (!enabled) return@onLongClick false
                    currentOnPressFeedback()
                    currentOnLongClick?.invoke()
                    true
                }
            }
            onClick {
                if (!enabled) return@onClick false
                currentOnPressFeedback()
                currentOnClick()
                true
            }
        }
        .pointerInput(enabled, repeatEnabled, onLongClick != null) {
            if (!enabled) return@pointerInput
            kotlinx.coroutines.coroutineScope {
                val repeatScope = this
                this@pointerInput.awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    currentOnPressFeedback()
                    var repeated = false
                    val repeatJob = if (currentOnLongClick != null) {
                        repeatScope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            repeated = true
                            currentOnLongClick?.invoke()
                        }
                    } else if (repeatEnabled) {
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

/** Observe only visibility edges; animated pixel heights must not recompose the accessory deck. */
@Composable
internal fun rememberTerminalKeyboardVisible(insets: WindowInsets = WindowInsets.ime): Boolean {
    val density = LocalDensity.current
    return remember(insets, density) {
        derivedStateOf { insets.getBottom(density) > 0 }
    }.value
}
