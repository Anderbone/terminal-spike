package com.yanjiyu.terminalspike.localarch

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.ui.settings.SettingsCategoryListContentDescription
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Requests real short-lived codes, but never approves login or reads stored credentials. */
class ArchLoginDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsRequestRealCodesAndCancelAcrossRecreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchLogin") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TerminalSpikeApplication
        assertTrue("Use the isolated verification app", application.packageName.endsWith(".archverify"))
        val repository = application.container.localSessionRepository
        compose.waitUntil(20_000) { repository.environment.state.value.checked }
        assertTrue("Install Arch starter tools before running this opt-in gate", repository.environment.state.value.starterToolsInstalled)
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("Local Arch Linux"))
        compose.onNodeWithText("Local Arch Linux").performClick()
        for (provider in ArchLoginProvider.entries) {
            try {
                compose.onNodeWithTag("local-arch-login-${provider.name.lowercase()}").performScrollTo().performClick()
                compose.waitUntil(90_000) { repository.login.value.code != null || repository.login.value.failed }
                assertFalse("${provider.label} code request failed", repository.login.value.failed)
                assertNotNull("${provider.label} must issue a real code", repository.login.value.code)
                assertTrue(repository.runtime.value.requiresForegroundService)
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag("local-arch-login-code").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Copy code and open browser").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("Cancel").performScrollTo().performClick()
                compose.waitUntil(10_000) { !repository.login.value.active }
                assertNull(repository.login.value.code)
                assertFalse(repository.login.value.succeeded)
                compose.onNodeWithTag("local-arch-login-${provider.name.lowercase()}").assertIsEnabled()
            } finally {
                repository.cancelLogin()
                compose.waitUntil(10_000) { !repository.login.value.active }
            }
        }
    }
}
