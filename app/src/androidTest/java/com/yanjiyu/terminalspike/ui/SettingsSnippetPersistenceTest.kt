package com.yanjiyu.terminalspike.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.TerminalSpikeComponentTestActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real ViewModel, Room, and authority gate; no terminal session or network fixture. */
class SettingsSnippetPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()
    private val container get() = (compose.activity.application as TerminalSpikeApplication).container

    private fun model(): TerminalSpikeViewModel {
        lateinit var model: TerminalSpikeViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[TerminalSpikeViewModel::class.java] }
        return model
    }

    @Test
    fun saveAcknowledgesOnlyAfterAuthorityReleaseAndDurablePublication() = runBlocking {
        val model = model()
        compose.waitUntil(15_000) { model.uiState.value.settingsReady }
        val before = container.terminalDataRepository.load().settings.snippets
        val held = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val owner = launch(Dispatchers.IO) {
            container.authoritativeData.withMutation {
                held.complete(generation)
                release.await()
            }
        }
        val generation = withTimeout(10_000) { held.await() }
        val attempted = CompletableDeferred<Unit>()
        val save = async(Dispatchers.Main.immediate) {
            attempted.complete(Unit)
            model.saveSnippet("Durable fixture", "printf fixture", false, existingId = -1)
        }
        try {
            compose.waitUntil(10_000) { attempted.isCompleted }
            val prematurelyCompleted = try {
                compose.waitUntil(750) { save.isCompleted }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
            assertFalse("Save must not acknowledge while another authority owner holds the catalog", prematurelyCompleted)
            assertEquals(before, model.uiState.value.snippets)
        } finally {
            release.complete(Unit)
            owner.join()
        }
        compose.waitUntil(10_000) { save.isCompleted }
        assertTrue(save.await().isSuccess)
        val durable = container.terminalDataRepository.load().settings.snippets
        val inserted = durable.single { it.label == "Durable fixture" }
        assertTrue(inserted.id > 0)
        assertEquals("printf fixture", inserted.command)
        assertFalse(inserted.appendEnter)
        assertEquals(durable, model.uiState.value.snippets)
        val catalog = requireNotNull(container.terminalDataRepository.loadCatalog().catalog)
        assertTrue(-1L in catalog.hiddenDefaultSnippetIds)
        assertEquals(catalog.hiddenDefaultSnippetIds, model.uiState.value.hiddenDefaultSnippetIds)
        assertTrue(container.authoritativeData.withRead { it } > generation)
    }

    @Test
    fun validationFailuresReturnFailureWithoutChangingDurableSnippets() = runBlocking {
        val model = model()
        compose.waitUntil(15_000) { model.uiState.value.settingsReady }
        val before = container.terminalDataRepository.load().settings.snippets
        val generation = container.authoritativeData.withRead { it }
        for ((label, command) in listOf("" to "pwd", "bad\u0007name" to "pwd", "Fixture" to "", "Fixture" to "bad\u0007command")) {
            val save = async(Dispatchers.Main.immediate) { model.saveSnippet(label, command, false) }
            compose.waitUntil(10_000) { save.isCompleted }
            assertTrue(save.await().isFailure)
            assertEquals(before, container.terminalDataRepository.load().settings.snippets)
            assertEquals(before, model.uiState.value.snippets)
        }
        assertEquals(generation, container.authoritativeData.withRead { it })
    }

    @Test
    fun savingBeforeTheCatalogLoadsReturnsFailureWithoutWriting() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch(Dispatchers.IO) {
            container.authoritativeData.withMutation {
                held.complete(Unit)
                release.await()
            }
        }
        withTimeout(10_000) { held.await() }
        val model = model()
        try {
            assertFalse(model.uiState.value.settingsReady)
            val save = async(Dispatchers.Main.immediate) { model.saveSnippet("Not ready", "pwd", false) }
            compose.waitUntil(10_000) { save.isCompleted }
            assertTrue(save.await().isFailure)
        } finally {
            release.complete(Unit)
            owner.join()
        }
        compose.waitUntil(15_000) { model.uiState.value.settingsReady }
        assertFalse(container.terminalDataRepository.load().settings.snippets.any { it.label == "Not ready" })
    }
}
