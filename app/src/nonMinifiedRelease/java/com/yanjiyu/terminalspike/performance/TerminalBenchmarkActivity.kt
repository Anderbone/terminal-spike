package com.yanjiyu.terminalspike.performance

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.yanjiyu.terminalspike.terminal.TerminalContentListener
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.benchmark.TerminalBenchmarkFixtureCatalog
import com.yanjiyu.terminalspike.terminal.benchmark.TerminalBenchmarkFixtureGenerator
import com.yanjiyu.terminalspike.terminal.benchmark.TerminalFixtureSpec
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
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

    private val contentListener = TerminalContentListener {
        drainSignal.release()
        if (completionArmed) {
            observedFrameAfterProduction = true
            publishCompletionIfDrained()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scenario = loadScenario(requireNotNull(intent.getStringExtra(EXTRA_SCENARIO_ID)))
        terminalView = FastTerminalView(this).apply {
            contentDescription = runningDescription(scenario.id)
            attachController(controller)
        }
        setContentView(terminalView)
        controller.addListener(contentListener)
        controller.start()
        fixtureExecutor.execute(::produceFixture)
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
        const val SCENARIO_ASSET = "scenarios.tsv"
        private const val MAX_PENDING_BEFORE_PRODUCER_WAIT = 4_000
        private const val DRAIN_WAIT_MILLIS = 250L

        fun runningDescription(id: String): String = "Terminal fixture running: $id"

        fun completeDescription(id: String): String = "Terminal fixture complete: $id"
    }
}
