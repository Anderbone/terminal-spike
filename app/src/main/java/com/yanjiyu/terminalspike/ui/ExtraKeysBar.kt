package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ExtraKeysBar(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keyOrder.forEach { item ->
            when (item) {
                KeyBarItem.Ctrl -> FilterChip(
                    selected = ctrlArmed,
                    onClick = onCtrl,
                    label = { Text("CTRL") },
                    modifier = Modifier.semantics { contentDescription = "One-shot Control modifier" },
                )
                KeyBarItem.Alt -> FilterChip(
                    selected = altArmed,
                    onClick = onAlt,
                    label = { Text("ALT") },
                    modifier = Modifier.semantics { contentDescription = "One-shot Alt modifier" },
                )
                is KeyBarItem.Key -> OutlinedButton(
                    onClick = { onKey(item.key) },
                    modifier = Modifier.semantics { contentDescription = "Terminal key ${item.key.label}" },
                ) {
                    Text(item.key.label)
                }
            }
        }
    }
}

private sealed interface KeyBarItem {
    data object Ctrl : KeyBarItem
    data object Alt : KeyBarItem
    data class Key(val key: ExtraKey) : KeyBarItem
}

private val keyOrder = listOf(
    KeyBarItem.Key(ExtraKey.ESC),
    KeyBarItem.Ctrl,
    KeyBarItem.Alt,
    KeyBarItem.Key(ExtraKey.TAB),
    KeyBarItem.Key(ExtraKey.UP),
    KeyBarItem.Key(ExtraKey.DOWN),
    KeyBarItem.Key(ExtraKey.LEFT),
    KeyBarItem.Key(ExtraKey.RIGHT),
    KeyBarItem.Key(ExtraKey.PAGE_UP),
    KeyBarItem.Key(ExtraKey.PAGE_DOWN),
)
