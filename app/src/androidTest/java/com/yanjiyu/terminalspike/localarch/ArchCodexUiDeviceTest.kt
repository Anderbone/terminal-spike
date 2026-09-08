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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in actual Codex onboarding in the native Local Arch terminal; never signs in. */
class ArchCodexUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun codexStartsInTheInstalledLocalTerminal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchCodexUi") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        assertTrue(application.packageName.endsWith(".archverify"))
        val repository = application.container.localSessionRepository
        compose.waitUntil(20_000) { repository.environment.state.value.checked }
        assertTrue(repository.environment.state.value.installed)
        compose.onNodeWithContentDescription("Open terminal").performClick()
        openLocal()
        compose.waitUntil(20_000) { repository.runtime.value.connectedCount == 1 }
        val id = ViewModelProvider(compose.activity)[TerminalSpikeViewModel::class.java].uiState.value.activeSessionId
        try {
            input("codex\n")
            compose.waitUntil(30_000) { transcript(repository, id).contains("Sign in with ChatGPT") }
            compose.onNodeWithTag("terminal_container").assertIsDisplayed()
            java.io.FileOutputStream(java.io.File(application.filesDir, "codex-onboarding.png")).use { output ->
                requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            report("ARCH_CODEX_NATIVE_TERMINAL_ONBOARDING_OK: actual Codex reached Sign in with ChatGPT through the Local Arch terminal")
        } finally {
            repository.close(id)
            compose.waitUntil(20_000) { repository.runtime.value.sessions.isEmpty() }
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
