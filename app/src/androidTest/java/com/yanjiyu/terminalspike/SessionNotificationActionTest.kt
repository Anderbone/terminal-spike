package com.yanjiyu.terminalspike

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun terminalBellNotificationUsesGenericTaskText() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val factory = SessionNotificationFactory(context)
        val event = TerminalProgramNotificationEvent(
            sessionId = -9042L,
            sessionTitle = "Terminal Spike dev",
            message = "",
        )
        val notification = factory.buildTerminalProgramNotification(
            event = event,
            privacyEnabled = false,
        )

        assertEquals(
            context.getString(R.string.terminal_program_notification_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        factory.ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = "terminal-program-${event.sessionId}"
        try {
            factory.publishTerminalProgramNotification(event, privacyEnabled = true)
            composeRule.waitUntil(5_000) { manager.activeNotifications.any { it.tag == tag } }
            val posted = manager.activeNotifications.single { it.tag == tag }.notification
            assertEquals(SessionNotificationFactory.PROGRAM_NOTIFICATION_CHANNEL_ID, posted.channelId)
            assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
            assertEquals(context.getString(R.string.terminal_program_notification_text),
                posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        } finally {
            manager.activeNotifications.filter { it.tag == tag }.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    @Test
    fun terminalProgramNotificationStripsControlsAndBidiFormatting() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val notification = SessionNotificationFactory(context).buildTerminalProgramNotification(
            event = TerminalProgramNotificationEvent(
                sessionId = 42L,
                sessionTitle = "Terminal Spike dev",
                message = "Build\t\u202Egpj.exe\u202C\u2066\u2069\n done\u0007",
            ),
            privacyEnabled = false,
        )

        assertEquals(
            "Build gpj.exe done",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun terminalProgramNotificationFallsBackWhenMessageIsOnlyUnsafeFormatting() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val notification = SessionNotificationFactory(context).buildTerminalProgramNotification(
            event = TerminalProgramNotificationEvent(
                sessionId = 42L,
                sessionTitle = "Terminal Spike dev",
                message = "\u0000\u001B\u202E\u2069",
            ),
            privacyEnabled = false,
        )

        assertEquals(
            context.getString(R.string.terminal_program_notification_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
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
