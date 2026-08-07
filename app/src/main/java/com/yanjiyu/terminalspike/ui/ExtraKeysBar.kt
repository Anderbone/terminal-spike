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
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey

@Composable
fun ExtraKeysBar(
    keys: List<TerminalExtraKey>,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onKey: (TerminalExtraKey) -> Unit,
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
        keys.forEach { key ->
            when (key) {
                TerminalExtraKey.CTRL -> FilterChip(
                    selected = ctrlArmed,
                    onClick = { onKey(key) },
                    label = { Text("CTRL") },
                    modifier = Modifier.semantics { contentDescription = "One-shot Control modifier" },
                )
                TerminalExtraKey.ALT -> FilterChip(
                    selected = altArmed,
                    onClick = { onKey(key) },
                    label = { Text("ALT") },
                    modifier = Modifier.semantics { contentDescription = "One-shot Alt modifier" },
                )
                else -> OutlinedButton(
                    onClick = { onKey(key) },
                    modifier = Modifier.semantics { contentDescription = "Terminal key ${key.label}" },
                ) {
                    Text(key.label)
                }
            }
        }
    }
}
