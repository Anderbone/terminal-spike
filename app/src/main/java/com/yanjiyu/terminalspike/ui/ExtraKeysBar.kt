package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey

private sealed interface ExtraKeyCell {
    data class Key(val key: TerminalExtraKey) : ExtraKeyCell
    data object Edit : ExtraKeyCell
}

@Composable
fun ExtraKeysBar(
    keys: List<TerminalExtraKey>,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    customizationEnabled: Boolean,
    onKey: (TerminalExtraKey) -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = keys.map(ExtraKeyCell::Key) + ExtraKeyCell.Edit
    val firstRowSize = (cells.size + 1) / 2

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExtraKeyRow(
                cells = cells.take(firstRowSize),
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                customizationEnabled = customizationEnabled,
                onKey = onKey,
                onCustomize = onCustomize,
            )
            ExtraKeyRow(
                cells = cells.drop(firstRowSize),
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                customizationEnabled = customizationEnabled,
                onKey = onKey,
                onCustomize = onCustomize,
            )
        }
    }
}

@Composable
private fun ExtraKeyRow(
    cells: List<ExtraKeyCell>,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    customizationEnabled: Boolean,
    onKey: (TerminalExtraKey) -> Unit,
    onCustomize: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEach { cell ->
            when (cell) {
                is ExtraKeyCell.Key -> ExtraKeyButton(
                    label = cell.key.label,
                    selected = when (cell.key) {
                        TerminalExtraKey.CTRL -> ctrlArmed
                        TerminalExtraKey.ALT -> altArmed
                        else -> false
                    },
                    enabled = true,
                    description = if (cell.key.isModifier) {
                        "One-shot ${cell.key.label} modifier"
                    } else {
                        "Terminal key ${cell.key.label}"
                    },
                    onClick = { onKey(cell.key) },
                )
                ExtraKeyCell.Edit -> ExtraKeyButton(
                    label = "EDIT",
                    selected = false,
                    enabled = customizationEnabled,
                    description = "Customize terminal keys",
                    onClick = onCustomize,
                )
            }
        }
    }
}

@Composable
private fun RowScope.ExtraKeyButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(7.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = when {
                        label.length >= 7 -> 8.sp
                        label.length >= 6 -> 9.sp
                        label.length >= 5 -> 10.sp
                        else -> MaterialTheme.typography.labelMedium.fontSize
                    },
                ),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
