package com.yanjiyu.terminalspike.terminal.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.HerdrSidebarLayout
import com.yanjiyu.terminalspike.terminal.TerminalController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HerdrAdaptiveTerminalTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun productionBridgeKeepsTheNativeViewWhenTogglingUnfoldingAndSwitchingToSsh() {
        val width = mutableStateOf(400)
        val controller = TerminalController()
        val active = mutableStateOf(controller)
        lateinit var root: android.view.View
        composeRule.setContent {
            root = LocalView.current.rootView
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.value.dp, 700.dp)) {
                        TerminalViewBridge(active.value, rememberTerminalInputFocusRequester(), {})
                    }
                }
            }
        }
        fun find(view: android.view.View): HerdrTerminalContainer? {
            if (view is HerdrTerminalContainer) return view
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        lateinit var native: FastTerminalView
        composeRule.runOnIdle {
            native = requireNotNull(find(root)).terminal
            controller.publishHerdrSidebarLayout(HerdrSidebarLayout(26, 120))
        }
        composeRule.onNodeWithContentDescription("Show Herdr Spaces and Agents").assertIsDisplayed()
        composeRule.runOnIdle {
            val container = requireNotNull(find(root))
            assertSame(native, container.terminal)
            assertTrue(native.left < 0)
            assertTrue(native.width > container.width)
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").performClick()
        composeRule.runOnIdle {
            assertSame(native, requireNotNull(find(root)).terminal)
            assertEquals(0, native.left)
        }
        composeRule.runOnIdle { width.value = 700 }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle {
            val container = requireNotNull(find(root))
            assertSame(native, container.terminal)
            assertEquals(container.width, native.width)
            width.value = 400
            active.value = TerminalController()
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, requireNotNull(find(root)).terminal.left) }
    }

    @Test fun phoneCanExpandAndHideAndUnfoldingRestoresTheDefaultLayout() {
        val width = mutableStateOf(599)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.value.dp, 700.dp)) {
                        HerdrAdaptiveTerminal(HerdrSidebarLayout(26, 120), "session") {
                            Text("hidden columns: $it")
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("hidden columns: 26").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show Herdr Spaces and Agents").performClick()
        composeRule.onNodeWithText("hidden columns: 0").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide Herdr Spaces and Agents").performClick()
        composeRule.onNodeWithText("hidden columns: 26").assertIsDisplayed()
        composeRule.runOnIdle { width.value = 600 }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.onNodeWithText("hidden columns: 0").assertIsDisplayed()
        composeRule.runOnIdle { width.value = 400 }
        composeRule.onNodeWithText("hidden columns: 26").assertIsDisplayed()
    }

    @Test fun nonHerdrMobileLayoutDisconnectAndSessionSwitchStayIsolated() {
        val layout = mutableStateOf<HerdrSidebarLayout?>(null)
        val session = mutableStateOf("first")
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 600.dp)) {
                    HerdrAdaptiveTerminal(layout.value, session.value) { Text("hidden columns: $it") }
                }
            }
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle { layout.value = HerdrSidebarLayout(0, 60) }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle { layout.value = HerdrSidebarLayout(26, 120) }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").performClick()
        composeRule.onNodeWithText("hidden columns: 0").assertIsDisplayed()
        composeRule.runOnIdle { session.value = "second" }
        composeRule.onNodeWithText("hidden columns: 26").assertIsDisplayed()
        composeRule.runOnIdle { layout.value = null }
        composeRule.onNodeWithText("hidden columns: 0").assertIsDisplayed()
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle { layout.value = HerdrSidebarLayout(26, 120) }
        composeRule.onNodeWithText("hidden columns: 26").assertIsDisplayed()
    }
}
