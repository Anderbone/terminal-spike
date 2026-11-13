package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import com.yanjiyu.terminalspike.ui.theme.upperNoticePlacement
import org.junit.Assert.assertTrue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yanjiyu.terminalspike.ui.JumpToLatestAction
import com.yanjiyu.terminalspike.ui.NoticeSnackbarHost
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.uiText
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TerminalPublishUxTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rootNoticeIsPresentedAndConsumedExactlyOnce() {
        val notice = mutableStateOf<UiText?>(
            uiText(R.string.notice_snippet_sent, UiText.Dynamic("New Co")),
        )
        lateinit var scope: CoroutineScope
        val hostState = SnackbarHostState()
        val consumptionCount = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                scope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize()) {
                    NoticeSnackbarHost(
                        notice = notice.value,
                        hostState = hostState,
                        modifier = Modifier.align(Alignment.TopCenter).upperNoticePlacement(),
                        onNoticePresented = { presentedNotice ->
                            consumptionCount.incrementAndGet()
                            if (notice.value == presentedNotice) notice.value = null
                        },
                    )
                }
            }
        }

        val message = composeRule.onNodeWithText("Sent New Co.").assertIsDisplayed()
        val bounds = message.fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Notice should occupy the upper half", bounds.bottom < root.center.y)
        // Verify the real Snackbar timeout, rather than manually dismissing the notice.
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.waitUntil(timeoutMillis = 5_000) { consumptionCount.get() == 1 }
        composeRule.runOnIdle {
            assertNull(notice.value)
            assertEquals(1, consumptionCount.get())
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sent New Co.").assertDoesNotExist()
        assertEquals(1, consumptionCount.get())

        // Direct host callers (exports/pickers) must use the same upper notice as ViewModel events.
        composeRule.runOnIdle {
            scope.launch { hostState.showSnackbar("File attached to terminal input") }
        }
        val directBounds = composeRule.onNodeWithText("File attached to terminal input")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Direct notices should also occupy the upper half", directBounds.bottom < root.center.y)
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.waitUntil(timeoutMillis = 5_000) { hostState.currentSnackbarData == null }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("File attached to terminal input").assertDoesNotExist()
        assertEquals(1, consumptionCount.get())
    }

    @Test
    fun jumpToLatestIsOnlyActionableWhenViewportIsNotFollowing() {
        val visible = mutableStateOf(false)
        val jumpCount = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                JumpToLatestAction(
                    visible = visible.value,
                    onJumpToLatest = {
                        jumpCount.incrementAndGet()
                        visible.value = false
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Jump to latest terminal output")
            .assertDoesNotExist()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNodeWithContentDescription("Jump to latest terminal output")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, jumpCount.get()) }
        composeRule.onNodeWithContentDescription("Jump to latest terminal output")
            .assertDoesNotExist()
    }
}
