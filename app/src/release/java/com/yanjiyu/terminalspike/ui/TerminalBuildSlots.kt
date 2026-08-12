package com.yanjiyu.terminalspike.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot

@Composable
internal fun TerminalBuildTopSlot(
    state: TerminalSpikeUiState,
    autoFollow: Boolean,
    feature: TerminalBuildFeature,
) = Unit

@Composable
internal fun TerminalBuildCanvasSlot(
    state: TerminalSpikeUiState,
    snapshot: PerformanceSnapshot,
    feature: TerminalBuildFeature,
    modifier: Modifier = Modifier,
) = Unit
