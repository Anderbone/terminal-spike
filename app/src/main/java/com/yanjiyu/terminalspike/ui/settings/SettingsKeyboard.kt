package com.yanjiyu.terminalspike.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.ui.TerminalShortcutEditorKey
import com.yanjiyu.terminalspike.ui.TerminalShortcutPreview
import com.yanjiyu.terminalspike.ui.theme.AppDialog

@Composable
internal fun KeyboardSettings(state: SettingsUiState, actions: SettingsActions) {
    val profile = state.keyboardProfile
    var editInputKeys by remember { mutableStateOf(false) }
    var editKeys by remember { mutableStateOf(false) }
    var replacementIndex by remember(profile?.id) { mutableStateOf<Int?>(null) }
    var resetRequested by remember { mutableStateOf(false) }
    if (editInputKeys) {
        InputKeyboardEditor(
        state.preferences.inputKeyboardLayout, state.preferences.inputButtonsOnRight,
        onDismiss = { editInputKeys = false },
        onSave = { actions.onInputKeyboardLayout(it); editInputKeys = false },
        )
        return
    }

    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize().testTag(KeyboardSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_keyboard, R.string.settings_keyboard_intro) }
        if (profile == null) {
            item { LoadingOrUnavailable(state.profilesLoading) }
        } else {
            item(key = R.string.settings_keyboard_preset_heading) { SectionLabel(R.string.settings_keyboard_preset_heading) }
            item {
                val selectedPresetId = KeyboardPresets.selectedId(profile)
                HorizontalChoices {
                    KeyboardPresets.selectable.forEach { preset ->
                        FilterChip(
                            selected = selectedPresetId == preset.id,
                            onClick = { actions.onKeyboardPreset(preset.id) },
                            label = { Text(stringResource(preset.labelRes)) },
                        )
                    }
                    if (selectedPresetId == "custom") {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_keyboard_preset_custom),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item(key = R.string.settings_edit_keyboard_keys) {
                Text(
                    text = stringResource(R.string.settings_keyboard_replace_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                KeyboardDeckPreview(
                    profile = profile,
                    buttonColors = state.buttonColors,
                    onKeyClick = { index -> replacementIndex = index },
                    onEditKeys = { editKeys = true },
                )
                Button(
                    onClick = { editKeys = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text(stringResource(R.string.settings_edit_keyboard_keys)) }
                Text(
                    text = stringResource(R.string.settings_keyboard_key_count, profile.orderedActions.size, profile.layout.rowCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item { SectionDivider() }
            item(key = R.string.settings_keyboard_layout_heading) { SectionLabel(R.string.settings_keyboard_layout_heading) }
            item {
                HorizontalChoices {
                    KeyboardLayout.entries.forEach { layout ->
                        FilterChip(
                            selected = profile.layout == layout,
                            onClick = { actions.onKeyboardLayout(layout) },
                            label = {
                                Text(
                                    if (layout == KeyboardLayout.ONE_ROW) {
                                        stringResource(R.string.settings_keyboard_one_row)
                                    } else {
                                        stringResource(R.string.settings_keyboard_two_rows)
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_keyboard_layout_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionDivider() }
            if (state.preferences.inputKeyboardLayout.isEmpty()) item(key = R.string.settings_input_buttons_right) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_input_buttons_right),
                    summary = stringResource(R.string.settings_input_buttons_right_summary),
                    checked = state.preferences.inputButtonsOnRight,
                    onCheckedChange = actions.onInputButtonsOnRight,
                )
            }
            item(key = R.string.settings_input_keyboard_edit) {
                val inputTokens = com.yanjiyu.terminalspike.core.model.InputKeyboardLayout.parse(state.preferences.inputKeyboardLayout)
                    ?: com.yanjiyu.terminalspike.core.model.InputKeyboardLayout.defaultTokens(state.preferences.inputButtonsOnRight)
                InputKeyboardPreview(inputTokens, selected = null,
                    onSelect = { editInputKeys = true }, onMove = { from, to ->
                        actions.onInputKeyboardLayout(inputTokens.toMutableList().apply { add(to, removeAt(from)) }.joinToString(","))
                    })
                Button(onClick = { editInputKeys = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_input_keyboard_edit))
                }
                Text(stringResource(R.string.settings_input_keyboard_summary), style = MaterialTheme.typography.bodySmall)
            }
            item(key = R.string.settings_voice_input_heading) { SectionLabel(R.string.settings_voice_input_heading) }
            item(key = R.string.settings_voice_input_enabled) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_voice_input_enabled),
                    summary = stringResource(R.string.settings_voice_input_enabled_summary),
                    checked = state.preferences.voiceInputEnabled,
                    onCheckedChange = actions.onVoiceInputEnabled,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.settings_voice_input_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                HorizontalChoices {
                    VoiceInputLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = state.preferences.voiceInputLanguage == language,
                            enabled = state.preferences.voiceInputEnabled,
                            onClick = { actions.onVoiceInputLanguage(language) },
                            label = { Text(stringResource(language.labelRes)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_voice_input_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                TextButton(onClick = { resetRequested = true }) {
                    Text(stringResource(R.string.settings_restore_keyboard_preset))
                }
            }
        }
    }

    if (editKeys && profile != null) {
        KeyboardActionsDialog(
            initialActions = profile.orderedActions,
            buttonColors = state.buttonColors,
            rowCount = profile.layout.rowCount,
            onDismiss = { editKeys = false },
            onSave = {
                editKeys = false
                actions.onKeyboardActions(it)
            },
        )
    }
    replacementIndex?.let { index ->
        profile?.orderedActions?.getOrNull(index)?.let { current ->
            KeyboardKeyReplacementDialog(
                current = current,
                onDismiss = { replacementIndex = null },
                onSelect = { replacement ->
                    actions.onKeyboardActions(
                        replaceKeyboardActionAt(profile.orderedActions, index, replacement),
                    )
                    replacementIndex = null
                },
            )
        }
    }
    if (resetRequested) {
        ResetDialog(
            title = R.string.settings_reset_keyboard_title,
            message = R.string.settings_reset_keyboard_message,
            onDismiss = { resetRequested = false },
            onConfirm = {
                resetRequested = false
                actions.onResetKeyboard()
            },
        )
    }
}

internal const val KeyboardPreviewKeyTestTagPrefix = "keyboard-preview-key-"

@Composable
private fun KeyboardDeckPreview(
    profile: KeyboardProfile,
    buttonColors: Map<String, String>,
    onKeyClick: (Int) -> Unit,
    onEditKeys: () -> Unit,
) {
    val runtimeActions = profile.toRuntimeAccessoryActionsOrNull().orEmpty()
    Box(Modifier.fillMaxWidth().testTag(KeyboardPreviewTestTag)) {
        TerminalShortcutPreview(
            actions = runtimeActions,
            buttonColors = buttonColors,
            layout = profile.layout,
            keyTagPrefix = KeyboardPreviewKeyTestTagPrefix,
            onKeyClick = onKeyClick,
            onEditKeys = onEditKeys,
        )
    }
}

@Composable
internal fun KeyboardKeyReplacementDialog(
    current: KeyboardAction,
    onDismiss: () -> Unit,
    onSelect: (KeyboardAction) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var search by remember(current) { mutableStateOf("") }
    val choices = runtimeSupportedKeyboardActions.filter { action ->
        keyboardActionSearchText(action).contains(search.trim(), ignoreCase = true)
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.settings_keyboard_replace_title,
                    keyboardActionLabel(current),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_keyboard_replace_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_keyboard_replace_search)) },
                    singleLine = true,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 60.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .testTag(KeyboardReplacementGridTestTag),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(6.dp),
                ) {
                    gridItems(choices, key = KeyboardAction::wireCode) { action ->
                        val label = keyboardActionLabel(action)
                        val replaceWithDescription = stringResource(
                            R.string.settings_keyboard_replace_with_description,
                            label,
                        )
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onSelect(action) }
                                .semantics {
                                    contentDescription = replaceWithDescription
                                    selected = action == current
                                },
                            color = if (action == current) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (action == current) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = if (action == current) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (action == current) {
                                    Text(
                                        stringResource(R.string.settings_keyboard_replace_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal const val KeyboardReplacementGridTestTag = "keyboard-replacement-grid"
internal const val KeyboardEditorDeckTestTag = "keyboard-editor-deck"
internal const val KeyboardEditorRowTestTagPrefix = "keyboard-editor-row-"
internal const val KeyboardEditorKeyTestTagPrefix = "keyboard-editor-key-"

internal fun replaceKeyboardActionAt(
    actions: List<KeyboardAction>,
    index: Int,
    replacement: KeyboardAction,
): List<KeyboardAction> {
    require(index in actions.indices) { "Keyboard action position is out of range." }
    val current = actions[index]
    if (replacement == current) return actions
    return actions.toMutableList().apply {
        val existingIndex = indexOf(replacement)
        this[index] = replacement
        if (existingIndex >= 0) this[existingIndex] = current
    }
}

@Composable
private fun KeyboardActionsDialog(
    initialActions: List<KeyboardAction>,
    buttonColors: Map<String, String>,
    rowCount: Int,
    onDismiss: () -> Unit,
    onSave: (List<KeyboardAction>) -> Unit,
) {
    var actions by remember(initialActions) { mutableStateOf(initialActions) }
    var search by remember { mutableStateOf("") }
    var replacementIndex by remember { mutableStateOf<Int?>(null) }
    val available = runtimeSupportedKeyboardActions.filter { action ->
        action !in actions && keyboardActionSearchText(action).contains(search.trim(), ignoreCase = true)
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_edit_keyboard_keys)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_edit_keyboard_keys_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_keyboard_replace_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyboardActionEditorDeck(
                    buttonColors = buttonColors,
                    actions = actions,
                    rowCount = rowCount,
                    onReplace = { replacementIndex = it },
                    onMove = { action, move ->
                        actions = actions.moveStableAction(action.wireCode, move)
                    },
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_add_key)) },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                    items(available.take(30), key = KeyboardAction::wireCode) { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { actions = actions + action }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(keyboardActionLabel(action), modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.add), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(actions) }, enabled = actions.isNotEmpty()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    replacementIndex?.let { index ->
        actions.getOrNull(index)?.let { current ->
            KeyboardKeyReplacementDialog(
                current = current,
                onDismiss = { replacementIndex = null },
                onSelect = { replacement ->
                    actions = replaceKeyboardActionAt(actions, index, replacement)
                    replacementIndex = null
                },
                onRemove = if (actions.size > 1) {
                    {
                        actions = actions - current
                        replacementIndex = null
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun KeyboardActionEditorDeck(
    buttonColors: Map<String, String>,
    actions: List<KeyboardAction>,
    rowCount: Int,
    onReplace: (Int) -> Unit,
    onMove: (KeyboardAction, KeyboardActionMove) -> Unit,
) {
    val visibleRowCount = rowCount.coerceAtLeast(1)
    val columns = ((actions.size + visibleRowCount - 1) / visibleRowCount).coerceAtLeast(1)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(KeyboardEditorDeckTestTag),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            actions.chunked(columns).forEachIndexed { rowIndex, rowActions ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$KeyboardEditorRowTestTagPrefix$rowIndex"),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rowActions.forEachIndexed { columnIndex, action ->
                        val index = rowIndex * columns + columnIndex
                        KeyboardActionEditorKey(
                            buttonColors = buttonColors,
                            modifier = Modifier.weight(1f),
                            action = action,
                            index = index,
                            actionCount = actions.size,
                            onReplace = { onReplace(index) },
                            onMove = { move -> onMove(action, move) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardActionEditorKey(
    buttonColors: Map<String, String>,
    modifier: Modifier = Modifier,
    action: KeyboardAction,
    index: Int,
    actionCount: Int,
    onReplace: () -> Unit,
    onMove: (KeyboardActionMove) -> Unit,
) {
    val label = keyboardActionLabel(action)
    val moveBeforeLabel = stringResource(R.string.settings_keyboard_move_before)
    val moveAfterLabel = stringResource(R.string.settings_keyboard_move_after)
    val replaceDescription = stringResource(
        R.string.settings_keyboard_replace_description,
        label,
        index + 1,
    )
    TerminalShortcutEditorKey(
        action = requireNotNull(toInputAccessoryAction(action)),
        buttonColors = buttonColors,
        onClick = onReplace,
        modifier = modifier
            .height(48.dp)
            .testTag("$KeyboardEditorKeyTestTagPrefix$index")
            .pointerInput(action.wireCode, index, actionCount) {
                var accumulatedDrag = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulatedDrag = 0f },
                    onDragCancel = { accumulatedDrag = 0f },
                    onDragEnd = { accumulatedDrag = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDrag += dragAmount.x
                        val threshold = size.width.coerceAtLeast(1) * 0.55f
                        when {
                            accumulatedDrag <= -threshold && index > 0 -> {
                                onMove(KeyboardActionMove.BEFORE)
                                accumulatedDrag = 0f
                            }
                            accumulatedDrag >= threshold && index < actionCount - 1 -> {
                                onMove(KeyboardActionMove.AFTER)
                                accumulatedDrag = 0f
                            }
                        }
                    },
                )
            }
            .semantics {
                contentDescription = replaceDescription
                customActions = buildList {
                    if (index > 0) {
                        add(CustomAccessibilityAction(moveBeforeLabel) {
                            onMove(KeyboardActionMove.BEFORE)
                            true
                        })
                    }
                    if (index < actionCount - 1) {
                        add(CustomAccessibilityAction(moveAfterLabel) {
                            onMove(KeyboardActionMove.AFTER)
                            true
                        })
                    }
                }
            },
    )
}

@Composable
internal fun keyboardActionSearchText(action: KeyboardAction): String =
    "${action.wireCode} ${keyboardActionLabel(action)}".replace('_', ' ')

@Composable
internal fun keyboardActionLabel(action: KeyboardAction): String = when (action) {
    KeyboardAction.ESCAPE -> stringResource(R.string.settings_keyboard_action_escape)
    KeyboardAction.CONTROL -> stringResource(R.string.settings_keyboard_action_control)
    KeyboardAction.ALT -> stringResource(R.string.settings_keyboard_action_alt)
    KeyboardAction.TAB -> stringResource(R.string.settings_keyboard_action_tab)
    KeyboardAction.SHIFT -> stringResource(R.string.settings_keyboard_action_shift)
    KeyboardAction.ARROW_UP -> "↑"
    KeyboardAction.ARROW_DOWN -> "↓"
    KeyboardAction.ARROW_LEFT -> "←"
    KeyboardAction.SHIFT_LEFT -> "⇧←"
    KeyboardAction.ARROW_RIGHT -> "→"
    KeyboardAction.HOME -> stringResource(R.string.settings_keyboard_action_home)
    KeyboardAction.END -> stringResource(R.string.settings_keyboard_action_end)
    KeyboardAction.PAGE_UP -> stringResource(R.string.settings_keyboard_action_page_up)
    KeyboardAction.PAGE_DOWN -> stringResource(R.string.settings_keyboard_action_page_down)
    KeyboardAction.BACKSPACE -> stringResource(R.string.settings_keyboard_action_backspace)
    KeyboardAction.INSERT -> stringResource(R.string.settings_keyboard_action_insert)
    KeyboardAction.DELETE -> stringResource(R.string.settings_keyboard_action_delete)
    KeyboardAction.ENTER -> stringResource(R.string.settings_keyboard_action_enter)
    KeyboardAction.SLASH -> "/"
    KeyboardAction.BACKSLASH -> "\\"
    KeyboardAction.PIPE -> "|"
    KeyboardAction.TILDE -> "~"
    KeyboardAction.BACKTICK -> "`"
    KeyboardAction.HYPHEN -> "-"
    KeyboardAction.UNDERSCORE -> "_"
    KeyboardAction.AT_SIGN -> "@"
    KeyboardAction.F1, KeyboardAction.F2, KeyboardAction.F3, KeyboardAction.F4,
    KeyboardAction.F5, KeyboardAction.F6, KeyboardAction.F7, KeyboardAction.F8,
    KeyboardAction.F9, KeyboardAction.F10, KeyboardAction.F11, KeyboardAction.F12,
    -> action.name
    KeyboardAction.CTRL_C -> "^C"
    KeyboardAction.CTRL_D -> "^D"
    KeyboardAction.CTRL_L -> "^L"
    KeyboardAction.CTRL_R -> "^R"
    KeyboardAction.CTRL_W -> "^W"
    KeyboardAction.CTRL_U -> "^U"
    KeyboardAction.CTRL_A -> "^A"
    KeyboardAction.CTRL_B -> "^B"
    KeyboardAction.CTRL_E -> "^E"
    KeyboardAction.CTRL_K -> "^K"
    KeyboardAction.CTRL_Z -> "^Z"
    KeyboardAction.TMUX_PREFIX -> stringResource(R.string.settings_keyboard_action_tmux)
    KeyboardAction.PASTE -> stringResource(R.string.settings_keyboard_action_paste)
    KeyboardAction.SELECT_IMAGES -> stringResource(R.string.settings_keyboard_action_select_images)
    KeyboardAction.SNIPPETS -> stringResource(R.string.settings_keyboard_action_snippets)
    KeyboardAction.AI_AGENTS -> stringResource(R.string.ai_agents_title)
    KeyboardAction.TMUX_SESSIONS -> stringResource(R.string.settings_keyboard_action_tmux_sessions)
    KeyboardAction.HIDE_KEYBOARD -> stringResource(R.string.settings_keyboard_action_hide)
    KeyboardAction.KEYBOARD_SETTINGS -> stringResource(R.string.settings_keyboard_action_keys)
    KeyboardAction.EXPANDED_KEYBOARD -> stringResource(R.string.terminal_more_keys_label)
    KeyboardAction.COLON -> ":"
    KeyboardAction.SEMICOLON -> ";"
    KeyboardAction.HASH -> "#"
    KeyboardAction.DOLLAR -> "$"
    KeyboardAction.EQUALS -> "="
    KeyboardAction.SPACE -> stringResource(R.string.settings_keyboard_action_space)
    KeyboardAction.EXCLAMATION -> "!"
    KeyboardAction.QUESTION -> "?"
    KeyboardAction.ASTERISK -> "*"
    KeyboardAction.PLUS -> "+"
    KeyboardAction.PERIOD -> "."
    KeyboardAction.COMMA -> ","
    KeyboardAction.LEFT_PAREN -> "("
    KeyboardAction.RIGHT_PAREN -> ")"
    KeyboardAction.LEFT_BRACKET -> "["
    KeyboardAction.RIGHT_BRACKET -> "]"
    KeyboardAction.LEFT_BRACE -> "{"
    KeyboardAction.RIGHT_BRACE -> "}"
    KeyboardAction.SINGLE_QUOTE -> "'"
    KeyboardAction.DOUBLE_QUOTE -> "\""
    KeyboardAction.LESS_THAN -> "<"
    KeyboardAction.GREATER_THAN -> ">"
    KeyboardAction.AMPERSAND -> "&"
    KeyboardAction.CARET -> "^"
    KeyboardAction.PERCENT -> "%"
}

internal const val KeyboardSettingsTestTag = "keyboard-settings"
internal const val KeyboardPreviewTestTag = "keyboard-profile-preview"
