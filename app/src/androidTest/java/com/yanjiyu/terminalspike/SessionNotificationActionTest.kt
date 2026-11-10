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
            context.getString(R.string.terminal_program_notification_named_title, event.sessionTitle),
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
    fun threeTaskRepliesKeepDistinctNotificationsAndExactPaneTargets() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val factory = SessionNotificationFactory(context)
        factory.ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val events = (1..3).map { index ->
            val task = com.yanjiyu.terminalspike.connection.HerdrAgentState(
                "w6:p$index", "terminal$index", "chat$index", "idle", 2,
                taskTitle = "Task $index", replyId = "turn$index", reply = "Result $index",
            )
            TerminalProgramNotificationEvent(-9050, "Project", task.reply.orEmpty(), task)
        }
        val tags = events.map { "terminal-program-${it.notificationKey}" }.toSet()
        try {
            events.forEach { factory.publishTerminalProgramNotification(it, privacyEnabled = false) }
            composeRule.waitUntil(5_000) { manager.activeNotifications.count { it.tag in tags } == 3 }
            val posted = manager.activeNotifications.filter { it.tag in tags }.map { it.notification }
            assertEquals(3, posted.map { it.contentIntent }.distinct().size)
            assertEquals(setOf("Result 1", "Result 2", "Result 3"),
                posted.map { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }.toSet())
            events.forEach { event ->
                val intent = terminalProgramNotificationIntent(context, event.sessionId, event.task)
                val task = requireNotNull(event.task)
                assertEquals(task.paneId, intent.getStringExtra(MainActivity.EXTRA_TASK_PANE))
                assertEquals(task.terminalId, intent.getStringExtra(MainActivity.EXTRA_TASK_TERMINAL))
                assertEquals(task.agentSession, intent.getStringExtra(MainActivity.EXTRA_TASK_SESSION))
            }
            // A repeated delivery replaces only that event, not its sibling tasks.
            factory.publishTerminalProgramNotification(events.first(), privacyEnabled = false)
            assertEquals(3, manager.activeNotifications.count { it.tag in tags })
        } finally {
            manager.activeNotifications.filter { it.tag in tags }.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    @Test
    fun taskAttentionAndReplyRespectPrivacyAndDoNotClaimSuccess() {
        val context = ApplicationProvider.getApplicationContext<TerminalSpikeApplication>()
        val task = com.yanjiyu.terminalspike.connection.HerdrAgentState(
            "w6:p1", "terminal1", "chat1", "blocked", 2, taskTitle = "Private task",
        )
        val event = TerminalProgramNotificationEvent(42, "Private project", "", task)
        val factory = SessionNotificationFactory(context)
        val attention = factory.buildTerminalProgramNotification(event, privacyEnabled = false)
        assertEquals(context.getString(R.string.terminal_task_waiting_title, task.taskTitle),
            attention.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.terminal_task_waiting_text),
            attention.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val hidden = factory.buildTerminalProgramNotification(event.copy(message = "Private reply"), privacyEnabled = true)
        assertEquals(Notification.VISIBILITY_SECRET, hidden.visibility)
        assertEquals(context.getString(R.string.terminal_program_notification_text),
            hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(!hidden.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Private"))
        val reply = factory.buildTerminalProgramNotification(event.copy(task = task.copy(state = "idle"),
            message = "Build failed; please choose a signing key."), privacyEnabled = false)
        assertEquals(context.getString(R.string.terminal_task_reply_title, task.taskTitle),
            reply.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Build failed; please choose a signing key.",
            reply.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
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
