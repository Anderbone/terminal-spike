package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.workload.FullScreenRate
import com.yanjiyu.terminalspike.workload.PreloadSize
import com.yanjiyu.terminalspike.workload.StreamingRate
import com.yanjiyu.terminalspike.workload.WorkloadMode

@Composable
fun TerminalControls(
    state: TerminalSpikeUiState,
    autoFollow: Boolean,
    onModeSelected: (WorkloadMode) -> Unit,
    onStreamingRateSelected: (StreamingRate) -> Unit,
    onFullScreenRateSelected: (FullScreenRate) -> Unit,
    onPreloadSelected: (PreloadSize) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPreload: () -> Unit,
    onClear: () -> Unit,
    onJumpToBottom: () -> Unit,
    onPerformanceVisible: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkloadMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { onModeSelected(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.semantics { contentDescription = "${mode.label} workload" },
                )
            }
            if (state.mode == WorkloadMode.STREAM) {
                SelectorButton(
                    label = state.streamingRate.label,
                    options = StreamingRate.entries,
                    optionLabel = StreamingRate::label,
                    onSelected = onStreamingRateSelected,
                    accessibilityLabel = "Streaming output rate",
                )
            } else {
                SelectorButton(
                    label = state.fullScreenRate.label,
                    options = FullScreenRate.entries,
                    optionLabel = FullScreenRate::label,
                    onSelected = onFullScreenRateSelected,
                    accessibilityLabel = "Full screen update rate",
                )
            }
            if (state.isRunning) {
                Button(onClick = onStop, modifier = Modifier.heightIn(min = 36.dp)) { Text("Stop") }
            } else {
                Button(onClick = onStart, enabled = !state.isPreloading, modifier = Modifier.heightIn(min = 36.dp)) {
                    Text("Start")
                }
            }
            Text(
                text = if (autoFollow) "● Following" else "● Scroll held",
                color = if (autoFollow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectorButton(
                label = "Preload ${state.preloadSize.label}",
                options = PreloadSize.entries,
                optionLabel = { "${it.label} lines" },
                onSelected = onPreloadSelected,
                accessibilityLabel = "Preload line count",
            )
            OutlinedButton(onClick = onPreload, enabled = !state.isPreloading) {
                Text(if (state.isPreloading) "Loading…" else "Preload")
            }
            TextButton(onClick = onClear) { Text("Clear") }
            TextButton(onClick = onJumpToBottom) { Text("Jump to bottom") }
            Text("Stats", style = MaterialTheme.typography.labelMedium)
            Switch(
                checked = state.showPerformance,
                onCheckedChange = onPerformanceVisible,
                modifier = Modifier.semantics { contentDescription = "Performance overlay" },
            )
        }
    }
}

@Composable
private fun <T> SelectorButton(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    accessibilityLabel: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .heightIn(min = 36.dp)
                .semantics { contentDescription = accessibilityLabel },
        ) {
            Text("$label ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}
