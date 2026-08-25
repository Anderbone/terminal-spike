package com.yanjiyu.terminalspike

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yanjiyu.terminalspike.ui.AppRoute
import com.yanjiyu.terminalspike.ui.ToolSection
import org.junit.Rule
import org.junit.Test

class MainContentStateHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalDestinationSurvivesWhileAppContentIsGated() {
        var contentVisible by mutableStateOf(true)

        composeRule.setContent {
            MainContentStateHost(visible = contentVisible) {
                var destination by rememberSaveable { mutableStateOf(AppRoute.WORKSPACE) }

                Text("Destination: ${destination.name}")
                Button(onClick = { destination = AppRoute.TERMINAL_DETAIL }) {
                    Text("Open terminal")
                }
            }
        }

        composeRule.onNodeWithText("Open terminal").performClick()
        composeRule.onNodeWithText("Destination: TERMINAL_DETAIL").assertIsDisplayed()

        composeRule.runOnIdle { contentVisible = false }
        composeRule.onNodeWithText("Destination: TERMINAL_DETAIL").assertDoesNotExist()

        composeRule.runOnIdle { contentVisible = true }
        composeRule.onNodeWithText("Destination: TERMINAL_DETAIL").assertIsDisplayed()
    }

    @Test
    fun settingsDestinationAndSectionSurviveWhileAppContentIsGated() {
        var contentVisible by mutableStateOf(true)

        composeRule.setContent {
            MainContentStateHost(visible = contentVisible) {
                var destination by rememberSaveable { mutableStateOf(AppRoute.WORKSPACE) }
                var settingsSection by rememberSaveable { mutableStateOf(ToolSection.PROFILES) }

                Text("Destination: ${destination.name}")
                Text("Settings: ${settingsSection.name}")
                Button(
                    onClick = {
                        destination = AppRoute.SETTINGS
                        settingsSection = ToolSection.SECURITY
                    },
                ) {
                    Text("Open security settings")
                }
            }
        }

        composeRule.onNodeWithText("Open security settings").performClick()
        composeRule.onNodeWithText("Destination: SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("Settings: SECURITY").assertIsDisplayed()

        composeRule.runOnIdle { contentVisible = false }
        composeRule.onNodeWithText("Destination: SETTINGS").assertDoesNotExist()

        composeRule.runOnIdle { contentVisible = true }
        composeRule.onNodeWithText("Destination: SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("Settings: SECURITY").assertIsDisplayed()
    }
}
