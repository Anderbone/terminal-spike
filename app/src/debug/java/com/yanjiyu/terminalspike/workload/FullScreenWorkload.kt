package com.yanjiyu.terminalspike.workload

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class FullScreenWorkload(
    private val generator: WorkloadGenerator,
) {
    suspend fun run(
        rate: FullScreenRate,
        emit: (GeneratedScreen) -> Unit,
    ) {
        var update = 0L
        val delayMillis = (1_000L / rate.updatesPerSecond).coerceAtLeast(1L)
        while (currentCoroutineContext().isActive) {
            emit(generator.fullScreen(update++))
            delay(delayMillis)
        }
    }
}
