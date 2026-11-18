/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MoshNativeSmokeTest {
    @Test
    fun cancelledAdmissionStopsBeforeReadingTheKey() {
        val listener = object : MoshNativeListener {
            override fun isCancellationRequested() = true
            override fun onConnected(connectivityGeneration: Long) = Unit
        }
        assertEquals(MoshNativeResult.CANCELLED, runWithKey(listener, byteArrayOf(0x41), expectUnreadKey = true))
    }

    @Test
    fun uncancelledAdmissionStillValidatesTheOneShotKey() {
        assertEquals(MoshNativeResult.KEY_READ_FAILED, runWithKey(NO_OP_LISTENER))
    }

    @Test
    fun failedCancellationQueryDoesNotEscapeJniOrLeaveNativeControlOccupied() {
        val listener = object : MoshNativeListener {
            override fun isCancellationRequested(): Boolean = error("Controlled callback failure")
            override fun onConnected(connectivityGeneration: Long) = Unit
        }
        assertEquals(MoshNativeResult.INTERNAL_ERROR, runWithKey(listener))
        assertEquals(MoshNativeResult.KEY_READ_FAILED, runWithKey(NO_OP_LISTENER))
    }

    private fun runWithKey(
        listener: MoshNativeListener,
        payload: ByteArray = byteArrayOf(),
        expectUnreadKey: Boolean = false,
    ): Int {
        val key = ParcelFileDescriptor.createPipe()
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(key[1]).use { it.write(payload) }
        try {
            return MoshNativeBridge.runSession(
                UUID.randomUUID().toString(), "127.0.0.1", 60_001,
                key[0].fd, input[0].fd, output[1].fd, 80, 24, listener,
            ).also {
                assertTrue(key[0].fileDescriptor.valid())
                assertTrue(input[0].fileDescriptor.valid())
                assertTrue(output[1].fileDescriptor.valid())
                if (expectUnreadKey) {
                    val remaining = ByteArray(payload.size)
                    assertEquals(payload.size, Os.read(key[0].fileDescriptor, remaining, 0, remaining.size))
                    assertArrayEquals(payload, remaining)
                }
            }
        } finally {
            listOf(key, input, output).forEach { pipe -> pipe.forEach { runCatching { it.close() } } }
        }
    }

    @Test
    fun nativeLicenceNoticesArePackagedWithTheTransport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notices = context.assets.open("mosh/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
        assertTrue(notices.contains("GPL-3.0-or-later"))
        assertTrue(notices.contains("GNU Nettle"))
        assertTrue(context.assets.open("MOSH-OPENSSL-EXCEPTION").use { it.readBytes().isNotEmpty() })
    }

    @Test
    fun packagedNativeEngineReportsPinnedUpstreamVersion() {
        assertEquals("mosh-1.4.0", MoshNativeBridge.version())
    }

    @Test
    fun nativeEngineConsumesAndRejectsMalformedOneShotKey() {
        val keyPipe = ParcelFileDescriptor.createReliablePipe()
        ParcelFileDescriptor.AutoCloseOutputStream(keyPipe[1]).use { output ->
            output.write("!!!!!!!!!!!!!!!!!!!!!!".encodeToByteArray())
        }
        val inputPipe = ParcelFileDescriptor.createReliablePipe()
        val outputPipe = ParcelFileDescriptor.createReliablePipe()
        val result = try {
            MoshNativeBridge.runSession(
                sessionId = UUID.randomUUID().toString(),
                serverAddress = "127.0.0.1",
                udpPort = 60_001,
                keyReadFd = keyPipe[0].fd,
                terminalInputReadFd = inputPipe[0].fd,
                terminalOutputWriteFd = outputPipe[1].fd,
                initialColumns = 80,
                initialRows = 24,
                listener = NO_OP_LISTENER,
            )
        } finally {
            keyPipe[0].close()
            inputPipe.forEach { descriptor -> runCatching { descriptor.close() } }
            outputPipe.forEach { descriptor -> runCatching { descriptor.close() } }
        }
        assertEquals(MoshNativeResult.KEY_READ_FAILED, result)
    }

    @Test
    fun partialNativeDupFailureNeverClosesBorrowedJavaDescriptors() {
        val keyPipe = ParcelFileDescriptor.createReliablePipe()
        val outputPipe = ParcelFileDescriptor.createReliablePipe()
        try {
            val result = MoshNativeBridge.runSession(
                sessionId = UUID.randomUUID().toString(),
                serverAddress = "127.0.0.1",
                udpPort = 60_001,
                keyReadFd = keyPipe[0].fd,
                terminalInputReadFd = Int.MAX_VALUE,
                terminalOutputWriteFd = outputPipe[1].fd,
                initialColumns = 80,
                initialRows = 24,
                listener = NO_OP_LISTENER,
            )

            assertEquals(MoshNativeResult.INITIALIZATION_FAILED, result)
            ParcelFileDescriptor.AutoCloseOutputStream(keyPipe[1]).use { it.write(0x41) }
            ParcelFileDescriptor.AutoCloseInputStream(keyPipe[0]).use { input ->
                assertEquals(0x41, input.read())
            }
            ParcelFileDescriptor.AutoCloseOutputStream(outputPipe[1]).use { it.write(0x42) }
            ParcelFileDescriptor.AutoCloseInputStream(outputPipe[0]).use { input ->
                assertEquals(0x42, input.read())
            }
            assertTrue(keyPipe.all { !it.fileDescriptor.valid() })
            assertTrue(outputPipe.all { !it.fileDescriptor.valid() })
        } finally {
            keyPipe.forEach { descriptor -> runCatching { descriptor.close() } }
            outputPipe.forEach { descriptor -> runCatching { descriptor.close() } }
        }
    }

    private companion object {
        val NO_OP_LISTENER = object : MoshNativeListener {
            override fun onConnected(connectivityGeneration: Long) = Unit
        }
    }
}
