package com.yanjiyu.terminalspike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.SessionForegroundStartResult
import com.yanjiyu.terminalspike.connection.SessionForegroundStarter
import com.yanjiyu.terminalspike.connection.SessionNotificationVisibility
import com.yanjiyu.terminalspike.connection.SshSessionSnapshot
import com.yanjiyu.terminalspike.connection.TerminalProgramNotificationEvent
import com.yanjiyu.terminalspike.connection.requiresForegroundService
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsSerializer
import com.yanjiyu.terminalspike.terminal.model.sanitizeUntrustedDisplayText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SessionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationFactory: SessionNotificationFactory
    private lateinit var cpuAwakePolicy: SessionCpuAwakePolicy
    private val transitionDetector = SessionTransitionDetector()
    @Volatile
    private var notificationPrivacyEnabled = true
    private var foregroundStarted = false
    private var endingForIdle = false
    private var latestStartId = 0

    private val repository
        get() = (application as TerminalSpikeApplication).container.sshSessionRepository

    override fun onCreate() {
        super.onCreate()
        notificationFactory = SessionNotificationFactory(this)
        cpuAwakePolicy = SessionCpuAwakePolicy(AndroidSessionCpuAwakeLease(this))
        val initialState = repository.sessions.value.toNotificationState()
        val promoted = runCatching {
            notificationFactory.ensureChannel()
            startForegroundImmediately(initialState)
        }.isSuccess
        if (!promoted) {
            repository.failAllForServiceLoss(getString(R.string.session_background_service_failed))
            endingForIdle = true
            stopSelf()
            return
        }
        serviceScope.launch {
            combine(
                repository.sessions,
                (application as TerminalSpikeApplication).container.settings.settings,
            ) { sessions, settings ->
                SessionServiceRuntimeState(
                    sessions = sessions,
                    keepCpuAwake = settings.keepCpuAwake,
                    notificationPrivacyEnabled = settings.notificationPrivacyEnabled,
                    disconnectNotificationsEnabled = settings.disconnectNotificationsEnabled,
                    reconnectNotificationsEnabled = settings.reconnectNotificationsEnabled,
                )
            }.catch {
                emit(
                    SessionServiceRuntimeState(
                        sessions = repository.sessions.value,
                        keepCpuAwake = AppSettingsSerializer.defaultValue.keepCpuAwake,
                        notificationPrivacyEnabled = true,
                        disconnectNotificationsEnabled = false,
                        reconnectNotificationsEnabled = false,
                    ),
                )
            }.collect { runtimeState ->
                notificationPrivacyEnabled = runtimeState.notificationPrivacyEnabled
                val notificationState = runtimeState.sessions.toNotificationState()
                cpuAwakePolicy.update(
                    enabled = runtimeState.keepCpuAwake,
                    hasActiveSession = notificationState.requiresForegroundService,
                )
                updateForegroundState(notificationState, runtimeState.notificationPrivacyEnabled)
                transitionDetector.update(
                    sessions = runtimeState.sessions,
                    disconnectEnabled = runtimeState.disconnectNotificationsEnabled,
                    reconnectEnabled = runtimeState.reconnectNotificationsEnabled,
                ).forEach { event ->
                    notificationFactory.publishEvent(
                        event,
                        privacyEnabled = runtimeState.notificationPrivacyEnabled,
                    )
                }
            }
        }
        serviceScope.launch {
            repository.terminalProgramNotifications.collect { event ->
                notificationFactory.publishTerminalProgramNotification(
                    event = event,
                    privacyEnabled = notificationPrivacyEnabled,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        endingForIdle = false
        if (!repository.requiresForegroundService()) finishForegroundForIdle()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cpuAwakePolicy.close()
        serviceScope.cancel()
        if (!endingForIdle) {
            repository.failAllForServiceLoss(getString(R.string.session_background_service_failed))
        }
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    private fun startForegroundImmediately(
        state: SessionNotificationState,
        privacyEnabled: Boolean = true,
    ) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notificationFactory.build(state, privacyEnabled),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        foregroundStarted = true
    }

    @SuppressLint("MissingPermission")
    private fun updateForegroundState(state: SessionNotificationState, privacyEnabled: Boolean) {
        if (!state.requiresForegroundService) {
            finishForegroundForIdle()
            return
        }
        endingForIdle = false
        if (!foregroundStarted) {
            if (runCatching { startForegroundImmediately(state, privacyEnabled) }.isFailure) {
                repository.failAllForServiceLoss(getString(R.string.session_background_service_failed))
                finishForegroundForIdle()
            }
            return
        }
        // POST_NOTIFICATIONS denial is intentionally not a connection prerequisite. Android still
        // surfaces a running foreground service in system task management on affected releases.
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notificationFactory.build(state, privacyEnabled),
            )
        }
    }

    private fun finishForegroundForIdle() {
        endingForIdle = true
        cpuAwakePolicy.update(enabled = false, hasActiveSession = false)
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        if (latestStartId == 0) stopSelf() else stopSelfResult(latestStartId)
    }

    companion object {
        internal const val ACTION_START =
            "com.yanjiyu.terminalspike.action.START_REMOTE_TERMINAL_SESSIONS"
        internal const val NOTIFICATION_ID = 2_001
    }
}

internal data class SessionServiceRuntimeState(
    val sessions: List<SshSessionSnapshot>,
    val keepCpuAwake: Boolean,
    val notificationPrivacyEnabled: Boolean,
    val disconnectNotificationsEnabled: Boolean,
    val reconnectNotificationsEnabled: Boolean,
)

internal interface SessionCpuAwakeLease {
    fun acquire()

    fun release()
}

/** Idempotent policy seam ensuring disabled/idle/destroy paths release immediately. */
internal class SessionCpuAwakePolicy(
    private val lease: SessionCpuAwakeLease,
) : AutoCloseable {
    private var held = false

    fun update(enabled: Boolean, hasActiveSession: Boolean) {
        val shouldHold = enabled && hasActiveSession
        if (shouldHold == held) return
        if (shouldHold) {
            held = runCatching { lease.acquire() }.isSuccess
        } else {
            held = false
            runCatching { lease.release() }
        }
    }

    override fun close() {
        if (!held) return
        held = false
        runCatching { lease.release() }
    }
}

private class AndroidSessionCpuAwakeLease(context: Context) : SessionCpuAwakeLease {
    private val wakeLock = context.applicationContext
        .getSystemService(PowerManager::class.java)
        .newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.applicationContext.packageName}:remote-terminal-session",
        )
        .apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}

internal enum class SessionTransitionNotification {
    UNEXPECTED_DISCONNECT,
    RECONNECTED_WITH_FRESH_SHELL,
}

/** Stateful but platform-free transition detector; session labels/endpoints never leave the app. */
internal class SessionTransitionDetector {
    private var previous = emptyMap<Long, ConnectionState>()
    private val reconnecting = mutableSetOf<Long>()

    fun update(
        sessions: List<SshSessionSnapshot>,
        disconnectEnabled: Boolean,
        reconnectEnabled: Boolean,
    ): List<SessionTransitionNotification> {
        sessions.filter { it.connectionState is ConnectionState.Reconnecting }
            .forEach { reconnecting += it.id }
        val events = buildList {
            sessions.forEach { session ->
                val old = previous[session.id]
                if (
                    disconnectEnabled && old is ConnectionState.Connected &&
                    (session.connectionState is ConnectionState.Failed ||
                        session.connectionState is ConnectionState.Reconnecting)
                ) {
                    add(SessionTransitionNotification.UNEXPECTED_DISCONNECT)
                }
                if (
                    reconnectEnabled && session.id in reconnecting &&
                    session.connectionState is ConnectionState.Connected
                ) {
                    add(SessionTransitionNotification.RECONNECTED_WITH_FRESH_SHELL)
                }
            }
        }
        val ids = sessions.mapTo(mutableSetOf(), SshSessionSnapshot::id)
        reconnecting.removeAll { id ->
            val state = sessions.firstOrNull { it.id == id }?.connectionState
            id !in ids || state is ConnectionState.Connected ||
                state is ConnectionState.Disconnected || state is ConnectionState.Failed
        }
        previous = sessions.associate { it.id to it.connectionState }
        return events
    }
}

internal class AndroidSessionForegroundStarter(
    context: Context,
) : SessionForegroundStarter {
    private val applicationContext = context.applicationContext

    override fun startFromVisibleUserAction(): SessionForegroundStartResult {
        val intent = Intent(applicationContext, SessionForegroundService::class.java)
            .setAction(SessionForegroundService.ACTION_START)
        val started = try {
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (_: RuntimeException) {
            null
        }
        if (started == null) return SessionForegroundStartResult.Unavailable
        val visibility = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            SessionNotificationVisibility.LIMITED_BY_PERMISSION
        } else {
            SessionNotificationVisibility.VISIBLE
        }
        return SessionForegroundStartResult.Started(visibility)
    }
}

internal data class SessionNotificationState(
    val activeSessionCount: Int,
    val connectedSessionCount: Int,
    val activeFriendlyName: String? = null,
) {
    val requiresForegroundService: Boolean get() = activeSessionCount > 0
}

internal fun List<SshSessionSnapshot>.toNotificationState(): SessionNotificationState {
    val active = filter { session -> session.connectionState.requiresForegroundService() }
    return SessionNotificationState(
        activeSessionCount = active.size,
        connectedSessionCount = count { session -> session.connectionState is ConnectionState.Connected },
        activeFriendlyName = active.singleOrNull()?.workspaceName?.toNotificationFriendlyName(),
    )
}

internal fun String.toNotificationFriendlyName(): String? {
    val normalized = trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_NOTIFICATION_FRIENDLY_NAME_LENGTH)
    if (normalized.isBlank()) return null
    if (normalized.any { character ->
            !(character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_')
        }
    ) {
        return null
    }
    return normalized
}

private const val MAX_NOTIFICATION_FRIENDLY_NAME_LENGTH = 48

internal class SessionNotificationFactory(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.session_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.session_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                PROGRAM_NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.terminal_program_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = applicationContext.getString(
                    R.string.terminal_program_notification_channel_description,
                )
                setShowBadge(true)
            },
        )
    }

    fun build(
        state: SessionNotificationState,
        privacyEnabled: Boolean = true,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            applicationContext,
            OPEN_APP_REQUEST_CODE,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectAll = PendingIntent.getActivity(
            applicationContext,
            DISCONNECT_ALL_REQUEST_CODE,
            Intent(applicationContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CONFIRM_DISCONNECT_ALL)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val safeFriendlyName = state.activeFriendlyName?.toNotificationFriendlyName()
        val content = when {
            state.activeSessionCount == 0 ->
                applicationContext.getString(R.string.session_notification_starting)
            !privacyEnabled && state.activeSessionCount == 1 && safeFriendlyName != null ->
                applicationContext.getString(
                    R.string.session_notification_active_named,
                    safeFriendlyName,
                )
            else -> applicationContext.resources.getQuantityString(
                R.plurals.session_notification_active_count,
                state.activeSessionCount,
                state.activeSessionCount,
            )
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(applicationContext.getString(R.string.session_notification_title))
            .setContentText(content)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(
                if (privacyEnabled) {
                    NotificationCompat.VISIBILITY_SECRET
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                },
            )
            .setLocalOnly(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                applicationContext.getString(R.string.session_notification_disconnect_all),
                disconnectAll,
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    fun publishEvent(event: SessionTransitionNotification, privacyEnabled: Boolean = true) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(
                applicationContext.getString(
                    when (event) {
                        SessionTransitionNotification.UNEXPECTED_DISCONNECT ->
                            R.string.session_notification_disconnected_title
                        SessionTransitionNotification.RECONNECTED_WITH_FRESH_SHELL ->
                            R.string.session_notification_reconnected_title
                    },
                ),
            )
            .setContentText(
                applicationContext.getString(
                    when (event) {
                        SessionTransitionNotification.UNEXPECTED_DISCONNECT ->
                            R.string.session_notification_disconnected_text
                        SessionTransitionNotification.RECONNECTED_WITH_FRESH_SHELL ->
                            R.string.session_notification_reconnected_text
                    },
                ),
            )
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(
                if (privacyEnabled) {
                    NotificationCompat.VISIBILITY_SECRET
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                },
            )
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            notificationManager.notify(
                when (event) {
                    SessionTransitionNotification.UNEXPECTED_DISCONNECT -> DISCONNECT_EVENT_ID
                    SessionTransitionNotification.RECONNECTED_WITH_FRESH_SHELL -> RECONNECT_EVENT_ID
                },
                notification,
            )
        }
    }

    internal fun buildTerminalProgramNotification(
        event: TerminalProgramNotificationEvent,
        privacyEnabled: Boolean,
    ): Notification {
        val safeSessionTitle = event.sessionTitle.toNotificationFriendlyName()
        val safeMessage = sanitizeUntrustedDisplayText(
            event.message,
            MAX_PROGRAM_NOTIFICATION_TEXT_LENGTH,
        )
            .ifEmpty { applicationContext.getString(R.string.terminal_program_notification_text) }
        val openSession = PendingIntent.getActivity(
            applicationContext,
            PROGRAM_NOTIFICATION_REQUEST_CODE_BASE + (event.sessionId.hashCode() and 0x0fff),
            terminalProgramNotificationIntent(applicationContext, event.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (!privacyEnabled && safeSessionTitle != null) {
            applicationContext.getString(
                R.string.terminal_program_notification_named_title,
                safeSessionTitle,
            )
        } else {
            applicationContext.getString(R.string.terminal_program_notification_title)
        }
        val text = if (privacyEnabled) {
            applicationContext.getString(R.string.terminal_program_notification_text)
        } else {
            safeMessage
        }
        return NotificationCompat.Builder(applicationContext, PROGRAM_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSession)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(
                if (privacyEnabled) {
                    NotificationCompat.VISIBILITY_SECRET
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                },
            )
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun publishTerminalProgramNotification(
        event: TerminalProgramNotificationEvent,
        privacyEnabled: Boolean,
    ) {
        runCatching {
            notificationManager.notify(
                "terminal-program-${event.sessionId}",
                PROGRAM_NOTIFICATION_ID,
                buildTerminalProgramNotification(event, privacyEnabled),
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        OPEN_APP_REQUEST_CODE,
        Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        internal const val CHANNEL_ID = "active_remote_terminal_sessions"
        internal const val PROGRAM_NOTIFICATION_CHANNEL_ID = "terminal_program_notifications"
        private const val OPEN_APP_REQUEST_CODE = 2_001
        private const val DISCONNECT_ALL_REQUEST_CODE = 2_002
        private const val DISCONNECT_EVENT_ID = 2_003
        private const val RECONNECT_EVENT_ID = 2_004
        private const val PROGRAM_NOTIFICATION_ID = 2_005
        private const val PROGRAM_NOTIFICATION_REQUEST_CODE_BASE = 3_000
        private const val MAX_PROGRAM_NOTIFICATION_TEXT_LENGTH = 512
    }
}

internal fun terminalProgramNotificationIntent(context: Context, sessionId: Long): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_TERMINAL_SESSION)
        .putExtra(MainActivity.EXTRA_TERMINAL_SESSION_ID, sessionId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
