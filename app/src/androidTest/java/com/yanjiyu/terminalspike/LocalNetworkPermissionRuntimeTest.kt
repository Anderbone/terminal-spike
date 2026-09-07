package com.yanjiyu.terminalspike

import android.Manifest
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yanjiyu.terminalspike.ui.TerminalSessionStripTestTag
import com.yanjiyu.terminalspike.ui.connections.HostConnectSecretTestTag
import com.yanjiyu.terminalspike.ui.connections.HostEditorHostnameTestTag
import com.yanjiyu.terminalspike.ui.connections.HostEditorNameTestTag
import com.yanjiyu.terminalspike.ui.connections.HostEditorNearbySshButtonTestTag
import com.yanjiyu.terminalspike.ui.connections.HostEditorSaveTestTag
import com.yanjiyu.terminalspike.ui.connections.HostEditorSecretTestTag
import com.yanjiyu.terminalspike.ui.connections.HostEditorUsernameTestTag
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

class LocalNetworkPermissionRuntimeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun requireAndroid17AndMissingPermission() {
        assumeTrue("ACCESS_LOCAL_NETWORK is enforced from API 37.", Build.VERSION.SDK_INT >= 37)
        assertTrue(
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    @Test
    fun deniedLocalConnectionReturnsToTheAppWithoutStartingASession() {
        submitSavedHostConnection("Denied LAN", "192.168.50.20")
        clickPermissionButton(allow = false)

        assertTrue(
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(
                "Local network permission is required to connect to LAN hosts.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            "Local network permission is required to connect to LAN hosts.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalSessionStripTestTag).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Connections screen").assertIsDisplayed()
    }

    @Test
    fun grantedLocalConnectionResumesTheExactPendingAction() {
        submitSavedHostConnection("Granted LAN", "192.168.50.21")
        clickPermissionButton(allow = true)

        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(TerminalSessionStripTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TerminalSessionStripTestTag).assertIsDisplayed()
    }

    @Test
    fun nearbySystemPickerDoesNotRequestBroadLocalAccess() {
        composeRule.onNodeWithContentDescription("Add host").performClick()
        composeRule.onNodeWithText("Show advanced settings").performScrollTo().performClick()
        composeRule.onNodeWithTag(HostEditorNearbySshButtonTestTag)
            .performScrollTo()
            .performClick()
        device.waitForIdle()

        assertTrue(
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        assertTrue(
            "Nearby SSH unexpectedly displayed the broad runtime-permission controls.",
            !device.hasObject(By.res(Pattern.compile(".*:permission_(?:allow|deny)_button$"))),
        )
        device.pressBack()
    }

    @Test
    fun activityRecreationWhilePromptIsOpenStillResumesOnce() {
        var originalActivity: MainActivity? = null
        composeRule.activityRule.scenario.onActivity { originalActivity = it }
        try {
            submitSavedHostConnection("Recreated LAN", "192.168.50.22")
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                activeWindowPackage() in PERMISSION_CONTROLLER_PACKAGES
            }

            device.setOrientationLeft()
            device.waitForIdle()
            clickPermissionButton(allow = true)

            composeRule.activityRule.scenario.onActivity {
                assertNotSame("MainActivity was not recreated by rotation.", originalActivity, it)
            }
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(TerminalSessionStripTestTag)
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag(TerminalSessionStripTestTag).assertIsDisplayed()
        } finally {
            device.setOrientationNatural()
        }
    }

    @Test
    fun publicEndpointStartsWithoutRequestingBroadLocalAccess() {
        submitSavedHostConnection("Public host", "example.invalid")

        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(TerminalSessionStripTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TerminalSessionStripTestTag).assertIsDisplayed()
        assertTrue(
            "A public endpoint unexpectedly opened the local-network permission UI.",
            activeWindowPackage() !in PERMISSION_CONTROLLER_PACKAGES,
        )
    }

    private fun submitSavedHostConnection(name: String, hostname: String) {
        composeRule.onNodeWithContentDescription("Add host").performClick()
        composeRule.onNodeWithTag(HostEditorNameTestTag).performTextReplacement(name)
        composeRule.onNodeWithTag(HostEditorHostnameTestTag).performTextReplacement(hostname)
        composeRule.onNodeWithTag(HostEditorUsernameTestTag).performTextReplacement("terminal")
        onView(withTagValue(equalTo(HostEditorSecretTestTag))).perform(replaceText("session secret"))
        closeSoftKeyboard()
        composeRule.onNodeWithTag(HostEditorSaveTestTag).performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Connect to $name")
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissAutofillSaveOverlayIfPresent()
        composeRule.onNodeWithContentDescription("Connect to $name").performClick()
        if (
            composeRule.onAllNodesWithTag(HostConnectSecretTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            dismissAutofillSaveOverlayIfPresent()
            onView(withTagValue(equalTo(HostConnectSecretTestTag))).perform(replaceText("session secret"))
            closeSoftKeyboard()
            composeRule.onNodeWithText("Connect").performClick()
        }
    }

    private fun dismissAutofillSaveOverlayIfPresent() {
        val decline = device.wait(
            Until.findObject(By.res(AUTOFILL_DECLINE_VIEW_ID)),
            AUTOFILL_WAIT_MILLIS,
        ) ?: return
        decline.click()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            activeWindowPackage() == instrumentation.targetContext.packageName
        }
    }

    private fun clickPermissionButton(allow: Boolean) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            activeWindowPackage() in PERMISSION_CONTROLLER_PACKAGES
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val wantedIdSuffix = if (allow) "permission_allow_button" else "permission_deny_button"
        val wantedText = if (allow) {
            Pattern.compile("(?i)^allow(?: access)?$")
        } else {
            Pattern.compile("(?i)^(?:don[’']t allow|deny)$")
        }
        val button = device.findObject(By.res(Pattern.compile(".*:$wantedIdSuffix$")))
            ?: device.findObject(By.text(wantedText))
        assertNotNull(
            "The Android local-network permission action was not visible. " + root.describeTree(),
            button,
        )
        button!!.click()
        device.waitForIdle()
    }

    private fun activeWindowPackage(): String? =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun AccessibilityNodeInfo?.describeTree(): String {
        val descriptions = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || descriptions.size >= 80) return
            descriptions += buildString {
                repeat(depth) { append(' ') }
                append(node.className)
                append(" id=").append(node.viewIdResourceName)
                append(" text=").append(node.text)
                append(" description=").append(node.contentDescription)
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(this, 0)
        return descriptions.joinToString(" | ")
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 10_000L
        val PERMISSION_CONTROLLER_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
        const val AUTOFILL_DECLINE_VIEW_ID = "android:id/autofill_save_no"
        const val AUTOFILL_WAIT_MILLIS = 1_000L
    }
}
