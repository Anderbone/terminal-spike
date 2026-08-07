package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot
import com.yanjiyu.terminalspike.terminal.view.TerminalViewBridge
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSpikeScreen(
    viewModel: TerminalSpikeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val performance by viewModel.controller.performance.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.onAppVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.onAppVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(rootView, state.isRunning, state.isPreloading) {
        rootView.keepScreenOn = state.isRunning || state.isPreloading
        onDispose { rootView.keepScreenOn = false }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Terminal Spike") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TerminalControls(
                state = state,
                autoFollow = performance.autoFollow,
                onModeSelected = viewModel::selectMode,
                onStreamingRateSelected = viewModel::selectStreamingRate,
                onFullScreenRateSelected = viewModel::selectFullScreenRate,
                onPreloadSelected = viewModel::selectPreloadSize,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onPreload = viewModel::preload,
                onClear = viewModel::clear,
                onJumpToBottom = viewModel::jumpToBottom,
                onPerformanceVisible = viewModel::setPerformanceVisible,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                TerminalViewBridge(controller = viewModel.controller)
                if (state.showPerformance) {
                    PerformanceOverlay(
                        snapshot = performance,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    )
                }
            }
            ExtraKeysBar(
                ctrlArmed = state.ctrlArmed,
                altArmed = state.altArmed,
                onCtrl = viewModel::toggleCtrl,
                onAlt = viewModel::toggleAlt,
                onKey = viewModel::sendExtraKey,
            )
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
        "Diagnostic approximation\n%.1f FPS  avg %.2f ms  p95 %.2f ms\n>8.3 ms %d   >16.7 ms %d\nscrollback %,d  visible %d  pending %,d\nheap %.1f MiB  follow %s\n%s · %s",
        timing.fps,
        timing.averageFrameMs,
        timing.p95FrameMs,
        timing.slowerThan8Ms,
        timing.slowerThan16Ms,
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
