/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

internal interface MoshNativeListener {
    /** Called after an authenticated server packet for [connectivityGeneration] is accepted. */
    fun onConnected(connectivityGeneration: Long)
}

internal object MoshNativeResult {
    const val REMOTE_CLOSED: Int = 0
    const val CANCELLED: Int = 1
    const val KEY_READ_FAILED: Int = 2
    const val UDP_TIMEOUT: Int = 3
    const val INITIALIZATION_FAILED: Int = 4
    const val TERMINAL_PIPE_CLOSED: Int = 5
    const val ALREADY_RUNNING: Int = 6
    const val INTERNAL_ERROR: Int = 7
}

internal object MoshNativeBridge {
    init {
        System.loadLibrary("mosh_extension")
    }

    fun version(): String = nativeVersion()

    /**
     * Borrows all three descriptors for this blocking call. JNI duplicates them with close-on-exec
     * before doing any session work; the Java caller remains responsible for closing its originals.
     */
    fun runSession(
        sessionId: String,
        serverAddress: String,
        udpPort: Int,
        keyReadFd: Int,
        terminalInputReadFd: Int,
        terminalOutputWriteFd: Int,
        initialColumns: Int,
        initialRows: Int,
        listener: MoshNativeListener,
    ): Int = nativeRunSession(
        sessionId,
        serverAddress,
        udpPort,
        keyReadFd,
        terminalInputReadFd,
        terminalOutputWriteFd,
        initialColumns,
        initialRows,
        listener,
    )

    fun resize(sessionId: String, columns: Int, rows: Int) {
        nativeResize(sessionId, columns, rows)
    }

    fun updateNetworkHint(sessionId: String, connectivityGeneration: Long) {
        nativeUpdateNetworkHint(sessionId, connectivityGeneration)
    }

    fun stop(sessionId: String) {
        nativeStop(sessionId)
    }

    private external fun nativeVersion(): String

    private external fun nativeRunSession(
        sessionId: String,
        serverAddress: String,
        udpPort: Int,
        keyReadFd: Int,
        terminalInputReadFd: Int,
        terminalOutputWriteFd: Int,
        initialColumns: Int,
        initialRows: Int,
        listener: MoshNativeListener,
    ): Int

    private external fun nativeResize(sessionId: String, columns: Int, rows: Int)

    private external fun nativeUpdateNetworkHint(sessionId: String, connectivityGeneration: Long)

    private external fun nativeStop(sessionId: String)
}
