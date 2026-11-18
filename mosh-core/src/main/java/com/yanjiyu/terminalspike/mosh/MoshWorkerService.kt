/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorker
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorkerCallback
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The executor must dispatch asynchronously; native work must never run on a Binder thread. */
public abstract class BaseMoshWorkerService protected constructor(
    private val executor: ExecutorService = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
    ) { runnable ->
        Thread(runnable, "mosh-native-session").apply { isDaemon = true }
    },
) : Service() {
    private val stateLock = Any()
    private var runningSession: RunningSession? = null
    private var destroyed = false

    private val binder = object : IMoshWorker.Stub() {
        override fun startSession(
            sessionId: String,
            serverAddress: ByteArray,
            addressFamily: Int,
            udpPort: Int,
            moshKeyRead: ParcelFileDescriptor,
            terminalInputRead: ParcelFileDescriptor,
            terminalOutputWrite: ParcelFileDescriptor,
            initialColumns: Int,
            initialRows: Int,
            locale: String,
            optionFlags: Long,
            callback: IMoshWorkerCallback,
        ) {
            enforceSameApplicationCaller()
            val descriptors = listOf(moshKeyRead, terminalInputRead, terminalOutputWrite)
            val failure = validateWorkerRequest(
                sessionId = sessionId,
                address = serverAddress,
                addressFamily = addressFamily,
                udpPort = udpPort,
                columns = initialColumns,
                rows = initialRows,
                locale = locale,
                optionFlags = optionFlags,
            )
            if (failure != null) {
                descriptors.forEach { it.closeQuietly() }
                callback.safeEvent(
                    sessionId = sessionId,
                    state = MoshSessionState.ERROR,
                    errorCode = failure.first,
                    detail = failure.second,
                )
                return
            }

            lateinit var session: RunningSession
            val deathRecipient = IBinder.DeathRecipient {
                requestStop(session, MoshStopReason.APP_SHUTDOWN, preserveReason = true)
            }
            val task = Runnable {
                runNativeSession(
                    session = session,
                    serverAddress = serverAddress,
                    udpPort = udpPort,
                    moshKeyRead = moshKeyRead,
                    terminalInputRead = terminalInputRead,
                    terminalOutputWrite = terminalOutputWrite,
                    columns = initialColumns,
                    rows = initialRows,
                )
            }
            session = RunningSession(
                sessionId = sessionId,
                callback = callback,
                callbackDeathRecipient = deathRecipient,
                descriptors = descriptors,
                task = task,
            )
            val rejection = synchronized(stateLock) {
                when {
                    destroyed -> NativeFinalEvent(
                        state = MoshSessionState.ERROR,
                        errorCode = MoshErrorCode.CANCELLED,
                        detail = "Worker process is stopping",
                    )
                    runningSession != null -> NativeFinalEvent(
                        state = MoshSessionState.ERROR,
                        errorCode = MoshErrorCode.INTERNAL_REDACTED,
                        detail = "Worker process is already occupied",
                    )
                    else -> {
                        runningSession = session
                        null
                    }
                }
            }
            if (rejection != null) {
                finishSession(session, rejection)
                return
            }

            try {
                synchronized(stateLock) {
                    // A stop may win admission; never register a callback after its cleanup.
                    if (session.finished) return
                    callback.asBinder().linkToDeath(deathRecipient, 0)
                    if (session.finished) return
                }
            } catch (_: RemoteException) {
                finishSession(session)
                return
            }

            try {
                synchronized(stateLock) {
                    if (session.finished) return
                    // Serialize placement with queued cancellation so removal cannot precede it.
                    executor.execute(session.task)
                }
            } catch (_: RejectedExecutionException) {
                finishSession(
                    session,
                    NativeFinalEvent(
                        state = MoshSessionState.ERROR,
                        errorCode = MoshErrorCode.NATIVE_INITIALIZATION_FAILED,
                        detail = "Could not schedule the native Mosh session",
                    ),
                )
            }
        }

        override fun resizeSession(sessionId: String, columns: Int, rows: Int) {
            enforceSameApplicationCaller()
            if (sessionId == synchronized(stateLock) { runningSession?.sessionId }) {
                runCatching { MoshNativeBridge.resize(sessionId, columns, rows) }
            }
        }

        override fun updateNetworkHint(sessionId: String, connectivityGeneration: Long) {
            enforceSameApplicationCaller()
            val session = synchronized(stateLock) { runningSession }
            if (session?.sessionId == sessionId) {
                session.connectivityGeneration.set(connectivityGeneration)
                runCatching { MoshNativeBridge.updateNetworkHint(sessionId, connectivityGeneration) }
            }
        }

        override fun stopSession(sessionId: String, reason: Int) {
            enforceSameApplicationCaller()
            val session = synchronized(stateLock) { runningSession }
            if (session?.sessionId == sessionId) {
                requestStop(session, reason)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val session = synchronized(stateLock) {
            destroyed = true
            runningSession
        }
        session?.let {
            requestStop(it, MoshStopReason.APP_SHUTDOWN, preserveReason = true)
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runNativeSession(
        session: RunningSession,
        serverAddress: ByteArray,
        udpPort: Int,
        moshKeyRead: ParcelFileDescriptor,
        terminalInputRead: ParcelFileDescriptor,
        terminalOutputWrite: ParcelFileDescriptor,
        columns: Int,
        rows: Int,
    ) {
        val canRun = synchronized(stateLock) {
            if (session.finished || destroyed || runningSession !== session || session.stopReason.get() != 0) {
                false
            } else {
                session.nativeStarted = true
                true
            }
        }
        if (!canRun) return

        try {
            val numericAddress = runCatching { InetAddress.getByAddress(serverAddress).hostAddress }
                .getOrNull()
            if (numericAddress.isNullOrBlank()) {
                finishSession(
                    session,
                    NativeFinalEvent(
                        state = MoshSessionState.ERROR,
                        errorCode = MoshErrorCode.INVALID_REQUEST,
                        detail = "Invalid numeric server address",
                    ),
                )
                return
            }

            val listener = object : MoshNativeListener {
                override fun isCancellationRequested() = session.stopReason.get() != 0

                override fun onConnected(connectivityGeneration: Long) {
                    session.callback.safeEvent(
                        sessionId = session.sessionId,
                        state = MoshSessionState.CONNECTED,
                        connectivityGeneration = connectivityGeneration,
                        onFailure = {
                            requestStop(session, MoshStopReason.APP_SHUTDOWN, preserveReason = true)
                        },
                    )
                }
            }
            val result = try {
                // JNI borrows these descriptors. This session retains their Java ownership
                // until native work returns, including class-loading/JNI-dispatch failures.
                MoshNativeBridge.runSession(
                    sessionId = session.sessionId,
                    serverAddress = numericAddress,
                    udpPort = udpPort,
                    keyReadFd = moshKeyRead.fd,
                    terminalInputReadFd = terminalInputRead.fd,
                    terminalOutputWriteFd = terminalOutputWrite.fd,
                    initialColumns = columns,
                    initialRows = rows,
                    listener = listener,
                )
            } catch (_: Throwable) {
                MoshNativeResult.INTERNAL_ERROR
            }
            finishSession(session, nativeResultEvent(result, session.stopReason.get()))
        } finally {
            finishSession(session)
        }
    }

    private fun requestStop(session: RunningSession, reason: Int, preserveReason: Boolean = false) {
        val queued = synchronized(stateLock) {
            if (session.finished) return
            if (preserveReason) session.stopReason.compareAndSet(0, reason) else session.stopReason.set(reason)
            !session.nativeStarted
        }
        if (queued) {
            finishSession(session, nativeResultEvent(MoshNativeResult.CANCELLED, session.stopReason.get()))
        } else {
            runCatching { MoshNativeBridge.stop(session.sessionId) }
        }
    }

    private fun finishSession(session: RunningSession, event: NativeFinalEvent? = null) {
        val wasCurrent = synchronized(stateLock) {
            if (session.finished) return
            session.finished = true
            if (!session.nativeStarted) {
                (executor as? ThreadPoolExecutor)?.remove(session.task)
            }
            (runningSession === session).also { if (it) runningSession = null }
        }
        session.descriptors.forEach { it.closeQuietly() }
        runCatching { session.callback.asBinder().unlinkToDeath(session.callbackDeathRecipient, 0) }
        try {
            event?.let {
                session.callback.safeEvent(
                    sessionId = session.sessionId,
                    state = it.state,
                    disconnectReason = it.disconnectReason,
                    errorCode = it.errorCode,
                    detail = it.detail,
                    connectivityGeneration = session.connectivityGeneration.get(),
                )
            }
        } finally {
            if (wasCurrent) stopSelf()
        }
    }

    private fun enforceSameApplicationCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Mosh worker accepts only same-application calls")
        }
    }

    private data class RunningSession(
        val sessionId: String,
        val callback: IMoshWorkerCallback,
        val callbackDeathRecipient: IBinder.DeathRecipient,
        val descriptors: List<ParcelFileDescriptor>,
        val task: Runnable,
        val stopReason: AtomicInteger = AtomicInteger(0),
        val connectivityGeneration: AtomicLong = AtomicLong(0),
    ) {
        // Admission, native ownership and finalization are serialized by stateLock.
        var nativeStarted = false
        var finished = false
    }
}

public class MoshWorker0Service : BaseMoshWorkerService()
public class MoshWorker1Service : BaseMoshWorkerService()
public class MoshWorker2Service : BaseMoshWorkerService()
public class MoshWorker3Service : BaseMoshWorkerService()
public class MoshWorker4Service : BaseMoshWorkerService()
public class MoshWorker5Service : BaseMoshWorkerService()
public class MoshWorker6Service : BaseMoshWorkerService()
public class MoshWorker7Service : BaseMoshWorkerService()
public class MoshWorker8Service : BaseMoshWorkerService()
public class MoshWorker9Service : BaseMoshWorkerService()

internal data class NativeFinalEvent(
    val state: Int,
    val disconnectReason: Int = MoshDisconnectReason.NONE,
    val errorCode: Int = MoshErrorCode.NONE,
    val detail: String = "",
)

internal fun nativeResultEvent(result: Int, stopReason: Int): NativeFinalEvent = when (result) {
    MoshNativeResult.REMOTE_CLOSED -> NativeFinalEvent(
        state = MoshSessionState.DISCONNECTED,
        disconnectReason = MoshDisconnectReason.REMOTE_CLOSED,
    )
    MoshNativeResult.CANCELLED -> NativeFinalEvent(
        state = MoshSessionState.DISCONNECTED,
        disconnectReason = when (stopReason) {
            MoshStopReason.SESSION_REPLACED -> MoshDisconnectReason.SESSION_REPLACED
            MoshStopReason.APP_SHUTDOWN -> MoshDisconnectReason.EXTENSION_FAILED
            else -> MoshDisconnectReason.USER_REQUESTED
        },
    )
    MoshNativeResult.KEY_READ_FAILED -> NativeFinalEvent(
        state = MoshSessionState.ERROR,
        errorCode = MoshErrorCode.KEY_READ_FAILED,
        detail = "The one-shot Mosh key could not be read",
    )
    MoshNativeResult.UDP_TIMEOUT -> NativeFinalEvent(
        state = MoshSessionState.ERROR,
        errorCode = MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL,
        detail = "No authenticated response arrived from the Mosh server",
    )
    MoshNativeResult.INITIALIZATION_FAILED -> NativeFinalEvent(
        state = MoshSessionState.ERROR,
        errorCode = MoshErrorCode.NATIVE_INITIALIZATION_FAILED,
        detail = "The native Mosh client could not initialize",
    )
    MoshNativeResult.TERMINAL_PIPE_CLOSED -> NativeFinalEvent(
        state = MoshSessionState.ERROR,
        errorCode = MoshErrorCode.EXTENSION_DIED,
        detail = "The terminal byte pipe closed unexpectedly",
    )
    else -> NativeFinalEvent(
        state = MoshSessionState.ERROR,
        errorCode = MoshErrorCode.INTERNAL_REDACTED,
        detail = "The isolated Mosh worker stopped unexpectedly",
    )
}

private fun validateWorkerRequest(
    sessionId: String,
    address: ByteArray,
    addressFamily: Int,
    udpPort: Int,
    columns: Int,
    rows: Int,
    locale: String,
    optionFlags: Long,
): Pair<Int, String>? {
    if (sessionId.isBlank() || udpPort !in 1..65_535 || columns !in 1..1_000 || rows !in 1..1_000) {
        return MoshErrorCode.INVALID_REQUEST to "Invalid Mosh session parameters"
    }
    val addressLength = when (addressFamily) {
        MoshAddressFamily.IPV4 -> 4
        MoshAddressFamily.IPV6 -> 16
        else -> -1
    }
    if (address.size != addressLength) {
        return MoshErrorCode.INVALID_REQUEST to "Invalid numeric server address"
    }
    val normalizedLocale = locale.uppercase(Locale.ROOT)
    if (!normalizedLocale.contains("UTF-8") && !normalizedLocale.endsWith(".UTF8")) {
        return MoshErrorCode.LOCALE_UNSUPPORTED to "Mosh requires a UTF-8 locale"
    }
    if (optionFlags != 0L) {
        return MoshErrorCode.INVALID_REQUEST to "Requested options were not negotiated"
    }
    return null
}

private fun IMoshWorkerCallback.safeEvent(
    sessionId: String,
    state: Int,
    disconnectReason: Int = MoshDisconnectReason.NONE,
    errorCode: Int = MoshErrorCode.NONE,
    detail: String = "",
    connectivityGeneration: Long = 0,
    onFailure: () -> Unit = {},
) {
    try {
        onSessionEvent(
            sessionId,
            state,
            disconnectReason,
            errorCode,
            detail,
            connectivityGeneration,
        )
    } catch (_: RemoteException) {
        onFailure()
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching(::close)
}
