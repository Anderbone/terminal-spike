package com.yanjiyu.terminalspike.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.settings.AiAgent
import com.yanjiyu.terminalspike.settings.AiAgents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the real ViewModel and process-owned DataStore while another authority owner holds it.
 *
 * The v2 Compose rule replaces Dispatchers.Main with a test dispatcher that advances only while
 * the instrumentation thread drives it (waitUntil/waitForIdle). App coroutines on that dispatcher
 * can hold the authority gate, so this thread must never block (runBlocking/await) on the gate,
 * on Main work or on anything waiting behind them: every such wait goes through [drive].
 */
class RuntimeSettingsAuthorityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // The container is process-owned. Reading it from the Application avoids
    // ActivityScenario.onActivity, whose off-main-thread waitForIdleSync() is unbounded and
    // was reached from IO workers while this test deliberately holds the authority gate.
    private val container get() = (
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as TerminalSpikeApplication
        ).container
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun cancelScopes() {
        io.cancel()
        main.cancel()
    }

    /** Runs [block] on IO and waits for it while driving the Compose test dispatcher. */
    private fun <T> drive(timeoutMillis: Long = 10_000, block: suspend () -> T): T {
        val work = io.async { block() }
        compose.waitUntil(timeoutMillis) { work.isCompleted }
        // Already completed: this only returns the value or rethrows the failure.
        return runBlocking { work.await() }
    }

    private fun readyModel(): TerminalSpikeViewModel {
        lateinit var model: TerminalSpikeViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[TerminalSpikeViewModel::class.java] }
        compose.waitUntil(15_000) { model.uiState.value.settingsReady }
        return model
    }

    @Test
    fun aiAgentSaveWaitsForAuthorityAndPublishesAfterRelease() {
        val model = readyModel()
        val original = drive { container.settings.settings.first() }
        val edited = AiAgents(listOf(AiAgent("authority-fixture", "Authority fixture", "fixture-command")))
        try {
            val save = CompletableDeferred<Boolean>()
            assertSerialized(
                start = { main.launch { save.complete(model.saveAiAgents(edited)) } },
                written = { save.isCompleted },
            )
            assertTrue(drive { save.await() })
            assertEquals(edited.toProto(), drive { container.settings.settings.first() }.aiAgents)
        } finally {
            drive {
                container.settingsMutations.mutate {
                    write {
                        container.settings.update {
                            if (original.hasAiAgents()) it.setAiAgents(original.aiAgents) else it.clearAiAgents()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun inputButtonSideWaitsForAuthorityAndPublishesAfterRelease() {
        val model = readyModel()
        val original = drive { container.settings.settings.first().inputButtonsOnRight }
        try {
            val published = io.async { container.settings.settings.first { it.inputButtonsOnRight == !original } }
            assertSerialized(
                start = { compose.runOnIdle { model.setInputButtonsOnRight(!original) } },
                written = { published.isCompleted },
            )
            assertEquals(!original, drive { container.settings.settings.first().inputButtonsOnRight })
        } finally {
            drive {
                container.settingsMutations.mutate {
                    write { container.settings.update { it.setInputButtonsOnRight(original) } }
                }
            }
        }
    }

    private fun assertSerialized(start: () -> Unit, written: () -> Boolean) {
        val held = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val owner = io.launch {
            container.authoritativeData.withMutation {
                held.complete(generation)
                release.await()
            }
        }
        val completedWhileOwned = try {
            val before = drive { held.await() }
            val completedWhileOwned = try {
                start()
                try {
                    // Drives the Compose test dispatcher, so a Main-launched write can reach the gate.
                    compose.waitUntil(750) { written() }
                    true
                } catch (_: ComposeTimeoutException) {
                    false
                }
            } finally {
                // Release before any wait, including after a failed acquisition, so the fixture
                // owner can never keep the gate for the rest of the test.
                release.complete(Unit)
            }
            compose.waitUntil(10_000) { owner.isCompleted }
            compose.waitUntil(10_000) { written() }
            assertTrue(
                "A durable runtime edit must advance the authority generation",
                drive { container.authoritativeData.withRead { it } } > before,
            )
            completedWhileOwned
        } finally {
            release.complete(Unit)
        }
        assertFalse("Runtime settings must wait for the current authority owner", completedWhileOwned)
    }
}
