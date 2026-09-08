package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.ui.NewTerminalSessionTestTag
import com.yanjiyu.terminalspike.ui.TerminalSpikeViewModel
import com.yanjiyu.terminalspike.ui.settings.SettingsCategoryListContentDescription
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in full UI gate. Requires an isolated verification APK so it cannot reset a user's Arch. */
class LocalArchUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun installsFromNewSessionAndUsesTheExistingTerminalUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchUi") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        assertTrue("Use the isolated verification application ID", application.packageName.endsWith(".archverify"))
        val repository = application.container.localSessionRepository
        compose.waitUntil(20_000) { repository.environment.state.value.checked }
        val restarting = InstrumentationRegistry.getArguments().getString("localArchUiRestart") == "true"
        if (restarting) assertTrue("First-run UI gate must run before restart gate", repository.environment.state.value.installed)
        else assertFalse("First-run gate requires an uninstalled isolated UI environment", repository.environment.state.value.installed)
        report("Open New session → Local Arch Linux")
        compose.onNodeWithContentDescription("Open terminal").performClick()
        openLocal()
        if (!restarting) {
            compose.onNodeWithTag("local-arch-install").assertIsDisplayed()
            compose.onNodeWithText("Not installed").assertIsDisplayed()
            compose.onNodeWithText("Install Arch Linux").performClick()
            compose.onNodeWithTag("local-arch-install").assertDoesNotExist()
            compose.onNodeWithTag("local-arch-progress").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open settings").performClick()
            compose.onNodeWithContentDescription(SettingsCategoryListContentDescription).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("local-arch-progress").assertIsDisplayed()
            // Exercise real Android backgrounding while the application service owns the installer.
            backgroundAndReturn()
            assertTrue("Installer must survive leaving the app", repository.runtime.value.installationActive)
            report("Installer survived Settings navigation, activity recreation and Home; dialog is absent")
            compose.waitUntil(35 * 60_000) {
                !repository.runtime.value.installationActive
            }
            assertNull(repository.environment.state.value.error)
            assertNull(repository.runtime.value.error)
            assertTrue(repository.environment.state.value.starterToolsInstalled)
            compose.onNodeWithText("Open local shell").performClick()
            report("Starter tools installed; opened shell explicitly without automatic navigation")
        }
        compose.waitUntil(10 * 60_000) {
            repository.runtime.value.sessions.any { it.connectionState is ConnectionState.Connected } ||
                repository.environment.state.value.error != null
        }
        assertNull(repository.environment.state.value.error)
        compose.onNodeWithTag("terminal_container").assertIsDisplayed()
        var model = ViewModelProvider(compose.activity)[TerminalSpikeViewModel::class.java]
        val first = model.uiState.value.activeSessionId
        assertTrue(first < 0)
        if (restarting) {
            backgroundAndReturn()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            input("test \"\$(cat /root/projects/ui-persistence.txt)\" = ui-persistent && printf '\\nUI_%s\\n' RESTART_OK\n")
            awaitMarker(repository, first, "UI_RESTART_OK")
            report("A new app process opened the existing environment and read the project")
            repository.close(first)
            compose.waitUntil(20_000) { repository.runtime.value.sessions.isEmpty() }
            verifySettingsReset(repository, delete = true)
            return
        }
        input("for tool in git zoxide rg fd fzf bat eza jq nano less unzip zip curl ssh rsync make gcc node npm; do command -v \$tool || break; done; node --version; pacman --version && mkdir -p /root/projects && printf '%s\\n' ui-persistent > /root/projects/ui-persistence.txt && printf '\\nUI_%s\\n' SHELL_READY\n")
        awaitMarker(repository, first, "UI_SHELL_READY")
        assertTrue(transcript(repository, first).contains("Pacman v"))
        report("Real pacman output reached the existing renderer through its input connection")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        model = ViewModelProvider(compose.activity)[TerminalSpikeViewModel::class.java]
        compose.waitUntil(20_000) { model.uiState.value.activeSessionId == first }
        compose.onNodeWithTag("terminal_container").assertIsDisplayed()
        openLocal()
        compose.waitUntil(20_000) { repository.runtime.value.connectedCount == 2 }
        val second = model.uiState.value.activeSessionId
        assertTrue(second < 0 && second != first)
        input("test \"\$(cat /root/projects/ui-persistence.txt)\" = ui-persistent && printf '\\nUI_%s\\n' SHARED_FILES\n")
        awaitMarker(repository, second, "UI_SHARED_FILES")
        report("Two local tabs share files; activity recreation retained the selected shell")
        repository.close(first)
        input("printf '\\nUI_%s\\n' OTHER_TAB_CLOSED\n")
        awaitMarker(repository, second, "UI_OTHER_TAB_CLOSED")
        repository.close(second)
        compose.waitUntil(20_000) { repository.runtime.value.sessions.isEmpty() }
        verifySettingsReset(repository, delete = false)
    }

    private fun backgroundAndReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val taskId = compose.activity.taskId
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(5_000)
        instrumentation.runOnMainSync {
            val manager = instrumentation.targetContext.getSystemService(android.app.ActivityManager::class.java)
            manager.appTasks.single { it.taskInfo?.taskId == taskId }.moveToFront()
        }
        compose.waitForIdle()
    }

    private fun verifySettingsReset(repository: LocalSessionRepository, delete: Boolean) {
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Local Arch Linux"))
        compose.onNodeWithText("Local Arch Linux").performClick()
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("Reset Arch environment").performScrollTo().performClick()
        compose.onNode(hasText("Reset Arch environment") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNode(hasText("Reset Arch environment") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("DELETE ARCH")
        compose.onNode(hasText("Reset Arch environment") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsEnabled()
        if (delete) {
            compose.onNode(hasText("Reset Arch environment") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(30_000) {
                (!repository.environment.state.value.busy && !repository.environment.state.value.installed) ||
                    repository.environment.state.value.error != null || repository.runtime.value.error != null
            }
            assertNull(repository.environment.state.value.error)
            assertNull(repository.runtime.value.error)
            compose.onNodeWithText("Not installed").assertIsDisplayed()
            report("Confirmed reset removed the isolated Arch environment through Settings")
        } else {
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(repository.environment.state.value.installed)
            report("Settings show installation state; destructive reset requires the exact phrase and cancellation preserves files")
        }
    }

    private fun openLocal() {
        compose.onNodeWithTag(NewTerminalSessionTestTag).performClick()
        compose.onNodeWithTag("new-session-picker").assertIsDisplayed()
        compose.onNodeWithText("SSH").assertIsDisplayed()
        compose.onNodeWithText("MOSH").assertIsDisplayed()
        compose.onNodeWithTag("new-local-arch").performClick()
    }

    private fun transcript(repository: LocalSessionRepository, id: Long) =
        repository.controllerFor(id)?.transcriptSnapshot()?.rows?.joinToString("\n") { it.line.text }.orEmpty()

    private fun awaitMarker(repository: LocalSessionRepository, id: Long, marker: String) {
        compose.waitUntil(30_000) { transcript(repository, id).contains(marker) }
    }

    private fun input(text: String) {
        onView(isAssignableFrom(FastTerminalView::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(FastTerminalView::class.java)
            override fun getDescription() = "Enter a real Arch command through the terminal input connection"
            override fun perform(controller: UiController, view: View) {
                val terminal = view as FastTerminalView
                terminal.requestTerminalInputFocus()
                val connection = requireNotNull(terminal.onCreateInputConnection(EditorInfo()))
                assertTrue(connection.commitText(text, 1))
                controller.loopMainThreadUntilIdle()
            }
        })
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    }
}
