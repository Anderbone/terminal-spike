package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared geometry for the live input bar and its visual editor. */
@Composable
internal fun InputKeyboardFrame(
    tokens: List<String>,
    modifier: Modifier = Modifier,
    legacyKeyWidth: Dp? = null,
    legacyLayout: Boolean = false,
    key: @Composable (Int, String, Modifier) -> Unit,
    input: @Composable (Int, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        // Four columns is the widest six-key arrangement when split across both sides.
        val columns = (terminalShortcutColumnCount(maxWidth.value) - 1).coerceAtLeast(1)
        val keyWidth = legacyKeyWidth ?: if (legacyLayout) {
            (maxWidth - 6.dp - 2.dp * columns) / (columns + 1)
        } else ((maxWidth - 171.dp) / 4).coerceIn(24.dp, 48.dp)
        val field = tokens.indexOf("input")
        Row(Modifier.fillMaxSize().padding(start = 3.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val side: @Composable (IntRange) -> Unit = { indices ->
                indices.toList().chunked(2).forEach { column ->
                    Column(Modifier.width(keyWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        column.forEach { index -> key(index, tokens[index], Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
            side(0 until field)
            input(field, Modifier.weight(1f).fillMaxHeight())
            side(field + 1 until tokens.size)
        }
    }
}
