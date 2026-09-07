/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import com.yanjiyu.terminalspike.mosh.api.IMoshCallback
import com.yanjiyu.terminalspike.mosh.api.IMoshPlugin
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorker
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorkerCallback
import java.util.LinkedHashMap

/**
 * Private, process-separated control-plane broker for the built-in Mosh transport.
 *
 * Terminal bytes and the one-shot Mosh key are transferred through file descriptors. The broker
 * never reads either stream and never receives an SSH credential.
 */
public class MoshExtensionService : Service() {
    private val stateLock = Any()
    private val callbackBroadcastGate = SerializedCallbackGate()
    private val slotAllocator = WorkerSlotAllocator(WORKER_COMPONENTS.size)
    private val sessions = mutableMapOf<String, BrokerSession>()
    private var foregroundStarted = false
    private val lastEvents = object : LinkedHashMap<String, MoshSessionEvent>(MAX_REPLAY_EVENTS + 1, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MoshSessionEvent>?,
        ): Boolean = size > MAX_REPLAY_EVENTS
    }
    private val callbacks = object : RemoteCallbackList<IMoshCallback>() {
        override fun onCallbackDied(callback: IMoshCallback?, cookie: Any?) {
            // Only the trusted main application can register. Its death must tear down every
            // ephemeral session; a Mosh key is intentionally never retained for resurrection.
            stopAllSessions(MoshStopReason.APP_SHUTDOWN)
        }
    }

    private val binder = object : IMoshPlugin.Stub() {
        override fun getApiVersion(): Int {
            enforceTrustedCaller()
            return MoshApi.PROTOCOL_VERSION
        }

        override fun getCapabilities(): MoshCapabilities {
            enforceTrustedCaller()
            return CAPABILITIES
        }

        override fun startSession(request: MoshSessionRequest): MoshSessionHandle {
            return try {
                enforceTrustedCaller()
                this@MoshExtensionService.startSession(request)
            } catch (error: RuntimeException) {
                // A rejected Binder request still transferred this process its own descriptor.
                // Never wait for finalization to release a rejected one-shot key pipe.
                request.moshKeyRead.closeBrokerCopy()
                throw error
            }
        }

        override fun resizeSession(sessionId: String, columns: Int, rows: Int) {
            enforceTrustedCaller()
            require(columns in 1..1_000 && rows in 1..1_000) {
                "Terminal dimensions are outside the version-1 bounds"
            }
            val worker = synchronized(stateLock) { sessions[sessionId]?.worker }
            worker?.safeCall { resizeSession(sessionId, columns, rows) }
        }

        override fun updateNetworkHint(sessionId: String, hint: MoshNetworkHint) {
            enforceTrustedCaller()
            val update = synchronized(stateLock) {
                val session = sessions[sessionId] ?: return
                if (hint.connectivityGeneration < session.connectivityGeneration) return
                val changed = hint.connectivityGeneration > session.connectivityGeneration
                session.connectivityGeneration = hint.connectivityGeneration
                Pair(
                    session.worker,
                    changed && (
                        session.lastState == MoshSessionState.CONNECTED ||
                            session.lastState == MoshSessionState.ROAMING
                        ),
                )
            }
            if (update.second) {
                publishEvent(
                    MoshSessionEvent(
                        MoshApi.MODEL_VERSION,
                        sessionId,
                        MoshSessionState.ROAMING,
                        MoshDisconnectReason.NONE,
                        MoshErrorCode.NONE,
                        "",
                        hint.connectivityGeneration,
                    ),
                )
            }
            update.first?.safeCall {
                updateNetworkHint(sessionId, hint.connectivityGeneration)
            }
        }

        override fun stopSession(sessionId: String, reason: Int) {
            enforceTrustedCaller()
            require(reason in MoshStopReason.USER_REQUESTED..MoshStopReason.ERROR_RECOVERY) {
                "Unknown stop reason"
            }
            stopBrokerSession(sessionId, reason)
        }

        override fun registerCallback(callback: IMoshCallback) {
            enforceTrustedCaller()
            if (!callbacks.register(callback)) return
            val replay = synchronized(stateLock) { lastEvents.values.toList() }
            replay.forEach { event -> callback.safeEvent(event) }
        }

        override fun unregisterCallback(callback: IMoshCallback) {
            enforceTrustedCaller()
            callbacks.unregister(callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        stopAllSessions(MoshStopReason.APP_SHUTDOWN)
        return false
    }

    override fun onDestroy() {
        stopAllSessions(MoshStopReason.APP_SHUTDOWN)
        finishForeground()
        callbackBroadcastGate.run { callbacks.kill() }
        super.onDestroy()
    }

    private fun startSession(request: MoshSessionRequest): MoshSessionHandle {
        require(request.modelVersion == MoshApi.MODEL_VERSION) { "Unsupported request model" }
        require(request.optionFlags == 0L) { "This extension does not advertise optional display controls" }

        // Reserve the identifier, worker slot and pipe endpoints atomically. Binder dispatch can
        // call this method concurrently, so checking the map before inserting would let two equal
        // identifiers allocate different workers and overwrite one another.
        val prepared = synchronized(stateLock) {
            prepareSessionLocked(request).also {
                try {
                    ensureForegroundLocked()
                } catch (error: RuntimeException) {
                    discardPreparedSessionLocked(it)
                    throw IllegalStateException("Could not keep the Mosh transport active", error)
                }
            }
        }
        val session = prepared.session
        val connection = checkNotNull(session.connection)

        publishEvent(connectingEvent(request.sessionId))
        val bound = runCatching {
            bindService(
                Intent().setComponent(WORKER_COMPONENTS[session.slot]),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        if (!bound) {
            failSession(
                session,
                MoshErrorCode.NATIVE_INITIALIZATION_FAILED,
                "Could not start the isolated Mosh worker",
            )
        } else {
            // A worker can connect and report a terminal error on the main thread before this
            // Binder thread returns from bindService(). If cleanup already removed the session,
            // immediately undo the successful bind instead of marking an unreachable record bound.
            val stillActive = synchronized(stateLock) {
                if (shouldRetainWorkerBinding(
                        isCurrentSession = sessions[request.sessionId] === session,
                        isFinished = session.finished,
                    )
                ) {
                    session.bound = true
                    true
                } else {
                    false
                }
            }
            if (!stillActive) runCatching { unbindService(connection) }
        }

        // AIDL writes return parcelables with PARCELABLE_WRITE_RETURN_VALUE. That transfers these
        // two client ends to the main application's process and closes the broker's copies.
        return MoshSessionHandle(
            MoshApi.MODEL_VERSION,
            request.sessionId,
            prepared.terminalInputWrite,
            prepared.terminalOutputRead,
            MoshSessionState.CONNECTING,
            CAPABILITY_FLAGS,
        )
    }

    /** Must be called with [stateLock] held. */
    private fun prepareSessionLocked(request: MoshSessionRequest): PreparedBrokerSession {
        if (request.sessionId in sessions) {
            request.moshKeyRead.closeBrokerCopy()
            throw IllegalArgumentException("Session identifier is already active")
        }
        val slot = slotAllocator.acquire() ?: run {
            request.moshKeyRead.closeBrokerCopy()
            throw IllegalStateException("The extension's isolated Mosh workers are occupied")
        }
        val inputPipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (error: Exception) {
            request.moshKeyRead.closeBrokerCopy()
            slotAllocator.release(slot)
            throw IllegalStateException("Could not create terminal input pipe", error)
        }
        val outputPipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (error: Exception) {
            inputPipe.forEach { it.closeBrokerCopy() }
            request.moshKeyRead.closeBrokerCopy()
            slotAllocator.release(slot)
            throw IllegalStateException("Could not create terminal output pipe", error)
        }

        val session = BrokerSession(
            request = request,
            slot = slot,
            terminalInputRead = inputPipe[0],
            terminalOutputWrite = outputPipe[1],
        )
        session.workerCallback = createWorkerCallback(session)
        session.connection = WorkerConnection(session)
        sessions[request.sessionId] = session
        return PreparedBrokerSession(
            session = session,
            terminalInputWrite = inputPipe[1],
            terminalOutputRead = outputPipe[0],
        )
    }

    private inner class WorkerConnection(
        private val session: BrokerSession,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val worker = IMoshWorker.Stub.asInterface(service)
            val shouldStart = synchronized(stateLock) {
                if (sessions[session.request.sessionId] !== session || session.finished) {
                    false
                } else {
                    session.worker = worker
                    true
                }
            }
            if (!shouldStart) return

            val started = worker.safeCall {
                startSession(
                    session.request.sessionId,
                    session.request.serverAddress,
                    session.request.addressFamily,
                    session.request.udpPort,
                    session.request.moshKeyRead,
                    session.terminalInputRead,
                    session.terminalOutputWrite,
                    session.request.initialColumns,
                    session.request.initialRows,
                    session.request.locale,
                    session.request.optionFlags,
                    checkNotNull(session.workerCallback),
                )
            }
            // The Binder transaction owns duplicates after it returns. The broker must not retain
            // key or worker-pipe ends, otherwise EOF/cancellation cannot propagate correctly.
            session.closeWorkerDescriptors()
            if (!started) {
                failSession(
                    session,
                    MoshErrorCode.EXTENSION_DIED,
                    "The isolated Mosh worker stopped during startup",
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            failSession(
                session,
                MoshErrorCode.EXTENSION_DIED,
                "The isolated Mosh worker process stopped",
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName?) {
            failSession(
                session,
                MoshErrorCode.NATIVE_INITIALIZATION_FAILED,
                "The isolated Mosh worker did not expose its control interface",
            )
        }
    }

    private fun createWorkerCallback(session: BrokerSession): IMoshWorkerCallback =
        object : IMoshWorkerCallback.Stub() {
            override fun onSessionEvent(
                sessionId: String,
                state: Int,
                disconnectReason: Int,
                errorCode: Int,
                redactedDetail: String,
                connectivityGeneration: Long,
            ) {
                if (sessionId != session.request.sessionId) return
                val event = runCatching {
                    MoshSessionEvent(
                        MoshApi.MODEL_VERSION,
                        sessionId,
                        state,
                        disconnectReason,
                        errorCode,
                        redactedDetail,
                        connectivityGeneration,
                    )
                }.getOrElse {
                    MoshSessionEvent(
                        MoshApi.MODEL_VERSION,
                        session.request.sessionId,
                        MoshSessionState.ERROR,
                        MoshDisconnectReason.NONE,
                        MoshErrorCode.INTERNAL_REDACTED,
                        "The isolated Mosh worker returned an invalid state",
                        0,
                    )
                }
                synchronized(stateLock) {
                    if (sessions[session.request.sessionId] !== session) return
                    session.lastState = event.state
                    session.connectivityGeneration = event.connectivityGeneration
                }
                publishEvent(event)
                if (event.state == MoshSessionState.ERROR || event.state == MoshSessionState.DISCONNECTED) {
                    cleanupSession(session)
                }
            }
        }

    private fun stopBrokerSession(sessionId: String, reason: Int) {
        val session = synchronized(stateLock) { sessions[sessionId] } ?: return
        val worker = synchronized(stateLock) {
            if (session.finished) return
            session.stopReason = reason
            session.worker
        }
        if (worker == null) {
            val disconnectReason = when (reason) {
                MoshStopReason.SESSION_REPLACED -> MoshDisconnectReason.SESSION_REPLACED
                MoshStopReason.APP_SHUTDOWN -> MoshDisconnectReason.EXTENSION_FAILED
                else -> MoshDisconnectReason.USER_REQUESTED
            }
            publishEvent(disconnectedEvent(sessionId, disconnectReason))
            cleanupSession(session)
        } else if (!worker.safeCall { stopSession(sessionId, reason) }) {
            failSession(session, MoshErrorCode.EXTENSION_DIED, "The isolated Mosh worker stopped")
        }
    }

    private fun stopAllSessions(reason: Int) {
        val snapshot = synchronized(stateLock) { sessions.keys.toList() }
        snapshot.forEach { sessionId -> stopBrokerSession(sessionId, reason) }
    }

    private fun failSession(session: BrokerSession, errorCode: Int, detail: String) {
        val active = synchronized(stateLock) {
            sessions[session.request.sessionId] === session && !session.finished
        }
        if (!active) return
        publishEvent(
            MoshSessionEvent(
                MoshApi.MODEL_VERSION,
                session.request.sessionId,
                MoshSessionState.ERROR,
                MoshDisconnectReason.NONE,
                errorCode,
                detail,
                session.connectivityGeneration,
            ),
        )
        cleanupSession(session)
    }

    private fun cleanupSession(session: BrokerSession) {
        val shouldUnbind = synchronized(stateLock) {
            if (sessions[session.request.sessionId] !== session || session.finished) return
            session.finished = true
            sessions.remove(session.request.sessionId)
            slotAllocator.release(session.slot)
            if (sessions.isEmpty()) finishForegroundLocked()
            session.bound
        }
        session.closeWorkerDescriptors()
        val connection = session.connection
        if (shouldUnbind && connection != null) runCatching { unbindService(connection) }
    }

    /** Must be called with [stateLock] held. */
    private fun ensureForegroundLocked() {
        if (foregroundStarted) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.mosh_session_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openExtension = PendingIntent.getActivity(
            this,
            0,
            checkNotNull(packageManager.getLaunchIntentForPackage(packageName)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(getString(R.string.mosh_session_notification_title))
            .setContentText(getString(R.string.mosh_session_notification_body))
            .setContentIntent(openExtension)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun finishForeground() = synchronized(stateLock) { finishForegroundLocked() }

    /** Must be called with [stateLock] held. */
    private fun finishForegroundLocked() {
        if (!foregroundStarted) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    /** Must be called with [stateLock] held. */
    private fun discardPreparedSessionLocked(prepared: PreparedBrokerSession) {
        val session = prepared.session
        sessions.remove(session.request.sessionId)
        slotAllocator.release(session.slot)
        session.closeWorkerDescriptors()
        prepared.terminalInputWrite.closeBrokerCopy()
        prepared.terminalOutputRead.closeBrokerCopy()
    }

    private fun publishEvent(event: MoshSessionEvent) {
        synchronized(stateLock) {
            lastEvents[event.sessionId] = event
            sessions[event.sessionId]?.lastState = event.state
        }
        // Worker callbacks arrive on independent Binder threads. RemoteCallbackList rejects
        // overlapping beginBroadcast calls, so serialize only the callback delivery phase and
        // never hold stateLock while invoking the main application.
        callbackBroadcastGate.run {
            val count = callbacks.beginBroadcast()
            try {
                for (index in 0 until count) callbacks.getBroadcastItem(index).safeEvent(event)
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun enforceTrustedCaller() {
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty()
        val signaturesMatch = packageManager.checkSignatures(callingUid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
        if (!isTrustedMainCaller(callingUid, Process.myUid(), callerPackages, signaturesMatch)) {
            throw SecurityException("Caller is not the trusted Terminal Spike application")
        }
    }

    private data class BrokerSession(
        val request: MoshSessionRequest,
        val slot: Int,
        val terminalInputRead: ParcelFileDescriptor,
        val terminalOutputWrite: ParcelFileDescriptor,
        var connection: ServiceConnection? = null,
        var workerCallback: IMoshWorkerCallback? = null,
        var worker: IMoshWorker? = null,
        var bound: Boolean = false,
        var finished: Boolean = false,
        var stopReason: Int = 0,
        var lastState: Int = MoshSessionState.CONNECTING,
        var connectivityGeneration: Long = 0,
    ) {
        fun closeWorkerDescriptors() {
            request.moshKeyRead.closeBrokerCopy()
            terminalInputRead.closeBrokerCopy()
            terminalOutputWrite.closeBrokerCopy()
        }
    }

    private data class PreparedBrokerSession(
        val session: BrokerSession,
        val terminalInputWrite: ParcelFileDescriptor,
        val terminalOutputRead: ParcelFileDescriptor,
    )

    private companion object {
        const val MAX_REPLAY_EVENTS = 64
        const val NOTIFICATION_CHANNEL_ID = "active_mosh_sessions"
        const val NOTIFICATION_ID = 2_002
        val CAPABILITY_FLAGS = MoshCapability.IPV4 or
            MoshCapability.IPV6 or
            MoshCapability.NETWORK_ROAMING or
            MoshCapability.MULTIPLE_SESSIONS
        val CAPABILITIES = MoshCapabilities(
            MoshApi.MODEL_VERSION,
            MoshApi.PROTOCOL_VERSION,
            MoshApi.PROTOCOL_VERSION,
            CAPABILITY_FLAGS,
            MOSH_WORKER_COUNT,
        )
        val WORKER_COMPONENTS = arrayOf(
            ComponentName("com.yanjiyu.terminalspike", MoshWorker0Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker1Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker2Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker3Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker4Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker5Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker6Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker7Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker8Service::class.java.name),
            ComponentName("com.yanjiyu.terminalspike", MoshWorker9Service::class.java.name),
        )
        init {
            check(WORKER_COMPONENTS.size == MOSH_WORKER_COUNT)
        }
    }
}

internal fun isTrustedMainCaller(
    callingUid: Int,
    extensionUid: Int,
    callerPackages: Set<String>,
    signaturesMatch: Boolean,
): Boolean = callingUid == extensionUid &&
    MAIN_APPLICATION_PACKAGE in callerPackages &&
    signaturesMatch

internal fun shouldRetainWorkerBinding(
    isCurrentSession: Boolean,
    isFinished: Boolean,
): Boolean = isCurrentSession && !isFinished

internal class SerializedCallbackGate {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock) { block() }
}

internal class WorkerSlotAllocator(private val size: Int) {
    init {
        require(size > 0)
    }

    private val occupied = BooleanArray(size)

    fun acquire(): Int? {
        val slot = occupied.indexOfFirst { !it }
        if (slot < 0) return null
        occupied[slot] = true
        return slot
    }

    fun release(slot: Int) {
        require(slot in occupied.indices) { "Invalid worker slot" }
        check(occupied[slot]) { "Worker slot was not occupied" }
        occupied[slot] = false
    }
}

private inline fun IMoshWorker.safeCall(block: IMoshWorker.() -> Unit): Boolean = try {
    block()
    true
} catch (_: RemoteException) {
    false
}

private fun IMoshCallback.safeEvent(event: MoshSessionEvent) {
    try {
        onSessionEvent(event)
    } catch (_: RemoteException) {
        // RemoteCallbackList observes binder death. No endpoint or session detail is logged here.
    }
}

private fun connectingEvent(sessionId: String) = MoshSessionEvent(
    MoshApi.MODEL_VERSION,
    sessionId,
    MoshSessionState.CONNECTING,
    MoshDisconnectReason.NONE,
    MoshErrorCode.NONE,
    "",
    0,
)

private fun disconnectedEvent(sessionId: String, reason: Int) = MoshSessionEvent(
    MoshApi.MODEL_VERSION,
    sessionId,
    MoshSessionState.DISCONNECTED,
    reason,
    MoshErrorCode.NONE,
    "",
    0,
)

private const val MAIN_APPLICATION_PACKAGE = "com.yanjiyu.terminalspike"
internal const val MOSH_WORKER_COUNT = 10

private fun ParcelFileDescriptor.closeBrokerCopy() {
    runCatching(::close)
}
