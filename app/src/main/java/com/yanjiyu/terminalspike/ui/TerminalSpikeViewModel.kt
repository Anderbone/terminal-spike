package com.yanjiyu.terminalspike.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.view.TerminalKeySequences
import com.yanjiyu.terminalspike.workload.FullScreenRate
import com.yanjiyu.terminalspike.workload.FullScreenWorkload
import com.yanjiyu.terminalspike.workload.PreloadSize
import com.yanjiyu.terminalspike.workload.StreamingRate
import com.yanjiyu.terminalspike.workload.StreamingWorkload
import com.yanjiyu.terminalspike.workload.WorkloadGenerator
import com.yanjiyu.terminalspike.workload.WorkloadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TerminalSpikeUiState(
    val mode: WorkloadMode = WorkloadMode.STREAM,
    val streamingRate: StreamingRate = StreamingRate.LINES_100,
    val fullScreenRate: FullScreenRate = FullScreenRate.FPS_30,
    val preloadSize: PreloadSize = PreloadSize.LINES_10K,
    val isRunning: Boolean = false,
    val isPreloading: Boolean = false,
    val showPerformance: Boolean = true,
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
)

class TerminalSpikeViewModel : ViewModel() {
    val controller = TerminalController()
    private val _uiState = MutableStateFlow(TerminalSpikeUiState())
    val uiState: StateFlow<TerminalSpikeUiState> = _uiState.asStateFlow()
    private val generator = WorkloadGenerator()
    private val streamingWorkload = StreamingWorkload(generator)
    private val fullScreenWorkload = FullScreenWorkload(generator)
    private var workloadJob: Job? = null
    private var preloadJob: Job? = null
    private var appVisible = true

    fun selectMode(mode: WorkloadMode) {
        if (_uiState.value.mode == mode) return
        val restart = _uiState.value.isRunning
        stopJobs()
        controller.stop()
        controller.setAlternateScreen(mode == WorkloadMode.FULL_SCREEN)
        _uiState.update { it.copy(mode = mode, isRunning = false, isPreloading = false) }
        if (restart) start()
    }

    fun selectStreamingRate(rate: StreamingRate) {
        _uiState.update { it.copy(streamingRate = rate) }
        if (rate == StreamingRate.STOPPED) {
            stop()
        } else if (_uiState.value.isRunning) {
            restartWorkload()
        }
    }

    fun selectFullScreenRate(rate: FullScreenRate) {
        _uiState.update { it.copy(fullScreenRate = rate) }
        if (_uiState.value.isRunning) restartWorkload()
    }

    fun selectPreloadSize(size: PreloadSize) {
        _uiState.update { it.copy(preloadSize = size) }
    }

    fun start() {
        val state = _uiState.value
        if (state.mode == WorkloadMode.STREAM && state.streamingRate == StreamingRate.STOPPED) return
        preloadJob?.cancel()
        controller.setAlternateScreen(state.mode == WorkloadMode.FULL_SCREEN)
        controller.start()
        _uiState.update { it.copy(isRunning = true, isPreloading = false) }
        updateWorkloadDescription()
        if (appVisible) launchWorkload()
    }

    fun stop() {
        stopJobs()
        controller.stop()
        _uiState.update { it.copy(isRunning = false, isPreloading = false) }
        controller.setWorkloadDescription(_uiState.value.mode.label, "Stopped")
    }

    fun clear() {
        preloadJob?.cancel()
        _uiState.update { it.copy(isPreloading = false) }
        controller.clear()
    }

    fun jumpToBottom() = controller.jumpToBottom()

    fun setPerformanceVisible(visible: Boolean) {
        _uiState.update { it.copy(showPerformance = visible) }
    }

    fun preload() {
        val lineCount = _uiState.value.preloadSize.lineCount
        stopJobs()
        controller.stop()
        controller.setAlternateScreen(false)
        controller.start()
        _uiState.update {
            it.copy(mode = WorkloadMode.STREAM, isRunning = false, isPreloading = true)
        }
        controller.setWorkloadDescription("Stream preload", "$lineCount lines")
        preloadJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                var generated = 0
                while (generated < lineCount) {
                    val count = minOf(PRELOAD_BATCH_SIZE, lineCount - generated)
                    val batch = List(count) { offset ->
                        generator.streamingLine((generated + offset).toLong())
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
            _uiState.update { it.copy(isPreloading = false) }
            controller.setWorkloadDescription("Stream", "Preload complete")
        }
    }

    fun toggleCtrl() {
        _uiState.update { it.copy(ctrlArmed = !it.ctrlArmed) }
    }

    fun toggleAlt() {
        _uiState.update { it.copy(altArmed = !it.altArmed) }
    }

    fun sendExtraKey(key: ExtraKey) {
        val state = _uiState.value
        val base = if (state.ctrlArmed) key.controlBytes ?: key.bytes else key.bytes
        val bytes = if (state.altArmed) TerminalKeySequences.ESCAPE + base else base
        controller.send(bytes)
        _uiState.update { it.copy(ctrlArmed = false, altArmed = false) }
    }

    fun onAppVisible(visible: Boolean) {
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
        workloadJob = viewModelScope.launch(Dispatchers.Default) {
            when (state.mode) {
                WorkloadMode.STREAM -> streamingWorkload.run(state.streamingRate, controller::enqueueGeneratedLines)
                WorkloadMode.FULL_SCREEN -> fullScreenWorkload.run(state.fullScreenRate) { screen ->
                    controller.enqueueGeneratedScreen(screen.lines, screen.cursor)
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

    private fun stopJobs() {
        workloadJob?.cancel()
        workloadJob = null
        preloadJob?.cancel()
        preloadJob = null
    }

    override fun onCleared() {
        stopJobs()
        controller.stop()
        super.onCleared()
    }

    companion object {
        private const val PRELOAD_BATCH_SIZE = 1_000
        private const val PRELOAD_BATCH_DELAY_MS = 18L
        private const val PRELOAD_BACKPRESSURE_LINES = 4_000
    }
}

enum class ExtraKey(
    val label: String,
    val bytes: ByteArray,
    val controlBytes: ByteArray? = null,
) {
    ESC("ESC", TerminalKeySequences.ESCAPE),
    TAB("TAB", TerminalKeySequences.TAB),
    UP("↑", TerminalKeySequences.ARROW_UP, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x41)),
    DOWN("↓", TerminalKeySequences.ARROW_DOWN, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x42)),
    LEFT("←", TerminalKeySequences.ARROW_LEFT, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x44)),
    RIGHT("→", TerminalKeySequences.ARROW_RIGHT, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x43)),
    PAGE_UP("PGUP", TerminalKeySequences.PAGE_UP, byteArrayOf(0x1b, 0x5b, 0x35, 0x3b, 0x35, 0x7e)),
    PAGE_DOWN("PGDN", TerminalKeySequences.PAGE_DOWN, byteArrayOf(0x1b, 0x5b, 0x36, 0x3b, 0x35, 0x7e)),
}
