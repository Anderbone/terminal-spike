package com.yanjiyu.terminalspike.terminal.benchmark

import android.view.Choreographer
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFrameScheduler
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.security.MessageDigest

internal data class TerminalPipelineBenchmarkResult(
    val inputBytes: Long,
    val inputChunks: Int,
    val inputSha256: String,
    val completedScrollbackLines: Long,
    val parserDirtyRows: Long,
    val controllerFrames: Int,
    val controllerLineCount: Int,
    val transcriptSha256: String,
    val cursorRow: Int,
    val cursorColumn: Int,
    val alternateScreen: Boolean,
)

/**
 * Replays project-authored fixture bytes without clocks, sleeps, network, or Android frame timing.
 * Macrobenchmarks own duration measurement; this helper owns deterministic parser/controller work.
 */
internal object TerminalParserControllerBenchmarkHarness {
    fun replay(
        spec: TerminalFixtureSpec,
        chunkBytes: Int = spec.chunkBytes,
        controllerDrainEveryChunks: Int = 8,
    ): TerminalPipelineBenchmarkResult {
        require(controllerDrainEveryChunks > 0)
        val engine = VtTerminalEngine(columns = spec.columns, rows = spec.rows)
        val scheduler = ManualTerminalFrameScheduler()
        val controller = TerminalController(
            buffer = TerminalBuffer(capacity = MAX_REPLAY_SCROLLBACK),
            frameScheduler = scheduler,
        )
        val inputDigest = MessageDigest.getInstance("SHA-256")
        var inputBytes = 0L
        var inputChunks = 0
        var completedLines = 0L
        var dirtyRows = 0L
        var lastFrame: TerminalFrameUpdate = engine.accept(ByteArray(0))

        TerminalBenchmarkFixtureGenerator.chunks(spec, chunkBytes).forEach { chunk ->
            inputDigest.update(chunk)
            inputBytes += chunk.size
            inputChunks += 1
            lastFrame = engine.accept(chunk)
            completedLines += lastFrame.completedScrollback.size
            dirtyRows += lastFrame.dirtyRows.size
            controller.updateTerminalFrame(lastFrame)
            if (inputChunks % controllerDrainEveryChunks == 0) scheduler.drainAll()
        }
        scheduler.drainAll()

        return TerminalPipelineBenchmarkResult(
            inputBytes = inputBytes,
            inputChunks = inputChunks,
            inputSha256 = inputDigest.digest().toHex(),
            completedScrollbackLines = completedLines,
            parserDirtyRows = dirtyRows,
            controllerFrames = scheduler.frames,
            controllerLineCount = controller.lineCount(),
            transcriptSha256 = transcriptDigest(controller.transcriptSnapshot()),
            cursorRow = lastFrame.cursor.row,
            cursorColumn = lastFrame.cursor.column,
            alternateScreen = lastFrame.alternateScreen,
        )
    }

    private fun transcriptDigest(lines: Iterable<TerminalLine>): String =
        MessageDigest.getInstance("SHA-256").run {
            lines.forEach { line ->
                update((if (line.softWrappedToNext) 1 else 0).toByte())
                line.runs.forEach { run ->
                    update(run.text.toByteArray(Charsets.UTF_8))
                    update(0.toByte())
                    update(run.style.toString().toByteArray(Charsets.UTF_8))
                    update(0.toByte())
                    update(run.startColumn.toString().toByteArray(Charsets.US_ASCII))
                    update(0.toByte())
                    update(run.columnWidth.toString().toByteArray(Charsets.US_ASCII))
                    update(0.toByte())
                    run.hyperlink?.let { hyperlink ->
                        update(hyperlink.uri.toByteArray(Charsets.UTF_8))
                    }
                    update(0xff.toByte())
                }
                update('\n'.code.toByte())
            }
            digest().toHex()
        }

    private const val MAX_REPLAY_SCROLLBACK = 100_000
}

private class ManualTerminalFrameScheduler : TerminalFrameScheduler {
    private val callbacks = ArrayDeque<Choreographer.FrameCallback>()
    var frames: Int = 0
        private set

    override fun postFrame(callback: Choreographer.FrameCallback) {
        callbacks.addLast(callback)
    }

    fun drainAll() {
        var remainingGuard = MAX_DRAINS
        while (callbacks.isNotEmpty()) {
            check(remainingGuard-- > 0) { "Controller did not quiesce within $MAX_DRAINS frames" }
            frames += 1
            callbacks.removeFirst().doFrame(frames * FRAME_INTERVAL_NANOS)
        }
    }

    companion object {
        private const val MAX_DRAINS = 100_000
        private const val FRAME_INTERVAL_NANOS = 16_666_667L
    }
}
