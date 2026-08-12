package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.ui.AppRoute
import com.yanjiyu.terminalspike.ui.PredictiveBackDestinationPreview
import com.yanjiyu.terminalspike.ui.PredictiveBackDestinationPreviewTestTag
import com.yanjiyu.terminalspike.ui.ShellBackSwipeEdge
import com.yanjiyu.terminalspike.ui.TerminalOwner
import com.yanjiyu.terminalspike.ui.progressShellBackGesture
import com.yanjiyu.terminalspike.ui.startShellBackGesture
import org.junit.Rule
import org.junit.Test

class PredictiveBackPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalPreviewIdentifiesTheFrozenConnectionsOwner() {
        val gesture = checkNotNull(
            startShellBackGesture(AppRoute.TERMINAL_DETAIL, TerminalOwner.CONNECTIONS),
        )
        val preview = checkNotNull(
            progressShellBackGesture(gesture, 0.75f, ShellBackSwipeEdge.LEFT).preview,
        )

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 400.dp, height = 700.dp)) {
                    PredictiveBackDestinationPreview(preview)
                }
            }
        }

        composeRule.onNodeWithTag(PredictiveBackDestinationPreviewTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Back to Connections").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Predictive back preview to Connections",
        ).assertIsDisplayed()
    }

    @Test
    fun rightEdgeSettingsPreviewUsesTheResolvedOwnerLabel() {
        val gesture = checkNotNull(
            startShellBackGesture(AppRoute.TERMINAL_DETAIL, TerminalOwner.SETTINGS),
        )
        val preview = checkNotNull(
            progressShellBackGesture(gesture, 1f, ShellBackSwipeEdge.RIGHT).preview,
        )

        composeRule.setContent {
            MaterialTheme {
                PredictiveBackDestinationPreview(preview)
            }
        }

        composeRule.onNodeWithText("Back to Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Predictive back preview to Settings",
        ).assertIsDisplayed()
    }
}
