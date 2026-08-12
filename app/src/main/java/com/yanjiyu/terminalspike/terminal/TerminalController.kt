package com.yanjiyu.terminalspike.terminal

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.performance.FrameTimingSnapshot
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.TerminalModes
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineAnchor
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectableLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionSource
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import com.yanjiyu.terminalspike.terminal.view.TerminalMouseSequences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TerminalContentChange(
    val fullRedraw: Boolean,
    /** Sorted, unique absolute controller rows. Ignored when [fullRedraw] is true. */
    val dirtyRows: IntArray = IntArray(0),
) {
    companion object {
        val FULL = TerminalContentChange(fullRedraw = true)
        val NONE = TerminalContentChange(fullRedraw = false)
    }
}

fun interface TerminalContentListener {
    fun onTerminalContentChanged(change: TerminalContentChange)
}

fun interface TerminalBellListener {
    /** Called only for a newly published frame; registration never replays an old event. */
    fun onTerminalBell(event: TerminalBellEvent)
}

data class TerminalCursor(
    val row: Int = 0,
    val column: Int = 0,
    val visible: Boolean = false,
    /** Remote DECSCUSR override. Null keeps the selected terminal-profile shape. */
    val styleOverride: CursorStyle? = null,
    /** Remote DECSCUSR override. Null keeps the selected terminal-profile blink policy. */
    val blinkOverride: Boolean? = null,
)

class TerminalController(
    val buffer: TerminalBuffer = TerminalBuffer(),
) : TerminalRendererController, TerminalInputSink, TerminalSelectionSource {
    internal constructor(
        buffer: TerminalBuffer,
        frameScheduler: TerminalFrameScheduler,
    ) : this(buffer) {
        this.frameScheduler = frameScheduler
    }

    val viewport = TerminalViewport()
    @Volatile
    var rendererProfile: TerminalRendererProfile = TerminalRendererProfile()
        private set

    var fontSizeSp: Float
        get() = rendererProfile.fontSizeSp
        set(value) {
            updateRendererProfile(rendererProfile.copy(fontSizeSp = value))
        }

    private var frameScheduler: TerminalFrameScheduler = AndroidTerminalFrameScheduler
    internal val contentSourceId: Long = NEXT_CONTENT_SOURCE_ID.getAndIncrement()
    private val listeners = CopyOnWriteArraySet<TerminalContentListener>()
    private val bellListeners = CopyOnWriteArraySet<TerminalBellListener>()
    private val queueLock = Any()
    private val outputQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val primaryTerminalScrollbackQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val alternateTerminalScrollbackQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val alternateScrollback = TerminalBuffer(MAX_ALTERNATE_SCROLLBACK_LINES)
    private var pendingScreen: List<TerminalLine>? = null
    private var pendingLiveLine: TerminalLine? = null
    private var hasPendingLiveLine = false
    private var pendingTerminalFrame: TerminalFrameUpdate? = null
    private var pendingPrimaryScrollbackClear = false
    private var pendingAlternateScrollbackClear = false
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
    @Volatile
    private var terminalModes = TerminalModes()
    @Volatile
    private var selectionContentRevision = 0L

    @Volatile
    private var publishedBellSequence = 0L
    private val _performance = MutableStateFlow(PerformanceSnapshot())
    val performance: StateFlow<PerformanceSnapshot> = _performance.asStateFlow()
    private val defaultInputSink: TerminalInputSink = createDefaultTerminalInputSink()

    @Volatile
    private var inputSink: TerminalInputSink = defaultInputSink

    @Volatile
    private var terminalSizeListener: ((Int, Int) -> Unit)? = null

    @Volatile
    private var inputAcceptedListener: (() -> Unit)? = null

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
        sendWithAcceptance(bytes)
    }

    override fun sendWithAcceptance(bytes: ByteArray): Boolean =
        trySendUserInput(
            bytes = applicationCursorSequence(bytes) ?: bytes,
            applyDirectSendPolicy = true,
        )

    override fun trySend(bytes: ByteArray): Boolean =
        trySendUserInput(
            bytes = applicationCursorSequence(bytes) ?: bytes,
            applyDirectSendPolicy = false,
        )

    fun sendPaste(text: String, appendEnter: Boolean = false): Boolean =
        trySendUserInput(
            bytes = encodeTerminalPaste(
                text = text,
                bracketedPaste = terminalModes.bracketedPaste,
                appendEnter = appendEnter,
            ),
            applyDirectSendPolicy = false,
        )

    /** Sends a physical numeric-keypad key using DECKPAM when the remote enabled it. */
    fun sendKeypad(normal: ByteArray, application: ByteArray): Boolean =
        trySendUserInput(
            bytes = selectKeypadSequence(terminalModes.applicationKeypad, normal, application),
            applyDirectSendPolicy = true,
        )

    /**
     * Sends terminal-protocol focus state, not user input.
     *
     * Focus reports deliberately stay outside outbound user-activity accounting. The sink's
     * direct-send policy still applies, including any transport failure caused by rejection.
     */
    fun reportFocus(focused: Boolean) {
        if (terminalModes.focusReporting) inputSink.send(if (focused) FOCUS_IN else FOCUS_OUT)
    }

    fun sendMouseWheel(up: Boolean, column: Int, row: Int): Boolean {
        if (!terminalModes.mouseTracking) return false
        return trySendUserInput(
            bytes = TerminalMouseSequences.wheel(
                up = up,
                column = column.coerceIn(0, terminalColumns - 1),
                row = row.coerceIn(0, terminalRows - 1),
                sgrEncoding = terminalModes.sgrMouseEncoding,
            ),
            applyDirectSendPolicy = true,
        )
    }

    fun isMouseTrackingEnabled(): Boolean = terminalModes.mouseTracking

    fun updateRendererProfile(profile: TerminalRendererProfile) {
        if (rendererProfile == profile) return
        rendererProfile = profile
        if (!profile.preserveAlternateScreenHistory) {
            synchronized(queueLock) {
                alternateTerminalScrollbackQueue.clear()
                alternateScrollback.clear()
                bumpSelectionContentRevision()
            }
        }
        notifyContentChanged()
    }

    fun setInputSink(
        sink: TerminalInputSink,
        onInputAccepted: () -> Unit = {},
        onResize: (Int, Int) -> Unit,
    ) {
        inputSink = sink
        terminalSizeListener = onResize
        inputAcceptedListener = onInputAccepted
    }

    fun resetInputSink() {
        inputSink = defaultInputSink
        terminalSizeListener = null
        inputAcceptedListener = null
    }

    private fun trySendUserInput(bytes: ByteArray, applyDirectSendPolicy: Boolean): Boolean {
        if (bytes.isEmpty()) return false
        val accepted = if (applyDirectSendPolicy) {
            inputSink.sendWithAcceptance(bytes)
        } else {
            inputSink.trySend(bytes)
        }
        if (accepted) {
            if (rendererProfile.jumpToBottomOnKeyboardInput) jumpToBottom()
            inputAcceptedListener?.invoke()
        }
        return accepted
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
            // Input protocol modes must take effect as soon as the parser observes them. Waiting for
            // the next render frame can encode a keypad press with the preceding DECKPAM state.
            terminalModes = frame.modes
            if (frame.clearScrollbackRequested) {
                if (frame.alternateScreen) {
                    alternateTerminalScrollbackQueue.clear()
                    pendingAlternateScrollbackClear = true
                } else {
                    // Both queues feed the controller-owned primary history. Anything queued before
                    // ED3 belongs to the saved lines being erased; later updates enqueue after it.
                    outputQueue.clear()
                    primaryTerminalScrollbackQueue.clear()
                    pendingPrimaryScrollbackClear = true
                }
            }
            if (frame.alternateScreen && rendererProfile.preserveAlternateScreenHistory) {
                alternateTerminalScrollbackQueue.addAll(frame.completedScrollback)
            } else if (!frame.alternateScreen) {
                primaryTerminalScrollbackQueue.addAll(frame.completedScrollback)
            }
            val earlierFrame = pendingTerminalFrame
            val coalescedBellCount = (
                (earlierFrame?.bellCount ?: 0).toLong() + frame.bellCount.toLong()
            ).coerceAtMost(MAX_COALESCED_BELL_COUNT.toLong()).toInt()
            pendingTerminalFrame = frame.copy(
                completedScrollback = emptyList(),
                responses = emptyList(),
                remoteClipboardRequests = emptyList(),
                bellSequence = maxOf(earlierFrame?.bellSequence ?: 0L, frame.bellSequence),
                bellCount = coalescedBellCount,
                clearScrollbackRequested =
                    earlierFrame?.clearScrollbackRequested == true || frame.clearScrollbackRequested,
                dirtyRows = mergeSortedDirtyRows(
                    first = earlierFrame?.dirtyRows,
                    second = frame.dirtyRows,
                    upperBoundExclusive = frame.screen.size,
                ),
            )
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
            pendingPrimaryScrollbackClear = false
            pendingAlternateScrollbackClear = false
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
            pendingPrimaryScrollbackClear = false
            pendingAlternateScrollbackClear = false
            buffer.clear()
            alternateScrollback.clear()
            liveLine = null
            terminalScreen = null
            terminalScreenIsAlternate = false
            terminalCursor = TerminalCursor()
            terminalModes = TerminalModes()
            alternateLines = emptyList()
            cursor = TerminalCursor()
            bumpSelectionContentRevision()
        }
        viewport.updateContent(0, null)
        notifyContentChanged()
    }

    /** Clears captured local history while leaving the live remote screen and transport intact. */
    fun clearLocalScrollback() {
        synchronized(queueLock) {
            // Queued rows were captured before the user's clear confirmation and are history too.
            outputQueue.clear()
            primaryTerminalScrollbackQueue.clear()
            alternateTerminalScrollbackQueue.clear()
            pendingPrimaryScrollbackClear = false
            pendingAlternateScrollbackClear = false
            buffer.clear()
            alternateScrollback.clear()
            bumpSelectionContentRevision()
        }
        viewport.updateContent(lineCount(), oldestLineId())
        viewport.jumpToBottom()
        notifyContentChanged()
    }

    /** Atomic, bounded stable-reference snapshot for find or transcript streaming off-main. */
    fun transcriptSnapshot(): TerminalTranscriptSnapshot = synchronized(queueLock) {
        val count = lineCount()
        val first = (count - TerminalTranscriptSnapshot.MAX_LINES).coerceAtLeast(0)
        val rows = ArrayList<TerminalTranscriptRow>(count - first)
        for (index in first until count) {
            selectionLineAt(index)?.let { selectable ->
                rows += TerminalTranscriptRow(selectable.anchor, selectable.line)
            }
        }
        TerminalTranscriptSnapshot(
            sourceId = contentSourceId,
            revision = selectionContentRevision,
            rows = rows,
            truncatedBefore = first > 0,
        )
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
        synchronized(queueLock) {
            if (alternateScreen == enabled) return
            outputQueue.clear()
            pendingScreen = null
            alternateScreen = enabled
            cursor = TerminalCursor()
            bumpSelectionContentRevision()
        }
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

    override val selectionLineCount: Int
        get() = lineCount()

    /** Changes whenever a captured find/volatile selection snapshot must be discarded. */
    val contentRevision: Long
        get() = selectionContentRevision

    override fun selectionLineAt(index: Int): TerminalSelectableLine? {
        val line = lineAt(index) ?: return null
        val anchor = when {
            terminalScreen != null && terminalScreenIsAlternate -> {
                val historyCount = alternateScrollback.lineCount()
                if (index < historyCount && line.id >= 0L) {
                    TerminalLineAnchor(TerminalLineSpace.ALTERNATE_HISTORY, line.id)
                } else {
                    volatileSelectionAnchor(index)
                }
            }
            terminalScreen != null -> {
                val historyCount = buffer.lineCount()
                if (index < historyCount && line.id >= 0L) {
                    TerminalLineAnchor(TerminalLineSpace.PRIMARY_HISTORY, line.id)
                } else {
                    volatileSelectionAnchor(index)
                }
            }
            alternateScreen -> volatileSelectionAnchor(index)
            index < buffer.lineCount() && line.id >= 0L ->
                TerminalLineAnchor(TerminalLineSpace.PRIMARY_HISTORY, line.id)
            else -> volatileSelectionAnchor(index)
        }
        return TerminalSelectableLine(anchor, line)
    }

    override fun selectionIndexOf(anchor: TerminalLineAnchor): Int? = when (anchor.space) {
        TerminalLineSpace.PRIMARY_HISTORY -> {
            val primaryVisible = if (terminalScreen != null) !terminalScreenIsAlternate else !alternateScreen
            if (!primaryVisible) {
                null
            } else {
                buffer.indexOfId(anchor.id)
            }
        }
        TerminalLineSpace.ALTERNATE_HISTORY -> {
            if (terminalScreen == null || !terminalScreenIsAlternate) {
                null
            } else {
                alternateScrollback.indexOfId(anchor.id)
            }
        }
        TerminalLineSpace.VOLATILE_SCREEN -> {
            if (anchor.id != selectionContentRevision) {
                null
            } else {
                selectionLineAt(anchor.volatileRow)
                    ?.takeIf { it.anchor == anchor }
                    ?.let { anchor.volatileRow }
            }
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
        listener.onTerminalContentChanged(TerminalContentChange.FULL)
    }

    fun removeListener(listener: TerminalContentListener) {
        listeners.remove(listener)
    }

    fun addBellListener(listener: TerminalBellListener) {
        // Deliberately no initial callback: BEL is an event, not replayable content state.
        bellListeners.add(listener)
    }

    fun removeBellListener(listener: TerminalBellListener) {
        bellListeners.remove(listener)
    }

    /** Lets a newly attached foreground owner suppress the already-consumed event sequence. */
    fun latestBellSequence(): Long = publishedBellSequence

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
        frameScheduler.postFrame(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback {
        var contentChanged = false
        var contentChange = TerminalContentChange.NONE
        var terminalScreenModeChanged = false
        var bellEvent: TerminalBellEvent? = null
        val hasMore = synchronized(queueLock) {
            val previousTerminalScreen = terminalScreen
            val previousCursor = cursor
            val primaryHistoryCleared = pendingPrimaryScrollbackClear
            val alternateHistoryCleared = pendingAlternateScrollbackClear
            pendingPrimaryScrollbackClear = false
            pendingAlternateScrollbackClear = false
            val batch = outputQueue.drain(MAX_LINES_PER_FRAME)
            val primaryTerminalScrollback = primaryTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            val alternateTerminalLines = alternateTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            val screen = pendingScreen
            pendingScreen = null
            val nextLiveLine = pendingLiveLine
            val updateLiveLine = hasPendingLiveLine
            pendingLiveLine = null
            hasPendingLiveLine = false
            val terminalFrame = pendingTerminalFrame
            pendingTerminalFrame = null
            frameScheduled = false

            if (primaryHistoryCleared) buffer.clear()
            if (alternateHistoryCleared) alternateScrollback.clear()
            if (batch.isNotEmpty()) buffer.append(batch)
            if (primaryTerminalScrollback.isNotEmpty()) buffer.append(primaryTerminalScrollback)
            terminalScreenModeChanged =
                terminalFrame != null && terminalFrame.alternateScreen != terminalScreenIsAlternate
            val enteringAlternate = terminalFrame?.alternateScreen == true && !terminalScreenIsAlternate
            if (enteringAlternate) alternateScrollback.clear()
            if (alternateTerminalLines.isNotEmpty() && rendererProfile.preserveAlternateScreenHistory) {
                alternateScrollback.append(alternateTerminalLines)
            }
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
                if (
                    terminalFrame.bellCount > 0 &&
                    terminalFrame.bellSequence != publishedBellSequence
                ) {
                    publishedBellSequence = terminalFrame.bellSequence
                    bellEvent = TerminalBellEvent(
                        sequence = terminalFrame.bellSequence,
                        count = terminalFrame.bellCount,
                    )
                }
            }
            contentChanged =
                primaryHistoryCleared || alternateHistoryCleared ||
                batch.isNotEmpty() || primaryTerminalScrollback.isNotEmpty() ||
                alternateTerminalLines.isNotEmpty() || screen != null ||
                updateLiveLine || terminalFrame != null
            if (terminalScreen != null && contentChanged) {
                cursor = terminalCursor.copy(
                    row = if (terminalScreenIsAlternate) {
                        alternateScrollback.lineCount() + terminalCursor.row
                    } else {
                        buffer.lineCount() + terminalCursor.row
                    },
                )
            }
            if (contentChanged) bumpSelectionContentRevision()

            val structuralChange =
                primaryHistoryCleared || alternateHistoryCleared ||
                    batch.isNotEmpty() || primaryTerminalScrollback.isNotEmpty() ||
                    alternateTerminalLines.isNotEmpty() || screen != null || updateLiveLine ||
                    terminalScreenModeChanged ||
                    terminalFrame != null && (
                        previousTerminalScreen == null ||
                            previousTerminalScreen.size != terminalFrame.screen.size
                        )
            contentChange = when {
                !contentChanged -> TerminalContentChange.NONE
                structuralChange || terminalFrame == null -> TerminalContentChange.FULL
                else -> TerminalContentChange(
                    fullRedraw = false,
                    dirtyRows = absoluteDirtyRows(
                        frame = terminalFrame,
                        previousCursor = previousCursor,
                        currentCursor = cursor,
                    ),
                )
            }

            !outputQueue.isEmpty || !primaryTerminalScrollbackQueue.isEmpty ||
                !alternateTerminalScrollbackQueue.isEmpty || pendingScreen != null ||
                hasPendingLiveLine || pendingTerminalFrame != null
        }
        if (terminalScreenModeChanged) viewport.jumpToBottom()
        if (contentChanged) notifyContentChanged(contentChange)
        bellEvent?.let { event -> bellListeners.forEach { listener -> listener.onTerminalBell(event) } }
        if (hasMore) scheduleFrame()
    }

    private fun notifyContentChanged(change: TerminalContentChange = TerminalContentChange.FULL) {
        val effectiveChange = if (!rendererProfile.keepViewportPositionOnOutput) {
            viewport.jumpToBottom()
            TerminalContentChange.FULL
        } else {
            change
        }
        listeners.forEach { listener -> listener.onTerminalContentChanged(effectiveChange) }
    }

    /** Maps engine screen rows into the controller's absolute history + screen row space. */
    private fun absoluteDirtyRows(
        frame: TerminalFrameUpdate,
        previousCursor: TerminalCursor,
        currentCursor: TerminalCursor,
    ): IntArray {
        val historyRows = if (frame.alternateScreen) {
            alternateScrollback.lineCount()
        } else {
            buffer.lineCount()
        }
        val mappedRows = IntArray(frame.dirtyRows.size)
        var mappedCount = 0
        frame.dirtyRows.forEach { screenRow ->
            if (screenRow in frame.screen.indices) {
                mappedRows[mappedCount++] = historyRows + screenRow
            }
        }
        val screenDirtyRows = if (mappedCount == mappedRows.size) {
            mappedRows
        } else {
            mappedRows.copyOf(mappedCount)
        }
        if (previousCursor == currentCursor || !previousCursor.visible && !currentCursor.visible) {
            return screenDirtyRows
        }
        val cursorRows = IntArray(2)
        var cursorCount = 0
        if (previousCursor.visible && previousCursor.row in 0 until lineCount()) {
            cursorRows[cursorCount++] = previousCursor.row
        }
        if (
            currentCursor.visible && currentCursor.row in 0 until lineCount() &&
            (cursorCount == 0 || currentCursor.row != cursorRows[0])
        ) {
            cursorRows[cursorCount++] = currentCursor.row
        }
        val boundedCursorRows = cursorRows.copyOf(cursorCount).apply { sort() }
        return mergeSortedDirtyRows(screenDirtyRows, boundedCursorRows)
    }

    private fun volatileSelectionAnchor(row: Int): TerminalLineAnchor = TerminalLineAnchor(
        space = TerminalLineSpace.VOLATILE_SCREEN,
        id = selectionContentRevision,
        volatileRow = row,
    )

    private fun bumpSelectionContentRevision() {
        selectionContentRevision = if (selectionContentRevision == Long.MAX_VALUE) {
            0L
        } else {
            selectionContentRevision + 1L
        }
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
        private val NEXT_CONTENT_SOURCE_ID = AtomicLong(1L)
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
        private val FOCUS_IN = byteArrayOf(0x1b, 0x5b, 0x49)
        private val FOCUS_OUT = byteArrayOf(0x1b, 0x5b, 0x4f)
    }
}

internal fun interface TerminalFrameScheduler {
    fun postFrame(callback: Choreographer.FrameCallback)
}

private object AndroidTerminalFrameScheduler : TerminalFrameScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun postFrame(callback: Choreographer.FrameCallback) {
        handler.post {
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }
}

/** Linear union for the engine's sorted unique row arrays without per-row boxing. */
private fun mergeSortedDirtyRows(
    first: IntArray?,
    second: IntArray,
    upperBoundExclusive: Int = Int.MAX_VALUE,
): IntArray {
    val left = first ?: IntArray(0)
    if (left.isEmpty() && second.all { it in 0 until upperBoundExclusive }) return second.copyOf()
    if (second.isEmpty() && left.all { it in 0 until upperBoundExclusive }) return left.copyOf()
    val merged = IntArray(left.size + second.size)
    var leftIndex = 0
    var rightIndex = 0
    var mergedSize = 0
    var previous = Int.MIN_VALUE
    while (leftIndex < left.size || rightIndex < second.size) {
        val next = when {
            rightIndex >= second.size -> left[leftIndex++]
            leftIndex >= left.size -> second[rightIndex++]
            left[leftIndex] < second[rightIndex] -> left[leftIndex++]
            left[leftIndex] > second[rightIndex] -> second[rightIndex++]
            else -> left[leftIndex++].also { rightIndex += 1 }
        }
        if (next in 0 until upperBoundExclusive && (mergedSize == 0 || next != previous)) {
            merged[mergedSize++] = next
            previous = next
        }
    }
    return merged.copyOf(mergedSize)
}

internal fun selectKeypadSequence(
    applicationKeypad: Boolean,
    normal: ByteArray,
    application: ByteArray,
): ByteArray = if (applicationKeypad) application else normal

internal fun encodeTerminalPaste(
    text: String,
    bracketedPaste: Boolean,
    appendEnter: Boolean = false,
): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val paste = if (bracketedPaste) {
        BRACKETED_PASTE_START + bytes + BRACKETED_PASTE_END
    } else {
        bytes
    }
    return if (appendEnter) paste + PASTE_ENTER else paste
}

private val BRACKETED_PASTE_START = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e)
private val BRACKETED_PASTE_END = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e)
private val PASTE_ENTER = byteArrayOf(0x0d)
