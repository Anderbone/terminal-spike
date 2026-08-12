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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

public abstract class BaseMoshWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mosh-native-session").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private var runningSession: RunningSession? = null

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

            val deathRecipient = IBinder.DeathRecipient {
                runCatching { MoshNativeBridge.stop(sessionId) }
            }
            val session = RunningSession(
                sessionId = sessionId,
                callback = callback,
                callbackDeathRecipient = deathRecipient,
            )
            synchronized(stateLock) {
                if (runningSession != null) {
                    descriptors.forEach { it.closeQuietly() }
                    callback.safeEvent(
                        sessionId = sessionId,
                        state = MoshSessionState.ERROR,
                        errorCode = MoshErrorCode.INTERNAL_REDACTED,
                        detail = "Worker process is already occupied",
                    )
                    return
                }
                runningSession = session
            }

            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
                synchronized(stateLock) { runningSession = null }
                descriptors.forEach { it.closeQuietly() }
                return
            }

            executor.execute {
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
                session.stopReason.set(reason)
                runCatching { MoshNativeBridge.stop(sessionId) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(stateLock) { runningSession }?.let { session ->
            session.stopReason.compareAndSet(0, MoshStopReason.APP_SHUTDOWN)
            runCatching { MoshNativeBridge.stop(session.sessionId) }
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
        val numericAddress = runCatching { InetAddress.getByAddress(serverAddress).hostAddress }
            .getOrNull()
        if (numericAddress.isNullOrBlank()) {
            listOf(moshKeyRead, terminalInputRead, terminalOutputWrite)
                .forEach { it.closeQuietly() }
            session.callback.safeEvent(
                sessionId = session.sessionId,
                state = MoshSessionState.ERROR,
                errorCode = MoshErrorCode.INVALID_REQUEST,
                detail = "Invalid numeric server address",
            )
            finishSession(session)
            return
        }

        val listener = object : MoshNativeListener {
            override fun onConnected(connectivityGeneration: Long) {
                session.callback.safeEvent(
                    sessionId = session.sessionId,
                    state = MoshSessionState.CONNECTED,
                    connectivityGeneration = connectivityGeneration,
                )
            }
        }
        val descriptors = listOf(moshKeyRead, terminalInputRead, terminalOutputWrite)
        val result = try {
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
        } finally {
            // JNI borrows these descriptors and duplicates them before native work. Keeping Java
            // ownership until this finally block also closes them if class loading or JNI symbol
            // resolution fails before native code is entered.
            descriptors.forEach { it.closeQuietly() }
        }

        val finalEvent = nativeResultEvent(result, session.stopReason.get())
        session.callback.safeEvent(
            sessionId = session.sessionId,
            state = finalEvent.state,
            disconnectReason = finalEvent.disconnectReason,
            errorCode = finalEvent.errorCode,
            detail = finalEvent.detail,
            connectivityGeneration = session.connectivityGeneration.get(),
        )
        finishSession(session)
    }

    private fun finishSession(session: RunningSession) {
        runCatching { session.callback.asBinder().unlinkToDeath(session.callbackDeathRecipient, 0) }
        synchronized(stateLock) {
            if (runningSession === session) runningSession = null
        }
        stopSelf()
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
        val stopReason: AtomicInteger = AtomicInteger(0),
        val connectivityGeneration: AtomicLong = AtomicLong(0),
    )
}

public class MoshWorker0Service : BaseMoshWorkerService()
public class MoshWorker1Service : BaseMoshWorkerService()
public class MoshWorker2Service : BaseMoshWorkerService()
public class MoshWorker3Service : BaseMoshWorkerService()

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
        runCatching { MoshNativeBridge.stop(sessionId) }
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching(::close)
}
