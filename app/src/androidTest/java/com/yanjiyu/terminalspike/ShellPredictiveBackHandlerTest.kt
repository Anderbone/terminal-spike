package com.yanjiyu.terminalspike

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yanjiyu.terminalspike.ui.AppRoute
import com.yanjiyu.terminalspike.ui.PredictiveBackDestinationPreview
import com.yanjiyu.terminalspike.ui.ShellPredictiveBackHandler
import com.yanjiyu.terminalspike.ui.TerminalOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShellPredictiveBackHandlerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    @Test
    fun liveHandlerCancelsWithoutNavigationThenCommitsToFrozenOwner() {
        var route by mutableStateOf(AppRoute.TERMINAL_DETAIL)
        var preview by mutableStateOf<com.yanjiyu.terminalspike.ui.ShellBackPreview?>(null)

        composeRule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                .onBackPressedDispatcher
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ShellPredictiveBackHandler(
                        currentRoute = route,
                        terminalOwner = TerminalOwner.CONNECTIONS,
                        onPreviewChanged = { preview = it },
                        onNavigate = { route = it },
                    )
                    Text("Route: ${route.name}", Modifier.align(Alignment.Center))
                    preview?.let { PredictiveBackDestinationPreview(it, Modifier.fillMaxSize()) }
                }
            }
        }

        dispatchProgress(0.6f)
        composeRule.onNodeWithText("Back to Connections").assertIsDisplayed()

        composeRule.runOnIdle {
            backDispatcher.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Back to Connections").assertDoesNotExist()
        composeRule.onNodeWithText("Route: TERMINAL_DETAIL").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(AppRoute.TERMINAL_DETAIL, route) }

        dispatchProgress(0.8f)
        composeRule.runOnIdle {
            backDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Back to Connections").assertDoesNotExist()
        composeRule.onNodeWithText("Route: CONNECTIONS").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(AppRoute.CONNECTIONS, route) }
    }

    private fun dispatchProgress(progress: Float) {
        composeRule.runOnIdle {
            backDispatcher.dispatchOnBackStarted(
                BackEventCompat(
                    touchX = 0f,
                    touchY = 400f,
                    progress = 0f,
                    swipeEdge = BackEventCompat.EDGE_LEFT,
                ),
            )
            backDispatcher.dispatchOnBackProgressed(
                BackEventCompat(
                    touchX = 80f,
                    touchY = 400f,
                    progress = progress,
                    swipeEdge = BackEventCompat.EDGE_LEFT,
                ),
            )
        }
        composeRule.waitForIdle()
    }
}
