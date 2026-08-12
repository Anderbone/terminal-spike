package com.yanjiyu.terminalspike.workload

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class StreamingWorkload(
    private val generator: WorkloadGenerator,
) {
    suspend fun run(
        rate: StreamingRate,
        emit: (List<TerminalLine>) -> Unit,
    ) {
        if (rate.linesPerSecond == 0) return
        var sequence = 0L
        var lineRemainder = 0.0
        while (currentCoroutineContext().isActive) {
            lineRemainder += rate.linesPerSecond * TICK_MILLIS / 1_000.0
            val batchSize = lineRemainder.toInt()
            lineRemainder -= batchSize
            if (batchSize > 0) {
                val batch = ArrayList<TerminalLine>(batchSize)
                repeat(batchSize) {
                    batch += generator.streamingLine(sequence++)
                }
                emit(batch)
            }
            delay(TICK_MILLIS)
        }
    }

    companion object {
        private const val TICK_MILLIS = 16L
    }
}
