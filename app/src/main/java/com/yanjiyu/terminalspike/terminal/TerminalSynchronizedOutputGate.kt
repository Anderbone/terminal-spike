package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate

/** A missing ESU must neither freeze a session nor accumulate unlimited pending history. */
internal class TerminalSynchronizedOutputGate {
    private var generation: Long? = null
    private var deadlineMillis = 0L
    private var expiredGeneration: Long? = null

    fun delayMillis(frame: TerminalFrameUpdate?, nowMillis: Long, pendingRows: Int): Long {
        if (frame?.synchronizedOutput != true) {
            generation = null
            return 0L
        }
        if (frame.synchronizedOutputGeneration == expiredGeneration) return 0L
        if (generation != frame.synchronizedOutputGeneration) {
            // Several frames can finish and a new one can start in one transport chunk. Changing
            // generations must not keep extending a continuously withheld presentation.
            if (generation == null) deadlineMillis = nowMillis + 1_000L
            generation = frame.synchronizedOutputGeneration
        }
        if (pendingRows >= 4_000 || nowMillis >= deadlineMillis) {
            expiredGeneration = frame.synchronizedOutputGeneration
            return 0L
        }
        return deadlineMillis - nowMillis
    }

    fun onFramePublished() {
        generation = null
    }
}
