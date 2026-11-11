package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val TerminalAccessoryColumnGap = 2.dp
internal val TerminalAccessoryRowGap = 4.dp

/** Shared geometry for the live input bar and its visual editor. */
@Composable
internal fun InputKeyboardFrame(
    tokens: List<String>,
    modifier: Modifier = Modifier,
    legacyKeyWidth: Dp? = null,
    key: @Composable (Int, String, Modifier) -> Unit,
    input: @Composable (Int, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        // Use exactly the same column width as the ordinary shortcut keyboard.
        val keyWidth = legacyKeyWidth ?: terminalAccessoryKeyWidth(maxWidth)
        val field = tokens.indexOf("input")
        Row(Modifier.fillMaxSize().padding(start = 3.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(TerminalAccessoryColumnGap), verticalAlignment = Alignment.CenterVertically) {
            val side: @Composable (IntRange) -> Unit = { indices ->
                indices.toList().chunked(2).forEach { column ->
                    Column(Modifier.width(keyWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TerminalAccessoryRowGap)) {
                        column.forEach { index -> key(index, tokens[index], Modifier.height(48.dp).fillMaxWidth()) }
                    }
                }
            }
            side(0 until field)
            input(field, Modifier.weight(1f).fillMaxHeight())
            side(field + 1 until tokens.size)
        }
    }
}

internal fun terminalAccessoryKeyWidth(availableWidth: Dp): Dp {
    val columns = (terminalShortcutColumnCount(availableWidth.value) - 1).coerceAtLeast(1)
    return (availableWidth - 6.dp - TerminalAccessoryColumnGap * columns) / (columns + 1)
}
