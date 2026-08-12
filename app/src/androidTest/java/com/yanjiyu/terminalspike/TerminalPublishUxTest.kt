package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
        val notice = mutableStateOf<UiText?>(uiText(R.string.notice_terminal_keys_saved))
        val hostState = SnackbarHostState()
        val consumptionCount = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                NoticeSnackbarHost(
                    notice = notice.value,
                    hostState = hostState,
                    onNoticePresented = { presentedNotice ->
                        consumptionCount.incrementAndGet()
                        if (notice.value == presentedNotice) notice.value = null
                    },
                )
            }
        }

        composeRule.onNodeWithText("Terminal keys saved.").assertIsDisplayed()
        composeRule.runOnIdle {
            checkNotNull(hostState.currentSnackbarData).dismiss()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { consumptionCount.get() == 1 }
        composeRule.runOnIdle {
            assertNull(notice.value)
            assertEquals(1, consumptionCount.get())
        }
        composeRule.waitForIdle()
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
