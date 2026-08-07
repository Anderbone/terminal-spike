package com.yanjiyu.terminalspike.terminal

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.yanjiyu.terminalspike.performance.FrameTimingSnapshot
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.TerminalModes
import java.util.concurrent.CopyOnWriteArraySet
import com.yanjiyu.terminalspike.terminal.view.TerminalMouseSequences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface TerminalContentListener {
    fun onTerminalContentChanged()
}

data class TerminalCursor(
    val row: Int = 0,
    val column: Int = 0,
    val visible: Boolean = false,
)

class TerminalController(
    val buffer: TerminalBuffer = TerminalBuffer(),
) : TerminalRendererController, TerminalInputSink {
    val viewport = TerminalViewport()
    var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<TerminalContentListener>()
    private val queueLock = Any()
    private val outputQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val primaryTerminalScrollbackQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val alternateTerminalScrollbackQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val alternateScrollback = TerminalBuffer(MAX_ALTERNATE_SCROLLBACK_LINES)
    private var pendingScreen: List<TerminalLine>? = null
    private var pendingLiveLine: TerminalLine? = null
    private var hasPendingLiveLine = false
    private var pendingTerminalFrame: TerminalFrameUpdate? = null
    private var frameScheduled = false
    private var acceptingOutput = false
    private var paused = false
    private var alternateScreen = false
    private var alternateLines: List<TerminalLine> = emptyList()
    private var workloadName = "Stream"
    private var workloadRate = "Stopped"
    private var frameTiming = FrameTimingSnapshot()
    private var liveLine: TerminalLine? = null
    private var terminalScreen: List<TerminalLine>? = null
    private var terminalScreenIsAlternate = false
    private var terminalCursor = TerminalCursor()
    private var terminalModes = TerminalModes()
    private val _performance = MutableStateFlow(PerformanceSnapshot())
    val performance: StateFlow<PerformanceSnapshot> = _performance.asStateFlow()
    private val fakeInputSession = FakeTerminalSession { line -> append(listOf(line)) }

    @Volatile
    private var inputSink: TerminalInputSink = fakeInputSession

    @Volatile
    private var terminalSizeListener: ((Int, Int) -> Unit)? = null

    @Volatile
    var terminalColumns: Int = DEFAULT_TERMINAL_COLUMNS
        private set

    @Volatile
    var terminalRows: Int = DEFAULT_TERMINAL_ROWS
        private set

    @Volatile
    var cursor: TerminalCursor = TerminalCursor()
        private set

    override fun send(bytes: ByteArray) {
        inputSink.send(applicationCursorSequence(bytes) ?: bytes)
    }

    fun sendPaste(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (terminalModes.bracketedPaste) {
            inputSink.send(BRACKETED_PASTE_START + bytes + BRACKETED_PASTE_END)
        } else {
            inputSink.send(bytes)
        }
    }

    fun reportFocus(focused: Boolean) {
        if (terminalModes.focusReporting) inputSink.send(if (focused) FOCUS_IN else FOCUS_OUT)
    }

    fun sendMouseWheel(up: Boolean, column: Int, row: Int): Boolean {
        if (!terminalModes.mouseTracking) return false
        inputSink.send(
            TerminalMouseSequences.wheel(
                up = up,
                column = column.coerceIn(0, terminalColumns - 1),
                row = row.coerceIn(0, terminalRows - 1),
                sgrEncoding = terminalModes.sgrMouseEncoding,
            ),
        )
        return true
    }

    fun isMouseTrackingEnabled(): Boolean = terminalModes.mouseTracking

    fun setInputSink(sink: TerminalInputSink, onResize: (Int, Int) -> Unit) {
        inputSink = sink
        terminalSizeListener = onResize
    }

    fun resetInputSink() {
        inputSink = fakeInputSession
        terminalSizeListener = null
    }

    fun reportTerminalSize(columns: Int, rows: Int) {
        val boundedColumns = columns.coerceAtLeast(1)
        val boundedRows = rows.coerceAtLeast(1)
        if (boundedColumns == terminalColumns && boundedRows == terminalRows) return
        terminalColumns = boundedColumns
        terminalRows = boundedRows
        terminalSizeListener?.invoke(boundedColumns, boundedRows)
    }

    fun updateSessionOutput(completedLines: List<TerminalLine>, currentLine: TerminalLine) {
        synchronized(queueLock) {
            outputQueue.addAll(completedLines)
            pendingLiveLine = currentLine
            hasPendingLiveLine = true
        }
        scheduleFrame()
    }

    fun updateTerminalFrame(frame: TerminalFrameUpdate) {
        synchronized(queueLock) {
            if (frame.alternateScreen) {
                alternateTerminalScrollbackQueue.addAll(frame.completedScrollback)
            } else {
                primaryTerminalScrollbackQueue.addAll(frame.completedScrollback)
            }
            pendingTerminalFrame = frame.copy(completedScrollback = emptyList(), responses = emptyList())
        }
        scheduleFrame()
    }

    fun start() {
        acceptingOutput = true
        paused = false
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        if (acceptingOutput) paused = false
    }

    fun stop() {
        acceptingOutput = false
        paused = false
        synchronized(queueLock) {
            outputQueue.clear()
            primaryTerminalScrollbackQueue.clear()
            alternateTerminalScrollbackQueue.clear()
            pendingScreen = null
            pendingLiveLine = null
            hasPendingLiveLine = false
            pendingTerminalFrame = null
        }
        publishPerformance(visibleLines = viewport.visibleRows(0).count)
    }

    override fun clear() {
        synchronized(queueLock) {
            outputQueue.clear()
            primaryTerminalScrollbackQueue.clear()
            alternateTerminalScrollbackQueue.clear()
            pendingScreen = null
            pendingLiveLine = null
            hasPendingLiveLine = false
            pendingTerminalFrame = null
        }
        buffer.clear()
        alternateScrollback.clear()
        liveLine = null
        terminalScreen = null
        terminalScreenIsAlternate = false
        terminalCursor = TerminalCursor()
        terminalModes = TerminalModes()
        alternateLines = emptyList()
        cursor = TerminalCursor()
        viewport.updateContent(0, null)
        notifyContentChanged()
    }

    override fun append(lines: List<TerminalLine>) {
        if (lines.isEmpty()) return
        synchronized(queueLock) {
            outputQueue.addAll(lines)
        }
        scheduleFrame()
    }

    override fun replaceVisibleScreen(lines: List<TerminalLine>) {
        synchronized(queueLock) {
            pendingScreen = lines
        }
        scheduleFrame()
    }

    fun enqueueGeneratedLines(lines: List<TerminalLine>) {
        if (!acceptingOutput || paused || lines.isEmpty()) return
        append(lines)
    }

    fun enqueueGeneratedScreen(lines: List<TerminalLine>, newCursor: TerminalCursor) {
        if (!acceptingOutput || paused) return
        cursor = newCursor
        replaceVisibleScreen(lines)
    }

    fun setAlternateScreen(enabled: Boolean) {
        if (alternateScreen == enabled) return
        synchronized(queueLock) {
            outputQueue.clear()
            pendingScreen = null
        }
        alternateScreen = enabled
        cursor = TerminalCursor()
        notifyContentChanged()
    }

    fun isAlternateScreen(): Boolean = alternateScreen || terminalScreenIsAlternate

    fun lineCount(): Int {
        val screen = terminalScreen
        return when {
            screen != null && terminalScreenIsAlternate -> alternateScrollback.lineCount() + screen.size
            screen != null -> buffer.lineCount() + screen.size
            alternateScreen -> alternateLines.size
            else -> buffer.lineCount() + if (liveLine == null) 0 else 1
        }
    }

    fun lineAt(index: Int): TerminalLine? {
        val screen = terminalScreen
        return when {
            screen != null && terminalScreenIsAlternate ->
                alternateScrollback.lineAt(index) ?: screen.getOrNull(index - alternateScrollback.lineCount())
            screen != null -> buffer.lineAt(index) ?: screen.getOrNull(index - buffer.lineCount())
            alternateScreen -> alternateLines.getOrNull(index)
            else -> buffer.lineAt(index) ?: liveLine?.takeIf { index == buffer.lineCount() }
        }
    }

    fun oldestLineId(): Long? = when {
        terminalScreenIsAlternate -> alternateScrollback.oldestLineId()
        alternateScreen -> null
        else -> buffer.oldestLineId()
    }

    fun pendingLineCount(): Int = synchronized(queueLock) {
        outputQueue.size + primaryTerminalScrollbackQueue.size + alternateTerminalScrollbackQueue.size
    }

    override fun jumpToBottom() {
        viewport.jumpToBottom()
        notifyContentChanged()
    }

    fun addListener(listener: TerminalContentListener) {
        listeners.add(listener)
        listener.onTerminalContentChanged()
    }

    fun removeListener(listener: TerminalContentListener) {
        listeners.remove(listener)
    }

    fun setWorkloadDescription(name: String, rate: String) {
        workloadName = name
        workloadRate = rate
        publishPerformance(viewport.visibleRows(0).count)
    }

    fun reportFrameTiming(timing: FrameTimingSnapshot, visibleLines: Int) {
        frameTiming = timing
        publishPerformance(visibleLines)
    }

    fun reportViewportStateIfChanged(previousAutoFollow: Boolean) {
        if (previousAutoFollow != viewport.autoFollow) {
            publishPerformance(viewport.visibleRows(0).count)
        }
    }

    private fun scheduleFrame() {
        val shouldSchedule = synchronized(queueLock) {
            if (frameScheduled) {
                false
            } else {
                frameScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        handler.post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private val frameCallback = Choreographer.FrameCallback {
        val batch: List<TerminalLine>
        val primaryTerminalScrollback: List<TerminalLine>
        val alternateTerminalLines: List<TerminalLine>
        val screen: List<TerminalLine>?
        val nextLiveLine: TerminalLine?
        val updateLiveLine: Boolean
        val terminalFrame: TerminalFrameUpdate?
        synchronized(queueLock) {
            batch = outputQueue.drain(MAX_LINES_PER_FRAME)
            primaryTerminalScrollback = primaryTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            alternateTerminalLines = alternateTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            screen = pendingScreen
            pendingScreen = null
            nextLiveLine = pendingLiveLine
            updateLiveLine = hasPendingLiveLine
            pendingLiveLine = null
            hasPendingLiveLine = false
            terminalFrame = pendingTerminalFrame
            pendingTerminalFrame = null
            frameScheduled = false
        }

        if (batch.isNotEmpty()) buffer.append(batch)
        if (primaryTerminalScrollback.isNotEmpty()) buffer.append(primaryTerminalScrollback)
        val terminalScreenModeChanged = terminalFrame != null && terminalFrame.alternateScreen != terminalScreenIsAlternate
        val enteringAlternate = terminalFrame?.alternateScreen == true && !terminalScreenIsAlternate
        if (enteringAlternate) alternateScrollback.clear()
        if (alternateTerminalLines.isNotEmpty()) alternateScrollback.append(alternateTerminalLines)
        if (screen != null) alternateLines = screen
        if (updateLiveLine) {
            liveLine = nextLiveLine
            cursor = TerminalCursor(
                row = buffer.lineCount(),
                column = nextLiveLine?.text?.length ?: 0,
                visible = true,
            )
        }
        if (terminalFrame != null) {
            terminalScreen = terminalFrame.screen
            terminalScreenIsAlternate = terminalFrame.alternateScreen
            terminalModes = terminalFrame.modes
            terminalCursor = terminalFrame.cursor
        }
        if (
            terminalScreen != null &&
            (batch.isNotEmpty() || primaryTerminalScrollback.isNotEmpty() || alternateTerminalLines.isNotEmpty() || terminalFrame != null)
        ) {
            cursor = terminalCursor.copy(
                row = if (terminalScreenIsAlternate) {
                    alternateScrollback.lineCount() + terminalCursor.row
                } else {
                    buffer.lineCount() + terminalCursor.row
                },
            )
        }
        if (terminalScreenModeChanged) viewport.jumpToBottom()
        if (
            batch.isNotEmpty() || primaryTerminalScrollback.isNotEmpty() || alternateTerminalLines.isNotEmpty() ||
            screen != null || updateLiveLine || terminalFrame != null
        ) {
            notifyContentChanged()
        }

        val hasMore = synchronized(queueLock) {
            !outputQueue.isEmpty || !primaryTerminalScrollbackQueue.isEmpty ||
                !alternateTerminalScrollbackQueue.isEmpty || pendingScreen != null ||
                hasPendingLiveLine || pendingTerminalFrame != null
        }
        if (hasMore) scheduleFrame()
    }

    private fun notifyContentChanged() {
        listeners.forEach { listener -> listener.onTerminalContentChanged() }
    }

    private fun publishPerformance(visibleLines: Int) {
        val runtime = Runtime.getRuntime()
        val pending = synchronized(queueLock) { outputQueue.size }
        _performance.value = PerformanceSnapshot(
            timing = frameTiming,
            scrollbackLines = buffer.lineCount(),
            visibleLines = visibleLines,
            pendingOutputLines = pending,
            heapMegabytes = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MEGABYTE,
            autoFollow = viewport.autoFollow,
            workload = workloadName,
            rate = workloadRate,
        )
    }

    private fun applicationCursorSequence(bytes: ByteArray): ByteArray? {
        if (!terminalModes.applicationCursorKeys) return null
        return when {
            bytes.contentEquals(CSI_ARROW_UP) -> SS3_ARROW_UP
            bytes.contentEquals(CSI_ARROW_DOWN) -> SS3_ARROW_DOWN
            bytes.contentEquals(CSI_ARROW_RIGHT) -> SS3_ARROW_RIGHT
            bytes.contentEquals(CSI_ARROW_LEFT) -> SS3_ARROW_LEFT
            bytes.contentEquals(CSI_HOME) -> SS3_HOME
            bytes.contentEquals(CSI_END) -> SS3_END
            else -> null
        }
    }

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14f
        private const val DEFAULT_TERMINAL_COLUMNS = 80
        private const val DEFAULT_TERMINAL_ROWS = 24
        private const val MAX_PENDING_LINES = 20_000
        private const val MAX_ALTERNATE_SCROLLBACK_LINES = 20_000
        private const val MAX_LINES_PER_FRAME = 2_000
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        private val CSI_ARROW_UP = byteArrayOf(0x1b, 0x5b, 0x41)
        private val CSI_ARROW_DOWN = byteArrayOf(0x1b, 0x5b, 0x42)
        private val CSI_ARROW_RIGHT = byteArrayOf(0x1b, 0x5b, 0x43)
        private val CSI_ARROW_LEFT = byteArrayOf(0x1b, 0x5b, 0x44)
        private val CSI_HOME = byteArrayOf(0x1b, 0x5b, 0x48)
        private val CSI_END = byteArrayOf(0x1b, 0x5b, 0x46)
        private val SS3_ARROW_UP = byteArrayOf(0x1b, 0x4f, 0x41)
        private val SS3_ARROW_DOWN = byteArrayOf(0x1b, 0x4f, 0x42)
        private val SS3_ARROW_RIGHT = byteArrayOf(0x1b, 0x4f, 0x43)
        private val SS3_ARROW_LEFT = byteArrayOf(0x1b, 0x4f, 0x44)
        private val SS3_HOME = byteArrayOf(0x1b, 0x4f, 0x48)
        private val SS3_END = byteArrayOf(0x1b, 0x4f, 0x46)
        private val BRACKETED_PASTE_START = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e)
        private val BRACKETED_PASTE_END = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e)
        private val FOCUS_IN = byteArrayOf(0x1b, 0x5b, 0x49)
        private val FOCUS_OUT = byteArrayOf(0x1b, 0x5b, 0x4f)
    }
}
