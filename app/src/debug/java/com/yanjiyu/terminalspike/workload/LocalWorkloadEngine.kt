package com.yanjiyu.terminalspike.workload

import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.model.TerminalLine

internal fun createLocalWorkloadEngine(): LocalWorkloadEngine = DebugLocalWorkloadEngine()

private class DebugLocalWorkloadEngine : LocalWorkloadEngine {
    private val generator = WorkloadGenerator()
    private val streaming = StreamingWorkload(generator)
    private val fullScreen = FullScreenWorkload(generator)

    override fun streamingLine(sequence: Long): TerminalLine = generator.streamingLine(sequence)

    override suspend fun runStreaming(
        rate: StreamingRate,
        emit: (List<TerminalLine>) -> Unit,
    ) {
        streaming.run(rate, emit)
    }

    override suspend fun runFullScreen(
        rate: FullScreenRate,
        emit: (lines: List<TerminalLine>, cursor: TerminalCursor) -> Unit,
    ) {
        fullScreen.run(rate) { screen -> emit(screen.lines, screen.cursor) }
    }
}
