package com.yanjiyu.terminalspike.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TerminalFixtureMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun plainTextAndLargeScrollbackFrames() = measureFixture("plain-100k")

    @Test
    fun ansiHeavyFrames() = measureFixture("ansi-heavy")

    @Test
    fun rapidCursorFrames() = measureFixture("cursor-redraw")

    @Test
    fun fullScreenRedrawFrames() = measureFixture("full-screen-redraw")

    @Test
    fun cjkAndWideCharacterFrames() = measureFixture("cjk-wide")

    @Test
    fun tmuxStatusFrames() = measureFixture("tmux-status")

    @Test
    fun tmuxLiveReconciliationFrames() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 3,
            setupBlock = {
                pressHome()
                startLiveReconciliationFixtureAndWait()
            },
        ) {
            triggerLiveReconciliationAndWait()
        }
    }

    private fun measureFixture(scenarioId: String) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 3,
            setupBlock = {
                pressHome()
                startTerminalFixtureAndWait(scenarioId)
            },
        ) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                12,
            )
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 4,
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                12,
            )
        }
    }
}

private fun MacrobenchmarkScope.startLiveReconciliationFixtureAndWait() {
    val intent = terminalFixtureIntent().apply {
        putExtra("terminal_fixture_scenario", "tmux-status")
        putExtra("terminal_fixture_live_reconciliation", true)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivityAndWait(intent)
    check(
        device.wait(
            Until.hasObject(By.desc("Terminal reconciliation ready")),
            FIXTURE_TIMEOUT_MILLIS,
        ),
    ) { "Timed out preparing the live tmux reconciliation fixture" }
}

private fun MacrobenchmarkScope.triggerLiveReconciliationAndWait() {
    val launch = device.executeShellCommand(
        "am start -W --activity-single-top " +
            "-n $TARGET_PACKAGE/.performance.TerminalBenchmarkActivity " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "--ez terminal_fixture_live_reconciliation true " +
            "--ez terminal_fixture_trigger_reconciliation true",
    )
    check(launch.contains("Status: ok")) {
        "Could not trigger the live tmux reconciliation fixture"
    }
    check(
        device.wait(
            Until.hasObject(By.desc("Terminal reconciliation complete")),
            FIXTURE_TIMEOUT_MILLIS,
        ),
    ) { "Timed out applying the live tmux reconciliation fixture" }
}

private fun MacrobenchmarkScope.startTerminalFixtureAndWait(scenarioId: String) {
    val intent = terminalFixtureIntent().apply {
        putExtra("terminal_fixture_scenario", scenarioId)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivityAndWait(intent)
    check(
        device.wait(
            Until.hasObject(By.desc("Terminal fixture complete: $scenarioId")),
            FIXTURE_TIMEOUT_MILLIS,
        ),
    ) { "Timed out replaying terminal fixture '$scenarioId'" }
}

private fun terminalFixtureIntent() = Intent(Intent.ACTION_MAIN).apply {
    setClassName(TARGET_PACKAGE, "$TARGET_PACKAGE.performance.TerminalBenchmarkActivity")
    addCategory(Intent.CATEGORY_LAUNCHER)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private const val FIXTURE_TIMEOUT_MILLIS = 180_000L
