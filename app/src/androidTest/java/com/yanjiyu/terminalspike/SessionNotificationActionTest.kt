package com.yanjiyu.terminalspike

import android.app.Notification
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.connection.TerminalProgramNotificationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionNotificationActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun notificationDisconnectActionTargetsActivityConfirmationOnly() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val notification = SessionNotificationFactory(context).build(
            SessionNotificationState(activeSessionCount = 2, connectedSessionCount = 2),
        )

        assertEquals(1, notification.actions.size)
        val action = notification.actions.single().actionIntent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(action.isActivity)
        assertEquals(
            context.getString(R.string.session_notification_disconnect_all),
            notification.actions.single().title.toString(),
        )
        assertEquals(
            context.getString(R.string.session_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test
    fun terminalProgramNotificationTargetsItsOriginatingTerminalTab() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val event = TerminalProgramNotificationEvent(
            sessionId = 42L,
            sessionTitle = "Terminal Spike dev",
            message = "Codex completed the requested change.",
        )
        val notification = SessionNotificationFactory(context)
            .buildTerminalProgramNotification(event, privacyEnabled = false)
        val intent = terminalProgramNotificationIntent(context, event.sessionId)

        assertEquals(MainActivity.ACTION_OPEN_TERMINAL_SESSION, intent.action)
        assertEquals(
            event.sessionId,
            intent.getLongExtra(MainActivity.EXTRA_TERMINAL_SESSION_ID, -1L),
        )
        assertEquals(
            "Terminal Spike dev task complete",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            event.message,
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(notification.contentIntent.isActivity)
        }
    }

    @Test
    fun currentCountConfirmationChangesNothingUntilExplicitChoice() {
        var confirms = 0
        var dismisses = 0
        composeRule.setContent {
            MaterialTheme {
                DisconnectAllSessionsConfirmation(
                    activeSessionCount = 2,
                    onConfirm = { confirms += 1 },
                    onDismiss = { dismisses += 1 },
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(0, dismisses)
        }
        composeRule.onNodeWithText("Keep connected").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, dismisses)
        }
    }
}
