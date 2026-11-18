package com.yanjiyu.terminalspike.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.settings.AiAgent
import com.yanjiyu.terminalspike.settings.AiAgents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real ViewModel and process-owned DataStore while another authority owner holds it. */
class RuntimeSettingsAuthorityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as TerminalSpikeApplication).container

    private fun readyModel(): TerminalSpikeViewModel {
        lateinit var model: TerminalSpikeViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[TerminalSpikeViewModel::class.java] }
        compose.waitUntil(15_000) { model.uiState.value.settingsReady }
        return model
    }

    @Test
    fun aiAgentSaveWaitsForAuthorityAndPublishesAfterRelease() = runBlocking {
        val model = readyModel()
        val original = container.settings.settings.first()
        val edited = AiAgents(listOf(AiAgent("authority-fixture", "Authority fixture", "fixture-command")))
        try {
            lateinit var save: Deferred<Boolean>
            assertSerialized(
                start = { save = async(Dispatchers.Main.immediate) { model.saveAiAgents(edited) } },
                awaitWrite = { assertTrue(save.await()) },
            )
            assertEquals(edited.toProto(), container.settings.settings.first().aiAgents)
        } finally {
            withContext(NonCancellable) {
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
    fun inputButtonSideWaitsForAuthorityAndPublishesAfterRelease() = runBlocking {
        val model = readyModel()
        val original = container.settings.settings.first().inputButtonsOnRight
        try {
            assertSerialized(
                start = { withContext(Dispatchers.Main.immediate) { model.setInputButtonsOnRight(!original) } },
                awaitWrite = { container.settings.settings.first { it.inputButtonsOnRight == !original } },
            )
            assertEquals(!original, container.settings.settings.first().inputButtonsOnRight)
        } finally {
            withContext(NonCancellable) {
                container.settingsMutations.mutate {
                    write { container.settings.update { it.setInputButtonsOnRight(original) } }
                }
            }
        }
    }

    private suspend fun assertSerialized(
        start: suspend CoroutineScope.() -> Unit,
        awaitWrite: suspend () -> Unit,
    ) = coroutineScope {
        val held = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val owner = launch(Dispatchers.IO) {
            container.authoritativeData.withMutation {
                held.complete(generation)
                release.await()
            }
        }
        val before = withTimeout(10_000) { held.await() }
        val completedWhileOwned: Boolean
        try {
            start()
            val observed = async(Dispatchers.IO) { awaitWrite() }
            completedWhileOwned = try {
                // Drive the Compose test dispatcher too; a sleeping instrumentation thread can
                // otherwise postpone a Main-launched write until after the lock is released.
                compose.waitUntil(750) { observed.isCompleted }
                observed.await()
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
        } finally {
            withContext(NonCancellable) { release.complete(Unit); owner.join() }
        }
        withTimeout(10_000) { awaitWrite() }
        assertFalse("Runtime settings must wait for the current authority owner", completedWhileOwned)
        val committed = async(Dispatchers.IO) { container.authoritativeData.withRead { it } }
        compose.waitUntil(5_000) { committed.isCompleted }
        assertTrue("A durable runtime edit must advance the authority generation", committed.await() > before)
    }
}
