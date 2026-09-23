package com.yanjiyu.terminalspike.terminal.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.HerdrSidebarLayout
import com.yanjiyu.terminalspike.terminal.TerminalController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HerdrAdaptiveTerminalTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun productionBridgeKeepsActualWindowWidthAcrossMetadataResizesAndSessions() {
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
        var initialColumns = 0
        var initialRows = 0
        lateinit var native: FastTerminalView
        composeRule.runOnIdle {
            native = requireNotNull(find(root)).terminal
            initialColumns = controller.terminalColumns
            initialRows = controller.terminalRows
            assertTrue(initialColumns > 0)
            native.requestFocus()
            controller.publishHerdrSidebarLayout(HerdrSidebarLayout(26, 120))
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle {
            val container = requireNotNull(find(root))
            assertSame(native, container.terminal)
            assertEquals(0, native.left)
            assertEquals(container.width, native.width)
            assertEquals(initialColumns, controller.terminalColumns)
            assertEquals(initialRows, controller.terminalRows)
            assertTrue(native.hasFocus())
            controller.publishHerdrSidebarLayout(HerdrSidebarLayout(0, 60, 2, 30))
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle {
            val container = requireNotNull(find(root))
            assertSame(native, container.terminal)
            assertEquals(container.width, native.width)
        }
        composeRule.runOnIdle {
            assertEquals(initialColumns, controller.terminalColumns)
            assertEquals(initialRows, controller.terminalRows)
            assertTrue(native.hasFocus())
            controller.publishHerdrSidebarLayout(null)
        }
        composeRule.runOnIdle {
            assertSame(native, requireNotNull(find(root)).terminal)
            assertEquals(initialColumns, controller.terminalColumns)
            controller.fontSizeSp = 20f
        }
        composeRule.waitUntil(5_000) { controller.terminalColumns < initialColumns }
        composeRule.runOnIdle {
            assertEquals(requireNotNull(find(root)).width, native.width)
            width.value = 700
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle {
            val container = requireNotNull(find(root))
            assertSame(native, container.terminal)
            assertEquals(container.width, native.width)
            width.value = 400
            active.value = TerminalController()
        }
        composeRule.onNodeWithTag("herdr_sidebar_toggle").assertDoesNotExist()
        composeRule.runOnIdle {
            val container = requireNotNull(find(root))
            assertSame(native, container.terminal)
            assertEquals(0, native.left)
            assertEquals(container.width, native.width)
        }
    }

}
