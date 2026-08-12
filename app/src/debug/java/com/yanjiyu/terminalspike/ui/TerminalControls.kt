package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot
import com.yanjiyu.terminalspike.workload.FullScreenRate
import com.yanjiyu.terminalspike.workload.PreloadSize
import com.yanjiyu.terminalspike.workload.StreamingRate
import com.yanjiyu.terminalspike.workload.WorkloadMode
import java.util.Locale

@Composable
internal fun TerminalBuildTopSlot(
    state: TerminalSpikeUiState,
    autoFollow: Boolean,
    feature: TerminalBuildFeature,
) {
    if (state.sshMode) return
    val debugFeature = feature as? DebugTerminalBuildFeature ?: return
    val buildState by debugFeature.uiState.collectAsStateWithLifecycle()
    TerminalControls(
        state = buildState,
        autoFollow = autoFollow,
        onModeSelected = debugFeature::selectMode,
        onStreamingRateSelected = debugFeature::selectStreamingRate,
        onFullScreenRateSelected = debugFeature::selectFullScreenRate,
        onPreloadSelected = debugFeature::selectPreloadSize,
        onStart = debugFeature::start,
        onStop = debugFeature::stop,
        onPreload = debugFeature::preload,
        onClear = debugFeature::clear,
        onJumpToBottom = debugFeature::jumpToBottom,
        onPerformanceVisible = debugFeature::setPerformanceVisible,
    )
}

@Composable
internal fun TerminalBuildCanvasSlot(
    state: TerminalSpikeUiState,
    snapshot: PerformanceSnapshot,
    feature: TerminalBuildFeature,
    modifier: Modifier = Modifier,
) {
    if (state.sshMode) return
    val debugFeature = feature as? DebugTerminalBuildFeature ?: return
    val buildState by debugFeature.uiState.collectAsStateWithLifecycle()
    if (!buildState.showPerformance) return
    PerformanceOverlay(snapshot = snapshot, modifier = modifier)
}

@Composable
private fun TerminalControls(
    state: TerminalBuildUiState,
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

@Composable
private fun PerformanceOverlay(
    snapshot: PerformanceSnapshot,
    modifier: Modifier = Modifier,
) {
    val timing = snapshot.timing
    val text = String.format(
        Locale.US,
        "Renderer %s\n%.1f draws/s  avg %.2f ms  p95 %.2f ms\nCPU >8.3 ms %d   >16.7 ms %d\nscrollback %,d  visible %d  pending %,d\nheap %.1f MiB  follow %s\n%s · %s",
        if (timing.idle) "idle" else "diagnostic",
        timing.drawsPerSecond,
        timing.averageDrawMs,
        timing.p95DrawMs,
        timing.drawsSlowerThan8Ms,
        timing.drawsSlowerThan16Ms,
        snapshot.scrollbackLines,
        snapshot.visibleLines,
        snapshot.pendingOutputLines,
        snapshot.heapMegabytes,
        if (snapshot.autoFollow) "on" else "off",
        snapshot.workload,
        snapshot.rate,
    )
    Surface(
        modifier = modifier.widthIn(max = 390.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
