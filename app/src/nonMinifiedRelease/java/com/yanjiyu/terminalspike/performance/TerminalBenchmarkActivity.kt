package com.yanjiyu.terminalspike.performance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.TerminalContentListener
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.TmuxLocalHistorySnapshot
import com.yanjiyu.terminalspike.terminal.benchmark.TerminalBenchmarkFixtureCatalog
import com.yanjiyu.terminalspike.terminal.benchmark.TerminalBenchmarkFixtureGenerator
import com.yanjiyu.terminalspike.terminal.benchmark.TerminalFixtureSpec
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.TerminalModes
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Test-target activity present only in the Baseline Profile plugin's non-minified release variant. */
class TerminalBenchmarkActivity : Activity() {
    private val fixtureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val drainSignal = Semaphore(0)
    private val controller = TerminalController(TerminalBuffer(TerminalBuffer.DEFAULT_CAPACITY))
    private lateinit var terminalView: FastTerminalView
    private lateinit var scenario: TerminalFixtureSpec
    private var completionArmed = false
    private var observedFrameAfterProduction = false
    private var publishedCompletion = false
    private var liveReconciliation = false
    private var reconciliationSeeded = false
    private var reconciliationRequested = false

    private val contentListener = TerminalContentListener {
        drainSignal.release()
        if (liveReconciliation) {
            publishReconciliationStateIfReady()
            return@TerminalContentListener
        }
        if (completionArmed) {
            observedFrameAfterProduction = true
            publishCompletionIfDrained()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        liveReconciliation = intent.getBooleanExtra(EXTRA_LIVE_RECONCILIATION, false)
        scenario = loadScenario(
            intent.getStringExtra(EXTRA_SCENARIO_ID) ?: LIVE_RECONCILIATION_SCENARIO,
        )
        terminalView = FastTerminalView(this).apply {
            contentDescription = runningDescription(scenario.id)
            attachController(controller)
        }
        setContentView(terminalView)
        controller.addListener(contentListener)
        controller.start()
        if (liveReconciliation) {
            fixtureExecutor.execute(::seedLiveReconciliation)
        } else {
            fixtureExecutor.execute(::produceFixture)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (
            liveReconciliation && intent.getBooleanExtra(EXTRA_TRIGGER_RECONCILIATION, false) &&
            !reconciliationRequested
        ) {
            reconciliationRequested = true
            terminalView.contentDescription = reconciliationRunningDescription()
            fixtureExecutor.execute(::stageLiveCoordinateReset)
        }
    }

    override fun onDestroy() {
        controller.removeListener(contentListener)
        controller.stop()
        fixtureExecutor.shutdownNow()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun produceFixture() {
        val engine = VtTerminalEngine(columns = scenario.columns, rows = scenario.rows)
        TerminalBenchmarkFixtureGenerator.chunks(scenario).forEach { chunk ->
            if (Thread.currentThread().isInterrupted) return
            controller.updateTerminalFrame(engine.accept(chunk))
            if (!awaitControllerCapacity()) return
        }
        runOnUiThread {
            completionArmed = true
            terminalView.postOnAnimation {
                observedFrameAfterProduction = true
                publishCompletionIfDrained()
            }
        }
    }

    private fun seedLiveReconciliation() {
        val history = List(LIVE_RECONCILIATION_HISTORY_ROWS) { index ->
            TerminalLine.plain("reconciliation-old-$index")
        }
        controller.setInputSink(
            sink = object : TerminalInputSink {
                override fun send(bytes: ByteArray) = Unit
            },
            onResize = { _, _ -> },
            isTmuxSession = { true },
        )
        controller.updateTerminalFrame(
            TerminalFrameUpdate(
                completedScrollback = emptyList(),
                screen = listOf(TerminalLine.plain("live")),
                cursor = TerminalCursor(row = 0, column = 4, visible = true),
                alternateScreen = false,
                modes = TerminalModes(),
            ),
        )
        controller.stageTmuxHistory(
            reconciliationSnapshot(
                lines = history,
                remoteHistoryRows = history.size,
            ),
        )
    }

    private fun stageLiveCoordinateReset() {
        val overlapStart = LIVE_RECONCILIATION_HISTORY_ROWS - LIVE_RECONCILIATION_OVERLAP_ROWS
        val lines = ArrayList<TerminalLine>(LIVE_RECONCILIATION_RESET_ROWS)
        repeat(LIVE_RECONCILIATION_OVERLAP_ROWS) { index ->
            lines += TerminalLine.plain("reconciliation-old-${overlapStart + index}")
        }
        repeat(LIVE_RECONCILIATION_NEW_ROWS) { index ->
            lines += TerminalLine.plain("reconciliation-new-$index")
        }
        controller.stageTmuxHistory(
            reconciliationSnapshot(
                lines = lines,
                remoteHistoryRows = lines.size,
            ),
        )
    }

    private fun reconciliationSnapshot(
        lines: List<TerminalLine>,
        remoteHistoryRows: Int,
    ) = TmuxLocalHistorySnapshot(
        sessionId = "\$benchmark",
        paneId = "%benchmark",
        lines = lines,
        remoteHistoryRows = remoteHistoryRows,
        remoteMousePassthrough = false,
        historyIncluded = true,
        authoritative = true,
        truncatedBefore = false,
    )

    private fun publishReconciliationStateIfReady() {
        when {
            !reconciliationSeeded &&
                controller.lineCount() == LIVE_RECONCILIATION_HISTORY_ROWS + 1 -> {
                reconciliationSeeded = true
                terminalView.contentDescription = reconciliationReadyDescription()
            }
            reconciliationRequested && !publishedCompletion &&
                controller.lineCount() ==
                LIVE_RECONCILIATION_HISTORY_ROWS + LIVE_RECONCILIATION_NEW_ROWS + 1 &&
                controller.lineAt(controller.lineCount() - 2)?.text ==
                "reconciliation-new-${LIVE_RECONCILIATION_NEW_ROWS - 1}" -> {
                publishedCompletion = true
                terminalView.contentDescription = reconciliationCompleteDescription()
            }
        }
    }

    private fun awaitControllerCapacity(): Boolean {
        while (controller.pendingLineCount() > MAX_PENDING_BEFORE_PRODUCER_WAIT) {
            drainSignal.drainPermits()
            if (controller.pendingLineCount() <= MAX_PENDING_BEFORE_PRODUCER_WAIT) return true
            try {
                drainSignal.tryAcquire(DRAIN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun publishCompletionIfDrained() {
        if (
            completionArmed && observedFrameAfterProduction && !publishedCompletion &&
            controller.pendingLineCount() == 0
        ) {
            publishedCompletion = true
            terminalView.contentDescription = completeDescription(scenario.id)
        }
    }

    private fun loadScenario(id: String): TerminalFixtureSpec {
        val catalog = assets.open(SCENARIO_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return TerminalBenchmarkFixtureCatalog.parse(catalog).singleOrNull { spec -> spec.id == id }
            ?: error("Unknown terminal performance scenario '$id'")
    }

    companion object {
        const val EXTRA_SCENARIO_ID = "terminal_fixture_scenario"
        const val EXTRA_LIVE_RECONCILIATION = "terminal_fixture_live_reconciliation"
        const val EXTRA_TRIGGER_RECONCILIATION = "terminal_fixture_trigger_reconciliation"
        const val SCENARIO_ASSET = "scenarios.tsv"
        private const val MAX_PENDING_BEFORE_PRODUCER_WAIT = 4_000
        private const val DRAIN_WAIT_MILLIS = 250L

        fun runningDescription(id: String): String = "Terminal fixture running: $id"

        fun completeDescription(id: String): String = "Terminal fixture complete: $id"

        fun reconciliationReadyDescription(): String = "Terminal reconciliation ready"

        fun reconciliationRunningDescription(): String = "Terminal reconciliation running"

        fun reconciliationCompleteDescription(): String = "Terminal reconciliation complete"

        private const val LIVE_RECONCILIATION_SCENARIO = "tmux-status"
        private const val LIVE_RECONCILIATION_HISTORY_ROWS = 20_480
        private const val LIVE_RECONCILIATION_OVERLAP_ROWS = 1_024
        private const val LIVE_RECONCILIATION_NEW_ROWS = 3_072
        private const val LIVE_RECONCILIATION_RESET_ROWS =
            LIVE_RECONCILIATION_OVERLAP_ROWS + LIVE_RECONCILIATION_NEW_ROWS
    }
}
