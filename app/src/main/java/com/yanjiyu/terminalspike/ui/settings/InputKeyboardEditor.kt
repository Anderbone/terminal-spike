package com.yanjiyu.terminalspike.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.InputKeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.ui.ExtraKeyButton
import com.yanjiyu.terminalspike.ui.resolve
import com.yanjiyu.terminalspike.ui.InputKeyboardFrame
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph

@Composable
internal fun InputKeyboardEditor(value: String, onRight: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val defaults = InputKeyboardLayout.defaultTokens(onRight)
    var tokens by remember(value, onRight) { mutableStateOf(InputKeyboardLayout.parse(value) ?: defaults) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var insertion by remember { mutableStateOf<Int?>(null) }
    var search by remember { mutableStateOf("") }
    var legacy by remember(value) { mutableStateOf(value.isEmpty()) }
    BackHandler(onBack = onDismiss)
    Column(Modifier.fillMaxSize().testTag("input-keyboard-editor")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onSave(if (legacy) "" else tokens.joinToString(",")) }) { Text(stringResource(R.string.save)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_input_keyboard_edit), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp))
            Text(stringResource(R.string.settings_input_keyboard_summary), modifier = Modifier.padding(horizontal = 16.dp))
            InputKeyboardPreview(tokens, selected, onSelect = { selected = it; insertion = null }, onMove = { from, to ->
                legacy = false
                tokens = tokens.toMutableList().apply { add(to, removeAt(from)) }
                selected = to
                insertion = null
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(enabled = tokens.size <= InputKeyboardLayout.MAX_KEYS, onClick = {
                    insertion = tokens.indexOf("input"); selected = null
                }, modifier = Modifier.testTag("input-keyboard-add-left")) { Text(stringResource(R.string.settings_input_keyboard_add_left)) }
                OutlinedButton(enabled = tokens.size <= InputKeyboardLayout.MAX_KEYS, onClick = {
                    insertion = tokens.size; selected = null
                }, modifier = Modifier.testTag("input-keyboard-add-right")) { Text(stringResource(R.string.settings_input_keyboard_add_right)) }
            }
            val current = selected?.let(tokens::getOrNull)
            if (current != null) {
                Text(inputKeyboardTokenLabel(current), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp))
                if (current != "input") TextButton(
                    enabled = current != "toggle" || tokens.count { it == "toggle" } > 1,
                    onClick = {
                        legacy = false
                        tokens = tokens.toMutableList().apply { removeAt(requireNotNull(selected)) }
                        selected = null
                    }, modifier = Modifier.testTag("input-keyboard-remove"),
                ) { Text(stringResource(R.string.settings_input_keyboard_remove)) }
            }
            if (insertion != null || (current != null && current != "input")) {
                val canReplaceSwitch = current != "toggle" || tokens.count { it == "toggle" } > 1
                OutlinedTextField(search, { search = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_add_key)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                val choices = listOf("toggle") + runtimeSupportedKeyboardActions.map { it.wireCode }
                choices.forEach { token ->
                    val label = inputKeyboardTokenLabel(token)
                    if ((token == "toggle" || canReplaceSwitch) && label.contains(search.trim(), ignoreCase = true)) {
                        TextButton(onClick = {
                            legacy = false
                            tokens = tokens.toMutableList().apply {
                                val at = insertion
                                if (at != null) add(at, token) else set(requireNotNull(selected), token)
                            }
                            selected = null; insertion = null
                        }, modifier = Modifier.fillMaxWidth().testTag("input-keyboard-choice-$token")) { Text(label) }
                    }
                }
            }
            TextButton(onClick = { legacy = true; tokens = defaults; selected = null; insertion = null },
                modifier = Modifier.testTag("input-keyboard-reset")) { Text(stringResource(R.string.settings_input_keyboard_reset)) }
        }
    }
}

@Composable
internal fun inputKeyboardTokenLabel(token: String): String = when (token) {
    "input" -> stringResource(R.string.settings_input_keyboard_field)
    "toggle" -> stringResource(R.string.terminal_typing_toggle)
    "enter" -> stringResource(R.string.terminal_enter_short)
    else -> KeyboardAction.entries.first { it.wireCode == token }.let {
        toInputAccessoryAction(it)?.label?.resolve() ?: keyboardActionLabel(it)
    }
}

/** Uses the same measured frame and button renderer as the terminal, including two-row columns. */
@Composable
internal fun InputKeyboardPreview(tokens: List<String>, selected: Int?, onSelect: (Int) -> Unit, onMove: (Int, Int) -> Unit) {
    val bounds = remember { mutableMapOf<Int, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentMove by rememberUpdatedState(onMove)
    val currentTokens by rememberUpdatedState(tokens)
    val before = stringResource(R.string.settings_keyboard_move_before)
    val after = stringResource(R.string.settings_keyboard_move_after)
    val shape = MaterialTheme.shapes.extraSmall
    val selectedColor = MaterialTheme.colorScheme.primary
    fun itemModifier(index: Int, modifier: Modifier): Modifier = modifier
        .testTag("input-keyboard-item-$index")
        .onGloballyPositioned { bounds[index] = it.boundsInRoot() }
        .then(if (selected == index) Modifier.border(2.dp, selectedColor, shape) else Modifier)
        .semantics {
            customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction(before) { onMove(index, index - 1); true })
                if (index < tokens.lastIndex) add(CustomAccessibilityAction(after) { onMove(index, index + 1); true })
            }
        }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        InputKeyboardFrame(tokens, Modifier.fillMaxWidth().height(106.dp).testTag("input-keyboard-preview")
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) {
                var dragged: Int? = null
                var position = Offset.Zero
                var previousDestination: Rect? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        previousDestination = null
                        position = point + origin
                        dragged = bounds.entries.firstOrNull { it.key < currentTokens.size && it.value.contains(position) }?.key
                    },
                    onDragEnd = { dragged = null }, onDragCancel = { dragged = null },
                    onDrag = { change, amount ->
                        change.consume(); position += amount
                        // Reflow can put the field back under the finger after crossing it.
                        // Keep that insertion until the finger leaves the old target bounds.
                        if (previousDestination?.contains(position) == true) return@detectDragGesturesAfterLongPress
                        previousDestination = null
                        val from = dragged
                        val to = bounds.entries.firstOrNull { it.key < currentTokens.size && it.value.contains(position) }?.key
                        if (from != null && to != null && from != to) {
                            previousDestination = bounds[to]
                            currentMove(from, to); dragged = to
                        }
                    },
                )
            }, key = { index, token, modifier ->
                ExtraKeyButton(label = inputKeyboardTokenLabel(token), description = inputKeyboardTokenLabel(token),
                    glyph = if (token == "toggle") ConnectionsGlyph.WRITE else null,
                    onClick = { onSelect(index) }, modifier = itemModifier(index, modifier))
            }, input = { index, modifier ->
                Surface(onClick = { onSelect(index) }, modifier = itemModifier(index, modifier), shape = shape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Text(stringResource(R.string.terminal_buffered_input_placeholder),
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
    }
}
