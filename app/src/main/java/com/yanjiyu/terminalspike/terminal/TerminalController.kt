package com.yanjiyu.terminalspike.terminal

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.yanjiyu.terminalspike.performance.FrameTimingSnapshot
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import java.util.concurrent.CopyOnWriteArraySet
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
    private var pendingScreen: List<TerminalLine>? = null
    private var frameScheduled = false
    private var acceptingOutput = false
    private var paused = false
    private var alternateScreen = false
    private var alternateLines: List<TerminalLine> = emptyList()
    private var workloadName = "Stream"
    private var workloadRate = "Stopped"
    private var frameTiming = FrameTimingSnapshot()
    private val _performance = MutableStateFlow(PerformanceSnapshot())
    val performance: StateFlow<PerformanceSnapshot> = _performance.asStateFlow()
    private val inputSession = FakeTerminalSession { line -> append(listOf(line)) }

    @Volatile
    var cursor: TerminalCursor = TerminalCursor()
        private set

    override fun send(bytes: ByteArray) = inputSession.send(bytes)

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
            pendingScreen = null
        }
        publishPerformance(visibleLines = viewport.visibleRows(0).count)
    }

    override fun clear() {
        synchronized(queueLock) {
            outputQueue.clear()
            pendingScreen = null
        }
        buffer.clear()
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

    fun isAlternateScreen(): Boolean = alternateScreen

    fun lineCount(): Int = if (alternateScreen) alternateLines.size else buffer.lineCount()

    fun lineAt(index: Int): TerminalLine? =
        if (alternateScreen) alternateLines.getOrNull(index) else buffer.lineAt(index)

    fun oldestLineId(): Long? = if (alternateScreen) null else buffer.oldestLineId()

    fun pendingLineCount(): Int = synchronized(queueLock) { outputQueue.size }

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
        val screen: List<TerminalLine>?
        synchronized(queueLock) {
            batch = outputQueue.drain(MAX_LINES_PER_FRAME)
            screen = pendingScreen
            pendingScreen = null
            frameScheduled = false
        }

        if (batch.isNotEmpty()) buffer.append(batch)
        if (screen != null) alternateLines = screen
        if (batch.isNotEmpty() || screen != null) notifyContentChanged()

        val hasMore = synchronized(queueLock) { !outputQueue.isEmpty || pendingScreen != null }
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

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14f
        private const val MAX_PENDING_LINES = 20_000
        private const val MAX_LINES_PER_FRAME = 2_000
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
    }
}
