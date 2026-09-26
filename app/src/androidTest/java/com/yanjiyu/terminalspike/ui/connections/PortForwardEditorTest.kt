package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.yanjiyu.terminalspike.core.model.PortForwardDirection
import com.yanjiyu.terminalspike.core.model.PortForwardRule
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PortForwardEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addsEditsAndRemovesAValidatedForward() {
        val rules = mutableStateOf(emptyList<PortForwardRule>())
        compose.setContent {
            TerminalSpikeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PortForwardEditor(rules.value, { rules.value = it }, enabled = true)
                }
            }
        }
        compose.onNodeWithText("Add port forward").performClick()
        compose.onNodeWithText("Listen port").performScrollTo().performTextInput("8080")
        compose.onNodeWithText("Destination port").performScrollTo().performTextInput("80")
        compose.onNodeWithText("Save rule").performClick()
        compose.runOnIdle { assertEquals(8080, rules.value.single().listenPort) }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Remote").performScrollTo().performClick()
        compose.onNodeWithText("Listen port").performScrollTo().performTextReplacement("9090")
        compose.onNodeWithText("Save rule").performClick()
        compose.runOnIdle {
            assertEquals(9090, rules.value.single().listenPort)
            assertEquals(PortForwardDirection.REMOTE, rules.value.single().direction)
        }
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertTrue(rules.value.isEmpty()) }
        compose.onNodeWithText("Example: localhost:5173").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(rules.value.isEmpty()) }
        repeat(2) {
            compose.onNodeWithText("Example: localhost:5173").performScrollTo().performClick()
            compose.onNodeWithText("Save rule").performClick()
            compose.runOnIdle {
                assertEquals(listOf(PortForwardRule(listenPort = 5173, destinationPort = 5173)), rules.value)
            }
        }
    }
}
