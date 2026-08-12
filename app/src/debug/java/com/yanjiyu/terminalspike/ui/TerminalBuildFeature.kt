package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.workload.FullScreenRate
import com.yanjiyu.terminalspike.workload.PreloadSize
import com.yanjiyu.terminalspike.workload.StreamingRate
import com.yanjiyu.terminalspike.workload.WorkloadMode
import com.yanjiyu.terminalspike.workload.createLocalWorkloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TerminalBuildUiState(
    val mode: WorkloadMode = WorkloadMode.STREAM,
    val streamingRate: StreamingRate = StreamingRate.LINES_100,
    val fullScreenRate: FullScreenRate = FullScreenRate.FPS_30,
    val preloadSize: PreloadSize = PreloadSize.LINES_10K,
    val isRunning: Boolean = false,
    val isPreloading: Boolean = false,
    val showPerformance: Boolean = true,
)

internal class DebugTerminalBuildFeature(
    private val scope: CoroutineScope,
    private val controller: TerminalController,
) : TerminalBuildFeature {
    private val engine = createLocalWorkloadEngine()
    private val _uiState = MutableStateFlow(TerminalBuildUiState())
    val uiState: StateFlow<TerminalBuildUiState> = _uiState.asStateFlow()
    private val _keepScreenOn = MutableStateFlow(false)
    override val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    private var workloadJob: Job? = null
    private var preloadJob: Job? = null
    private var appVisible = true

    fun selectMode(mode: WorkloadMode) {
        if (_uiState.value.mode == mode) return
        val restart = _uiState.value.isRunning
        cancelJobs()
        controller.stop()
        controller.setAlternateScreen(mode == WorkloadMode.FULL_SCREEN)
        updateState { it.copy(mode = mode, isRunning = false, isPreloading = false) }
        if (restart) start()
    }

    fun selectStreamingRate(rate: StreamingRate) {
        updateState { it.copy(streamingRate = rate) }
        if (rate == StreamingRate.STOPPED) {
            stop()
        } else if (_uiState.value.isRunning) {
            restartWorkload()
        }
    }

    fun selectFullScreenRate(rate: FullScreenRate) {
        updateState { it.copy(fullScreenRate = rate) }
        if (_uiState.value.isRunning) restartWorkload()
    }

    fun selectPreloadSize(size: PreloadSize) {
        updateState { it.copy(preloadSize = size) }
    }

    fun start() {
        val state = _uiState.value
        if (state.mode == WorkloadMode.STREAM && state.streamingRate == StreamingRate.STOPPED) return
        preloadJob?.cancel()
        controller.setAlternateScreen(state.mode == WorkloadMode.FULL_SCREEN)
        controller.start()
        updateState { it.copy(isRunning = true, isPreloading = false) }
        updateWorkloadDescription()
        if (appVisible) launchWorkload()
    }

    override fun stop() {
        cancelJobs()
        controller.stop()
        updateState { it.copy(isRunning = false, isPreloading = false) }
        controller.setWorkloadDescription(_uiState.value.mode.label, "Stopped")
    }

    fun clear() {
        preloadJob?.cancel()
        updateState { it.copy(isPreloading = false) }
        controller.clear()
    }

    fun jumpToBottom() {
        val wasAutoFollowing = controller.viewport.autoFollow
        controller.jumpToBottom()
        controller.reportViewportStateIfChanged(wasAutoFollowing)
    }

    fun setPerformanceVisible(visible: Boolean) {
        updateState { it.copy(showPerformance = visible) }
    }

    fun preload() {
        val lineCount = _uiState.value.preloadSize.lineCount
        cancelJobs()
        controller.stop()
        controller.setAlternateScreen(false)
        controller.start()
        updateState {
            it.copy(mode = WorkloadMode.STREAM, isRunning = false, isPreloading = true)
        }
        controller.setWorkloadDescription("Stream preload", "$lineCount lines")
        preloadJob = scope.launch {
            withContext(Dispatchers.Default) {
                var generated = 0
                while (generated < lineCount) {
                    val count = minOf(PRELOAD_BATCH_SIZE, lineCount - generated)
                    val batch = List(count) { offset ->
                        engine.streamingLine((generated + offset).toLong())
                    }
                    while (controller.pendingLineCount() > PRELOAD_BACKPRESSURE_LINES) {
                        delay(PRELOAD_BATCH_DELAY_MS)
                    }
                    controller.enqueueGeneratedLines(batch)
                    generated += count
                    delay(PRELOAD_BATCH_DELAY_MS)
                }
                while (controller.pendingLineCount() > 0) delay(PRELOAD_BATCH_DELAY_MS)
            }
            controller.stop()
            updateState { it.copy(isPreloading = false) }
            controller.setWorkloadDescription("Stream", "Preload complete")
        }
    }

    override fun onAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        if (!visible) {
            workloadJob?.cancel()
            workloadJob = null
            controller.pause()
        } else if (_uiState.value.isRunning) {
            controller.resume()
            launchWorkload()
        }
    }

    private fun restartWorkload() {
        workloadJob?.cancel()
        workloadJob = null
        controller.stop()
        controller.start()
        updateWorkloadDescription()
        if (appVisible) launchWorkload()
    }

    private fun launchWorkload() {
        workloadJob?.cancel()
        val state = _uiState.value
        workloadJob = scope.launch(Dispatchers.Default) {
            when (state.mode) {
                WorkloadMode.STREAM -> engine.runStreaming(state.streamingRate, controller::enqueueGeneratedLines)
                WorkloadMode.FULL_SCREEN -> engine.runFullScreen(state.fullScreenRate) { lines, cursor ->
                    controller.enqueueGeneratedScreen(lines, cursor)
                }
            }
        }
    }

    private fun updateWorkloadDescription() {
        val state = _uiState.value
        val rate = when (state.mode) {
            WorkloadMode.STREAM -> state.streamingRate.label
            WorkloadMode.FULL_SCREEN -> state.fullScreenRate.label
        }
        controller.setWorkloadDescription(state.mode.label, rate)
    }

    private fun cancelJobs() {
        workloadJob?.cancel()
        workloadJob = null
        preloadJob?.cancel()
        preloadJob = null
    }

    private inline fun updateState(transform: (TerminalBuildUiState) -> TerminalBuildUiState) {
        _uiState.update(transform)
        _keepScreenOn.value = _uiState.value.run { isRunning || isPreloading }
    }

    private companion object {
        const val PRELOAD_BATCH_SIZE = 1_000
        const val PRELOAD_BATCH_DELAY_MS = 18L
        const val PRELOAD_BACKPRESSURE_LINES = 4_000
    }
}

internal fun createTerminalBuildFeature(
    scope: CoroutineScope,
    controller: TerminalController,
): TerminalBuildFeature = DebugTerminalBuildFeature(scope, controller)

internal fun initialTerminalTabs(): List<SessionTabUi> = listOf(
    SessionTabUi(
        id = LOCAL_TERMINAL_SESSION_ID,
        title = "Bench",
        connectionState = ConnectionState.Disconnected,
        isLocalTerminal = true,
    ),
)
