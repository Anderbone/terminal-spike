package com.yanjiyu.terminalspike.terminal

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.yanjiyu.terminalspike.connection.TmuxHistoryPageRequest
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.ModelLimits
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
import java.util.ArrayDeque
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
    private val tmuxTerminalScrollbackQueue = BoundedBatchQueue<TerminalLine>(MAX_PENDING_LINES)
    private val alternateScrollback = TerminalBuffer(MAX_ALTERNATE_SCROLLBACK_LINES)
    private var tmuxScrollback: TerminalBuffer? = null
    private var pendingScreen: List<TerminalLine>? = null
    private var pendingLiveLine: TerminalLine? = null
    private var hasPendingLiveLine = false
    private var pendingTerminalFrame: TerminalFrameUpdate? = null
    private val pendingTmuxHistorySnapshots = ArrayDeque<TmuxLocalHistorySnapshot>()
    private var activeTmuxHistoryReconciliation: TmuxHistoryReconciliation? = null
    private var tmuxReconciliationWorkObserver: ((Int) -> Unit)? = null
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
    private var tmuxHistoryActive = false
    /**
     * The outer VT screen mode in which the managed tmux client was first observed.
     *
     * SSH tmux clients normally enter the outer alternate screen, while a resumed Mosh tmux
     * client can remain on the outer primary screen. Only the former may use an alternate-to-
     * primary transition as evidence that the client exited tmux.
     */
    private var tmuxOuterScreenIsAlternate: Boolean? = null
    @Volatile
    private var tmuxOuterTerminalExited = false
    private var tmuxHistoryMetadataKnown = false
    @Volatile
    private var tmuxInteractionMetadataFresh = false
    private var tmuxRemoteMousePassthrough = true
    private var tmuxInnerMouseTracking = false
    private var tmuxPaneInMode = false
    private var tmuxHistoryPaneId: String? = null
    private var tmuxHistoryTruncatedBefore = false
    /** Rows owned by the app from completed tmux history-coordinate epochs. */
    private var tmuxArchivedHistoryRows = 0
    private var tmuxHistoryCapturedStartRow = 0
    private var tmuxHistoryOldestAvailableRow = 0
    private var tmuxRemoteHistoryRows = 0
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
    private var tmuxSessionProvider: (() -> Boolean)? = null
    private var trustTmuxStreamScrollback = true

    @Volatile
    private var tmuxHistoryRefreshListener: ((Boolean) -> Unit)? = null

    @Volatile
    private var tmuxOlderHistoryListener: ((TmuxHistoryPageRequest) -> Unit)? = null

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

    fun isTmuxSession(): Boolean =
        !tmuxOuterTerminalExited && tmuxSessionProvider?.invoke() == true

    fun isTmuxLocalScrollAvailable(): Boolean =
        isTmuxSession() && tmuxHistoryActive &&
            tmuxHistoryMetadataKnown && (tmuxScrollback?.lineCount() ?: 0) > 0

    internal fun isTmuxPaneInMode(): Boolean = synchronized(queueLock) {
        tmuxHistoryMetadataKnown && tmuxPaneInMode
    }

    internal fun tmuxScrollDiagnostic(): String {
        val confirmed = isTmuxSession()
        return synchronized(queueLock) {
            "confirmed=$confirmed, outerAlternate=$terminalScreenIsAlternate, " +
                "historyActive=$tmuxHistoryActive, metadataKnown=$tmuxHistoryMetadataKnown, " +
                "metadataFresh=$tmuxInteractionMetadataFresh, " +
                "remoteMousePassthrough=$tmuxRemoteMousePassthrough, " +
                "mouseAny=$tmuxInnerMouseTracking, paneInMode=$tmuxPaneInMode, " +
                "historyLines=${tmuxScrollback?.lineCount() ?: 0}, pane=$tmuxHistoryPaneId, " +
                "archivedHistory=$tmuxArchivedHistoryRows, " +
                "capturedStart=$tmuxHistoryCapturedStartRow, " +
                "oldestAvailable=$tmuxHistoryOldestAvailableRow, " +
                "remoteHistory=$tmuxRemoteHistoryRows, truncatedBefore=$tmuxHistoryTruncatedBefore, " +
                "pendingSnapshots=${pendingTmuxHistorySnapshots.size}, " +
                "outerExited=$tmuxOuterTerminalExited"
        }
    }

    internal fun hasTmuxLocalHistory(): Boolean = synchronized(queueLock) {
        tmuxHistoryMetadataKnown && (tmuxScrollback?.lineCount() ?: 0) > 0
    }

    internal fun markTmuxInteractionMetadataStale() {
        synchronized(queueLock) { tmuxInteractionMetadataFresh = false }
    }

    /** Schedules one off-main probe; established history avoids another full transfer. */
    fun requestTmuxHistoryRefresh() {
        if (isTmuxSession()) {
            tmuxHistoryRefreshListener?.invoke(!hasTmuxLocalHistory())
        }
    }

    /** Requests one older bounded page only when the reader is near a locally truncated top. */
    fun requestOlderTmuxHistoryIfNeeded() {
        if (!isTmuxSession()) return
        val request = synchronized(queueLock) {
            val paneId = tmuxHistoryPaneId ?: return@synchronized null
            if (
                !tmuxHistoryMetadataKnown ||
                tmuxHistoryCapturedStartRow <= tmuxHistoryOldestAvailableRow ||
                viewport.scrollY > viewport.lineHeightPx * TMUX_HISTORY_PREFETCH_ROWS
            ) {
                return@synchronized null
            }
            TmuxHistoryPageRequest(
                paneId = paneId,
                beforeRow = tmuxHistoryCapturedStartRow,
                remoteHistoryRows = tmuxRemoteHistoryRows,
            )
        }
        request?.let { tmuxOlderHistoryListener?.invoke(it) }
    }

    /** Re-arms managed history when the selector attaches tmux after an earlier outer-screen exit. */
    internal fun beginManagedTmuxSession(identityChanged: Boolean = false) {
        synchronized(queueLock) {
            if (tmuxOuterTerminalExited || identityChanged) {
                tmuxHistoryActive = false
                tmuxOuterScreenIsAlternate = null
                tmuxHistoryMetadataKnown = false
                tmuxInteractionMetadataFresh = false
                tmuxRemoteMousePassthrough = true
                tmuxInnerMouseTracking = false
                tmuxPaneInMode = false
                tmuxTerminalScrollbackQueue.clear()
                pendingTmuxHistorySnapshots.clear()
                activeTmuxHistoryReconciliation = null
                tmuxHistoryPaneId = null
                tmuxHistoryTruncatedBefore = false
                resetTmuxHistoryRange()
            }
            tmuxOuterTerminalExited = false
        }
    }

    internal fun stageTmuxHistory(snapshot: TmuxLocalHistorySnapshot) {
        if (tmuxOuterTerminalExited || tmuxSessionProvider?.invoke() != true) return
        val shouldSchedule = synchronized(queueLock) {
            if (tmuxOuterTerminalExited) return@synchronized false
            if (!snapshot.olderPage) {
                val active = activeTmuxHistoryReconciliation
                val changesPane = active != null && active.snapshot.paneId != snapshot.paneId
                val supersedesActive = changesPane || active != null && !active.snapshot.olderPage &&
                    (snapshot.historyIncluded || !active.snapshot.historyIncluded)
                if (supersedesActive) activeTmuxHistoryReconciliation = null
                pendingTmuxHistorySnapshots.removeIf { pending ->
                    pending.paneId != snapshot.paneId ||
                        !pending.olderPage &&
                        (snapshot.historyIncluded || !pending.historyIncluded)
                }
            }
            if (pendingTmuxHistorySnapshots.size == MAX_PENDING_TMUX_SNAPSHOTS) {
                pendingTmuxHistorySnapshots.removeFirst()
            }
            pendingTmuxHistorySnapshots.addLast(snapshot)
            true
        }
        if (shouldSchedule) scheduleFrame()
    }

    internal fun observeTmuxReconciliationWork(observer: ((Int) -> Unit)?) {
        synchronized(queueLock) { tmuxReconciliationWorkObserver = observer }
    }

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
        isTmuxSession: () -> Boolean = { false },
        requestTmuxHistoryRefresh: (Boolean) -> Unit = {},
        requestOlderTmuxHistory: (TmuxHistoryPageRequest) -> Unit = {},
        trustTmuxStreamScrollback: Boolean = true,
    ) {
        inputSink = sink
        terminalSizeListener = onResize
        inputAcceptedListener = onInputAccepted
        tmuxSessionProvider = isTmuxSession
        this.trustTmuxStreamScrollback = trustTmuxStreamScrollback
        tmuxHistoryRefreshListener = requestTmuxHistoryRefresh
        tmuxOlderHistoryListener = requestOlderTmuxHistory
        tmuxOuterTerminalExited = false
        tmuxOuterScreenIsAlternate = null
    }

    fun resetInputSink() {
        inputSink = defaultInputSink
        terminalSizeListener = null
        inputAcceptedListener = null
        tmuxSessionProvider = null
        tmuxHistoryRefreshListener = null
        tmuxOlderHistoryListener = null
        synchronized(queueLock) {
            pendingTmuxHistorySnapshots.clear()
            activeTmuxHistoryReconciliation = null
        }
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
        val configuredTmux = tmuxSessionProvider?.invoke() == true
        synchronized(queueLock) {
            val exitingManagedTmux = configuredTmux &&
                tmuxOuterScreenIsAlternate != false && !frame.alternateScreen &&
                tmuxHistoryActive &&
                (terminalScreenIsAlternate || pendingTerminalFrame?.alternateScreen == true)
            if (exitingManagedTmux) {
                tmuxOuterTerminalExited = true
                tmuxHistoryMetadataKnown = false
                tmuxInteractionMetadataFresh = false
                tmuxRemoteMousePassthrough = true
                tmuxInnerMouseTracking = false
                tmuxPaneInMode = false
                tmuxTerminalScrollbackQueue.clear()
                pendingTmuxHistorySnapshots.clear()
                activeTmuxHistoryReconciliation = null
            }
            val managedTmuxFrame = configuredTmux &&
                !tmuxOuterTerminalExited
            if (frame.alternateScreen && !configuredTmux) tmuxHistoryActive = false
            // Input protocol modes must take effect as soon as the parser observes them. Waiting for
            // the next render frame can encode a keypad press with the preceding DECKPAM state.
            terminalModes = frame.modes
            // ED3 clears the remote program's terminal display history, not the app-owned local
            // transcript. Only the explicit clearLocalScrollback action may destroy retained rows.
            if (frame.alternateScreen && rendererProfile.preserveAlternateScreenHistory) {
                alternateTerminalScrollbackQueue.addAll(frame.completedScrollback)
            } else if (!frame.alternateScreen) {
                primaryTerminalScrollbackQueue.addAll(frame.completedScrollback)
            }
            if (managedTmuxFrame) {
                tmuxHistoryActive = true
                if (trustTmuxStreamScrollback) tmuxTerminalScrollbackQueue.addAll(frame.completedScrollback)
            }
            val earlierFrame = pendingTerminalFrame
            val coalescedBellCount = (
                (earlierFrame?.bellCount ?: 0).toLong() + frame.bellCount.toLong()
            ).coerceAtMost(MAX_COALESCED_BELL_COUNT.toLong()).toInt()
            pendingTerminalFrame = frame.copy(
                completedScrollback = emptyList(),
                responses = emptyList(),
                remoteClipboardRequests = emptyList(),
                terminalNotifications = emptyList(),
                bellSequence = maxOf(earlierFrame?.bellSequence ?: 0L, frame.bellSequence),
                bellCount = coalescedBellCount,
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
            tmuxTerminalScrollbackQueue.clear()
            pendingScreen = null
            pendingLiveLine = null
            hasPendingLiveLine = false
            pendingTerminalFrame = null
            pendingTmuxHistorySnapshots.clear()
            activeTmuxHistoryReconciliation = null
        }
        publishPerformance(visibleLines = viewport.visibleRows(0).count)
    }

    override fun clear() {
        synchronized(queueLock) {
            outputQueue.clear()
            primaryTerminalScrollbackQueue.clear()
            alternateTerminalScrollbackQueue.clear()
            tmuxTerminalScrollbackQueue.clear()
            pendingScreen = null
            pendingLiveLine = null
            hasPendingLiveLine = false
            pendingTerminalFrame = null
            pendingTmuxHistorySnapshots.clear()
            activeTmuxHistoryReconciliation = null
            buffer.clear()
            alternateScrollback.clear()
            tmuxScrollback?.clear()
            tmuxScrollback = null
            liveLine = null
            terminalScreen = null
            terminalScreenIsAlternate = false
            tmuxHistoryActive = false
            tmuxOuterScreenIsAlternate = null
            tmuxOuterTerminalExited = false
            tmuxHistoryMetadataKnown = false
            tmuxInteractionMetadataFresh = false
            tmuxRemoteMousePassthrough = true
            tmuxInnerMouseTracking = false
            tmuxPaneInMode = false
            tmuxHistoryPaneId = null
            tmuxHistoryTruncatedBefore = false
            resetTmuxHistoryRange()
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
            tmuxTerminalScrollbackQueue.clear()
            buffer.clear()
            alternateScrollback.clear()
            tmuxScrollback?.clear()
            tmuxScrollback = null
            pendingTmuxHistorySnapshots.clear()
            activeTmuxHistoryReconciliation = null
            tmuxHistoryMetadataKnown = false
            tmuxInteractionMetadataFresh = false
            tmuxRemoteMousePassthrough = true
            tmuxInnerMouseTracking = false
            tmuxPaneInMode = false
            tmuxHistoryPaneId = null
            tmuxHistoryTruncatedBefore = false
            resetTmuxHistoryRange()
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
            truncatedBefore = first > 0 || (tmuxHistoryActive && tmuxHistoryTruncatedBefore),
        )
    }

    /** Small point-in-time preview for the tab switcher; never publishes terminal cells as UI state. */
    fun previewLines(maxLines: Int = 10, maxColumns: Int = 120): List<String> =
        synchronized(queueLock) {
            val boundedLines = maxLines.coerceIn(1, 16)
            val boundedColumns = maxColumns.coerceIn(16, 160)
            val count = lineCount()
            val first = (count - boundedLines).coerceAtLeast(0)
            (first until count)
                .mapNotNull { index -> lineAt(index)?.text }
                .map { line -> line.take(boundedColumns).trimEnd() }
                .dropWhile(String::isBlank)
                .dropLastWhile(String::isBlank)
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
            screen != null && tmuxHistoryActive -> tmuxScrollbackOrCreate().lineCount() + screen.size
            screen != null && terminalScreenIsAlternate -> alternateScrollback.lineCount() + screen.size
            screen != null -> buffer.lineCount() + screen.size
            alternateScreen -> alternateLines.size
            else -> buffer.lineCount() + if (liveLine == null) 0 else 1
        }
    }

    fun lineAt(index: Int): TerminalLine? {
        val screen = terminalScreen
        return when {
            screen != null && tmuxHistoryActive -> tmuxScrollbackOrCreate().let { history ->
                history.lineAt(index) ?: screen.getOrNull(index - history.lineCount())
            }
            screen != null && terminalScreenIsAlternate -> alternateScrollback.let { history ->
                history.lineAt(index) ?: screen.getOrNull(index - history.lineCount())
            }
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
            terminalScreen != null && tmuxHistoryActive -> {
                val historyCount = tmuxScrollbackOrCreate().lineCount()
                if (index < historyCount && line.id >= 0L) {
                    TerminalLineAnchor(TerminalLineSpace.ALTERNATE_HISTORY, line.id)
                } else {
                    volatileSelectionAnchor(index)
                }
            }
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
            val primaryVisible = if (terminalScreen != null) {
                !tmuxHistoryActive && !terminalScreenIsAlternate
            } else {
                !alternateScreen
            }
            if (!primaryVisible) {
                null
            } else {
                buffer.indexOfId(anchor.id)
            }
        }
        TerminalLineSpace.ALTERNATE_HISTORY -> {
            if (terminalScreen == null || !tmuxHistoryActive && !terminalScreenIsAlternate) {
                null
            } else {
                activeAlternateScrollback().indexOfId(anchor.id)
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

    /** Viewport row ordinal; stable selection anchors continue to use each line's sparse ID. */
    fun oldestLineId(): Long? = when {
        tmuxHistoryActive -> tmuxScrollbackOrCreate().oldestRowOrdinal()
        terminalScreenIsAlternate -> activeAlternateScrollback().oldestRowOrdinal()
        alternateScreen -> null
        else -> buffer.oldestRowOrdinal()
    }

    fun pendingLineCount(): Int = synchronized(queueLock) {
        outputQueue.size + primaryTerminalScrollbackQueue.size + alternateTerminalScrollbackQueue.size +
            tmuxTerminalScrollbackQueue.size
    }

    private fun activeAlternateScrollback(): TerminalBuffer =
        if (tmuxHistoryActive) tmuxScrollbackOrCreate() else alternateScrollback

    private fun tmuxScrollbackOrCreate(): TerminalBuffer =
        tmuxScrollback ?: TerminalBuffer(
            capacity = ModelLimits.MAX_SCROLLBACK_LINES,
            initialNextId = ModelLimits.MAX_SCROLLBACK_LINES.toLong(),
        ).also {
            tmuxScrollback = it
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
        var requestTmuxHistoryBootstrap = false
        var restoreTmuxViewportY: Float? = null
        var tmuxSnapshotRequiresLiveBottom = false
        val hasMore = synchronized(queueLock) {
            val previousTerminalScreen = terminalScreen
            val previousCursor = cursor
            val batch = outputQueue.drain(MAX_LINES_PER_FRAME)
            val primaryTerminalScrollback = primaryTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            val alternateTerminalLines = alternateTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            val deferTmuxTerminalLines =
                activeTmuxHistoryReconciliation != null || pendingTmuxHistorySnapshots.isNotEmpty()
            val tmuxTerminalLines = if (deferTmuxTerminalLines) {
                emptyList()
            } else {
                tmuxTerminalScrollbackQueue.drain(MAX_LINES_PER_FRAME)
            }
            val screen = pendingScreen
            pendingScreen = null
            val nextLiveLine = pendingLiveLine
            val updateLiveLine = hasPendingLiveLine
            pendingLiveLine = null
            hasPendingLiveLine = false
            val terminalFrame = pendingTerminalFrame
            pendingTerminalFrame = null
            frameScheduled = false

            if (batch.isNotEmpty()) buffer.append(batch)
            if (primaryTerminalScrollback.isNotEmpty()) buffer.append(primaryTerminalScrollback)
            terminalScreenModeChanged =
                terminalFrame != null && terminalFrame.alternateScreen != terminalScreenIsAlternate
            val enteringAlternate = terminalFrame?.alternateScreen == true && !terminalScreenIsAlternate
            if (enteringAlternate) {
                alternateScrollback.clear()
                if (tmuxOuterScreenIsAlternate != false) {
                    activeTmuxHistoryReconciliation?.snapshot?.let {
                        pendingTmuxHistorySnapshots.addFirst(it)
                    }
                    activeTmuxHistoryReconciliation = null
                    tmuxScrollback = null
                    tmuxHistoryPaneId = null
                    tmuxHistoryMetadataKnown = false
                    tmuxInteractionMetadataFresh = false
                    tmuxRemoteMousePassthrough = true
                    tmuxInnerMouseTracking = false
                    tmuxPaneInMode = false
                    tmuxHistoryTruncatedBefore = false
                    resetTmuxHistoryRange()
                }
            }
            val tmuxReconciliation = processTmuxHistoryReconciliationFrame(
                preserveViewport = !enteringAlternate,
            )
            when (tmuxReconciliation.viewportDirective) {
                TmuxViewportDirective.NONE -> Unit
                TmuxViewportDirective.RESTORE_READER_POSITION -> {
                    restoreTmuxViewportY = viewport.scrollY
                }
                TmuxViewportDirective.LIVE_BOTTOM -> tmuxSnapshotRequiresLiveBottom = true
            }
            requestTmuxHistoryBootstrap = tmuxReconciliation.requestHistoryBootstrap
            if (alternateTerminalLines.isNotEmpty() && rendererProfile.preserveAlternateScreenHistory) {
                alternateScrollback.append(alternateTerminalLines)
            }
            if (
                tmuxTerminalLines.isNotEmpty() && tmuxHistoryActive &&
                tmuxHistoryMetadataKnown && !tmuxRemoteMousePassthrough
            ) {
                val history = tmuxScrollbackOrCreate()
                val droppedRows =
                    (history.lineCount() + tmuxTerminalLines.size - history.capacity).coerceAtLeast(0)
                history.append(tmuxTerminalLines)
                val droppedArchivedRows = minOf(droppedRows, tmuxArchivedHistoryRows)
                tmuxArchivedHistoryRows -= droppedArchivedRows
                val droppedCurrentRows = droppedRows - droppedArchivedRows
                tmuxHistoryCapturedStartRow += droppedCurrentRows
                tmuxHistoryOldestAvailableRow += droppedCurrentRows
                tmuxRemoteHistoryRows =
                    (tmuxRemoteHistoryRows.toLong() + tmuxTerminalLines.size)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                tmuxHistoryTruncatedBefore = tmuxHistoryCapturedStartRow > 0
                tmuxInteractionMetadataFresh = false
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
            if (tmuxReconciliation.completedSnapshot && tmuxOuterScreenIsAlternate == null) {
                tmuxOuterScreenIsAlternate = terminalScreen?.let { terminalScreenIsAlternate }
            }
            if (tmuxOuterTerminalExited && !terminalScreenIsAlternate) {
                tmuxHistoryActive = false
                tmuxScrollback = null
                tmuxHistoryPaneId = null
                tmuxHistoryTruncatedBefore = false
                resetTmuxHistoryRange()
            }
            contentChanged =
                batch.isNotEmpty() || primaryTerminalScrollback.isNotEmpty() ||
                alternateTerminalLines.isNotEmpty() || tmuxTerminalLines.isNotEmpty() ||
                tmuxReconciliation.completedSnapshot || screen != null ||
                updateLiveLine || terminalFrame != null
            if (terminalScreen != null && contentChanged) {
                cursor = terminalCursor.copy(
                    row = if (tmuxHistoryActive) {
                        tmuxScrollbackOrCreate().lineCount() + terminalCursor.row
                    } else if (terminalScreenIsAlternate) {
                        alternateScrollback.lineCount() + terminalCursor.row
                    } else {
                        buffer.lineCount() + terminalCursor.row
                    },
                )
            }
            if (contentChanged) bumpSelectionContentRevision()

            val structuralChange =
                batch.isNotEmpty() || primaryTerminalScrollback.isNotEmpty() ||
                    alternateTerminalLines.isNotEmpty() || tmuxTerminalLines.isNotEmpty() ||
                    tmuxReconciliation.completedSnapshot || screen != null || updateLiveLine ||
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
                !alternateTerminalScrollbackQueue.isEmpty || !tmuxTerminalScrollbackQueue.isEmpty ||
                pendingTmuxHistorySnapshots.isNotEmpty() ||
                activeTmuxHistoryReconciliation != null ||
                pendingScreen != null ||
                hasPendingLiveLine || pendingTerminalFrame != null
        }
        if (terminalScreenModeChanged) viewport.jumpToBottom()
        if (contentChanged) notifyContentChanged(contentChange)
        when {
            tmuxSnapshotRequiresLiveBottom -> viewport.jumpToBottom()
            rendererProfile.keepViewportPositionOnOutput -> {
                restoreTmuxViewportY?.let(viewport::scrollTo)
            }
        }
        bellEvent?.let { event -> bellListeners.forEach { listener -> listener.onTerminalBell(event) } }
        if (requestTmuxHistoryBootstrap) tmuxHistoryRefreshListener?.invoke(true)
        if (hasMore) scheduleFrame()
    }

    private fun processTmuxHistoryReconciliationFrame(
        preserveViewport: Boolean,
    ): TmuxReconciliationFrameResult {
        var remaining = MAX_TMUX_RECONCILIATION_ROW_WORK_PER_FRAME
        var completedSnapshot = false
        var requestHistoryBootstrap = false
        var viewportDirective = TmuxViewportDirective.NONE

        while (remaining > 0) {
            val reconciliation = activeTmuxHistoryReconciliation ?: run {
                val snapshot = pendingTmuxHistorySnapshots.pollFirst() ?: break
                TmuxHistoryReconciliation(
                    snapshot = snapshot,
                    base = tmuxScrollbackOrCreate(),
                    context = TmuxHistoryReconciliationContext(
                        paneId = tmuxHistoryPaneId,
                        metadataKnown = tmuxHistoryMetadataKnown,
                        remoteHistoryRows = tmuxRemoteHistoryRows,
                        capturedStartRow = tmuxHistoryCapturedStartRow,
                        oldestAvailableRow = tmuxHistoryOldestAvailableRow,
                        archivedRows = tmuxArchivedHistoryRows,
                    ),
                ).also { activeTmuxHistoryReconciliation = it }
            }
            val step = reconciliation.step(remaining)
            remaining -= step.rowWork
            if (!step.complete) break

            activeTmuxHistoryReconciliation = null
            completedSnapshot = true
            val directive = commitPreparedTmuxHistory(reconciliation, preserveViewport)
            viewportDirective = when {
                directive == TmuxViewportDirective.LIVE_BOTTOM -> directive
                viewportDirective == TmuxViewportDirective.NONE -> directive
                else -> viewportDirective
            }
            val snapshot = reconciliation.snapshot
            requestHistoryBootstrap = requestHistoryBootstrap ||
                !snapshot.historyIncluded &&
                (!tmuxHistoryMetadataKnown || tmuxRemoteHistoryRows != snapshot.remoteHistoryRows)

            // A metadata-only result consumes no row work. The queue is capped, so continuing
            // cannot spin indefinitely and avoids stretching cheap state updates across frames.
            if (step.rowWork == 0 && pendingTmuxHistorySnapshots.isEmpty()) break
        }

        val used = MAX_TMUX_RECONCILIATION_ROW_WORK_PER_FRAME - remaining
        if (used > 0 || activeTmuxHistoryReconciliation != null) {
            tmuxReconciliationWorkObserver?.invoke(used)
        }
        return TmuxReconciliationFrameResult(
            completedSnapshot = completedSnapshot,
            requestHistoryBootstrap = requestHistoryBootstrap,
            viewportDirective = viewportDirective,
        )
    }

    private fun commitPreparedTmuxHistory(
        reconciliation: TmuxHistoryReconciliation,
        preserveViewport: Boolean,
    ): TmuxViewportDirective {
        val snapshot = reconciliation.snapshot
        val prepared = reconciliation.completedResult()
        if (snapshot.olderPage) {
            if (prepared.acceptedOlderPage && prepared.replacement != null) {
                tmuxScrollback = prepared.replacement
                tmuxHistoryCapturedStartRow = prepared.capturedStartRow
                tmuxHistoryTruncatedBefore =
                    tmuxHistoryCapturedStartRow > tmuxHistoryOldestAvailableRow ||
                    tmuxHistoryOldestAvailableRow > 0
            }
            return TmuxViewportDirective.NONE
        }

        val viewportDirective = when {
            reconciliation.paneChanged -> TmuxViewportDirective.LIVE_BOTTOM
            reconciliation.replaceHistory && preserveViewport && !viewport.autoFollow ->
                TmuxViewportDirective.RESTORE_READER_POSITION
            else -> TmuxViewportDirective.NONE
        }
        if (reconciliation.paneChanged && !snapshot.historyIncluded) {
            prepared.replacement?.let { tmuxScrollback = it }
            tmuxArchivedHistoryRows = 0
            tmuxHistoryMetadataKnown = false
        } else if (reconciliation.replaceHistory) {
            prepared.replacement?.let { tmuxScrollback = it }
            tmuxArchivedHistoryRows = prepared.archivedRows
            tmuxHistoryCapturedStartRow = prepared.capturedStartRow
            tmuxHistoryMetadataKnown = true
            tmuxHistoryOldestAvailableRow = snapshot.oldestAvailableRow
            tmuxRemoteHistoryRows = snapshot.remoteHistoryRows
        }
        tmuxHistoryActive = true
        val historyMatchesRemote = tmuxHistoryMetadataKnown &&
            tmuxRemoteHistoryRows == snapshot.remoteHistoryRows
        tmuxInteractionMetadataFresh = snapshot.interactionMetadataFresh && historyMatchesRemote
        tmuxRemoteMousePassthrough = snapshot.remoteMousePassthrough
        tmuxInnerMouseTracking = snapshot.mouseTrackingActive
        tmuxPaneInMode = snapshot.paneInMode
        tmuxHistoryPaneId = snapshot.paneId
        if (snapshot.historyIncluded) {
            tmuxHistoryTruncatedBefore = if (prepared.reconciledHistory) {
                tmuxHistoryCapturedStartRow > tmuxHistoryOldestAvailableRow
            } else {
                snapshot.truncatedBefore
            }
        }
        return viewportDirective
    }

    private data class TmuxReconciliationFrameResult(
        val completedSnapshot: Boolean,
        val requestHistoryBootstrap: Boolean,
        val viewportDirective: TmuxViewportDirective,
    )

    private fun resetTmuxHistoryRange() {
        tmuxArchivedHistoryRows = 0
        tmuxHistoryCapturedStartRow = 0
        tmuxHistoryOldestAvailableRow = 0
        tmuxRemoteHistoryRows = 0
    }

    private enum class TmuxViewportDirective {
        NONE,
        RESTORE_READER_POSITION,
        LIVE_BOTTOM,
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
        val historyRows = if (tmuxHistoryActive) {
            tmuxScrollbackOrCreate().lineCount()
        } else if (frame.alternateScreen) {
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
        private const val MAX_PENDING_TMUX_SNAPSHOTS = 8
        private const val TMUX_HISTORY_PREFETCH_ROWS = 96
        private const val MAX_LINES_PER_FRAME = 2_000
        internal const val MAX_TMUX_RECONCILIATION_ROW_WORK_PER_FRAME = 2_000
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
