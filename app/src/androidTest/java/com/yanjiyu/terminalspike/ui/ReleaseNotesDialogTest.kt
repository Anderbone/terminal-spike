package com.yanjiyu.terminalspike.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.repository.SettingsMutationCoordinator
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsSerializer
import com.yanjiyu.terminalspike.ui.releasenotes.ReleaseNoteSection
import com.yanjiyu.terminalspike.ui.releasenotes.ReleaseNotes
import com.yanjiyu.terminalspike.ui.releasenotes.ReleaseNotesDialog
import com.yanjiyu.terminalspike.ui.releasenotes.ReleaseNotesUpdateHost
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseNotesDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unreadUpdatesWaitForIdleContentAndStayDismissedAfterRecreation() {
        val fixture = SettingsFixture()
        val repository = fixture.repository
        val release = CompletableDeferred<Unit>()
        val ready = mutableStateOf(false)
        val idle = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        try {
            runBlocking { repository.initializeReleaseNotes(0) }
            composeRule.setContent {
                TerminalSpikeTheme(updateSystemBarIcons = false) {
                    if (mounted.value) ReleaseNotesUpdateHost(repository, fixture.mutations, ready.value, idle.value)
                }
            }
            composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
            composeRule.runOnIdle { ready.value = true }
            composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
            composeRule.runOnIdle { idle.value = true }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("What’s new")).fetchSemanticsNodes().isNotEmpty()
            }
            val held = CompletableDeferred<Unit>()
            val owner = fixture.scope.launch {
                fixture.gate.withMutation { held.complete(Unit); release.await() }
            }
            runBlocking { withTimeout(5_000) { held.await() } }
            composeRule.onNodeWithText("Got it").performClick()
            val observed = fixture.scope.async {
                repository.settings.first { it.releaseNotesSeenRevision > 0 }
            }
            val acknowledgedWhileOwned = observedWhileOwned(observed)
            release.complete(Unit)
            runBlocking { owner.join() }
            composeRule.waitUntil(5_000) {
                runBlocking { repository.settings.first().releaseNotesSeenRevision > 0 }
            }
            assertFalse("Acknowledgement must wait for the authority owner", acknowledgedWhileOwned)
            assertTrue(awaitCommittedGeneration(fixture) > 0L)
            composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
            composeRule.runOnIdle { mounted.value = false }
            composeRule.runOnIdle { mounted.value = true }
            composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
        } finally {
            composeRule.runOnIdle { mounted.value = false }
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun initialUpdateBaselineWaitsForAuthorityBeforePersisting() {
        val fixture = SettingsFixture()
        val mounted = mutableStateOf(true)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val owner = fixture.scope.launch {
                fixture.gate.withMutation { held.complete(Unit); release.await() }
            }
            runBlocking { withTimeout(5_000) { held.await() } }
            composeRule.setContent {
                TerminalSpikeTheme(updateSystemBarIcons = false) {
                    if (mounted.value) ReleaseNotesUpdateHost(
                        fixture.repository, fixture.mutations, ready = true, canShow = false,
                    )
                }
            }
            val observed = fixture.scope.async {
                fixture.repository.settings.first { it.hasReleaseNotesSeenRevision() }
            }
            val initializedWhileOwned = observedWhileOwned(observed)
            release.complete(Unit)
            runBlocking { owner.join() }
            composeRule.waitUntil(5_000) {
                runBlocking { fixture.repository.settings.first().hasReleaseNotesSeenRevision() }
            }
            assertFalse("Initial education baseline must wait for the authority owner", initializedWhileOwned)
            assertTrue(awaitCommittedGeneration(fixture) > 0L)
        } finally {
            composeRule.runOnIdle { mounted.value = false }
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun aboutHistoryCanBeClosedAndReopened() {
        composeRule.setContent { TerminalSpikeTheme(updateSystemBarIcons = false) { AboutSection() } }
        repeat(2) {
            composeRule.onNodeWithText("View update history").performScrollTo().performClick()
            composeRule.onNodeWithText("Update history").assertIsDisplayed()
            composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun upgradeShowsUnreadNotesAndCanExpandToHistoryWithoutStackingDialogs() {
        val visible = mutableStateOf(true)
        val notes = listOf(release(2), release(1))
        composeRule.setContent {
            TerminalSpikeTheme(updateSystemBarIcons = false) {
                if (visible.value) ReleaseNotesDialog(
                    notes = notes.take(1), history = notes, onDismiss = { visible.value = false },
                )
            }
        }
        composeRule.onNodeWithText("What’s new").assertIsDisplayed()
        composeRule.onNodeWithText("Version 1.1").assertDoesNotExist()
        composeRule.onNodeWithText("View update history").performClick()
        composeRule.onNodeWithText("Update history").assertIsDisplayed()
        composeRule.onNodeWithTag("release-notes-list").performScrollToNode(hasText("Version 1.1"))
        composeRule.onNodeWithText("Version 1.1").assertIsDisplayed()
        composeRule.onNodeWithText("Got it").performClick()
        composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
    }

    @Test
    fun longHistoryAtLargeFontKeepsCloseReachable() {
        var dismissed = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TerminalSpikeTheme(updateSystemBarIcons = false) {
                    ReleaseNotesDialog(
                        notes = (20 downTo 1).map(::release), onDismiss = { dismissed = true },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("release-notes-list").performScrollToNode(hasText("Version 1.1"))
        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun failedAcknowledgementKeepsUpdatesVisibleAndOffersRetry() {
        val saving = mutableStateOf(true)
        var dismissedForNow = false
        composeRule.setContent {
            TerminalSpikeTheme(updateSystemBarIcons = false) {
                ReleaseNotesDialog(
                    notes = listOf(release(1)), history = listOf(release(1)),
                    saving = saving.value, saveFailed = !saving.value, onDismiss = {},
                    onDismissWithoutSaving = { dismissedForNow = true },
                )
            }
        }
        composeRule.onNodeWithText("Got it").assertIsNotEnabled()
        composeRule.runOnIdle { saving.value = false }
        composeRule.onNodeWithText("Could not save that you have read these updates. Please try again.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Got it").assertIsEnabled()
        composeRule.onNodeWithText("Not now").performClick()
        composeRule.runOnIdle { assertEquals(true, dismissedForNow) }
    }

    @Test
    fun failedAuthorityKeepsUnreadUpdatesVisibleWithoutAcknowledgingThem() {
        val fixture = SettingsFixture(startReady = false)
        val mounted = mutableStateOf(true)
        try {
            runBlocking {
                fixture.repository.initializeReleaseNotes(0)
                fixture.gate.resolveStartup({ true }, { false })
            }
            composeRule.setContent {
                TerminalSpikeTheme(updateSystemBarIcons = false) {
                    if (mounted.value) ReleaseNotesUpdateHost(
                        fixture.repository, fixture.mutations, ready = true, canShow = true,
                    )
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("What’s new")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Got it").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("Could not save that you have read these updates. Please try again."))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, runBlocking { fixture.repository.settings.first().releaseNotesSeenRevision })
            composeRule.onNodeWithText("Got it").assertIsEnabled()
            composeRule.onNodeWithText("Not now").performClick()
            composeRule.onNodeWithTag("release-notes-dialog").assertDoesNotExist()
        } finally {
            composeRule.runOnIdle { mounted.value = false }
            fixture.close()
        }
    }

    private fun awaitCommittedGeneration(fixture: SettingsFixture): Long {
        // A DataStore emission can precede updateData's return and markCommitted. Wait for the
        // authority owner to release its lock, advancing Compose frames so it can finish.
        val read = fixture.scope.async { fixture.gate.withRead { it } }
        composeRule.waitUntil(5_000) { read.isCompleted }
        return runBlocking { read.await() }
    }

    private fun observedWhileOwned(write: Deferred<*>): Boolean = try {
        composeRule.waitUntil(750) { write.isCompleted }
        runBlocking { write.await() }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }

    private class SettingsFixture(startReady: Boolean = true) : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        private val file = File(context.cacheDir, "release-notes-${UUID.randomUUID()}.pb")
        private val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val gate = AuthoritativeDataGate().also {
            if (startReady) runBlocking { it.resolveStartup({ false }, { true }) }
        }
        val mutations = SettingsMutationCoordinator(gate)
        val repository = AppSettingsRepository(DataStoreFactory.create(
            serializer = AppSettingsSerializer, scope = scope, produceFile = { file },
        ))

        override fun close() {
            runBlocking { job.cancelAndJoin() }
            check(file.delete() || !file.exists()) { "Release notes fixture was not removed." }
        }
    }

    private fun release(revision: Int) = ReleaseNotes(
        revision, "1.$revision",
        listOf(ReleaseNoteSection(R.string.release_notes_added, listOf(R.string.release_notes_1_updates))),
    )
}
