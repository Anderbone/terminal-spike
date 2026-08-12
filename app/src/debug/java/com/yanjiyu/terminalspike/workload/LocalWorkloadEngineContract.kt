package com.yanjiyu.terminalspike.workload

import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.model.TerminalLine

internal interface LocalWorkloadEngine {
    fun streamingLine(sequence: Long): TerminalLine

    suspend fun runStreaming(
        rate: StreamingRate,
        emit: (List<TerminalLine>) -> Unit,
    )

    suspend fun runFullScreen(
        rate: FullScreenRate,
        emit: (lines: List<TerminalLine>, cursor: TerminalCursor) -> Unit,
    )
}
