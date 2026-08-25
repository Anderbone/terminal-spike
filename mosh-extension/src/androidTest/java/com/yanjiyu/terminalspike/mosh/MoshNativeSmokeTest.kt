/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MoshNativeSmokeTest {
    @Test
    fun launcherUsesTheMatchingMoshAdaptiveIconFamily() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val applicationInfo = context.applicationInfo

        assertEquals(R.mipmap.ic_launcher, applicationInfo.icon)
        assertNotNull(context.packageManager.getApplicationIcon(applicationInfo))
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
