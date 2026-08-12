/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.BadParcelableException
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoshParcelableInstrumentedTest {
    @Test
    fun capabilitiesNetworkHintAndEventRoundTrip() {
        val capabilities = roundTrip(
            MoshCapabilities(
                1,
                1,
                1,
                MoshCapability.IPV4 or MoshCapability.IPV6 or MoshCapability.NETWORK_ROAMING,
                4,
            ),
            MoshCapabilities.CREATOR,
        )
        val hint = roundTrip(
            MoshNetworkHint(1, 7, MoshAddressFamily.IPV6, false),
            MoshNetworkHint.CREATOR,
        )
        val event = roundTrip(
            MoshSessionEvent(
                1,
                SESSION_ID,
                MoshSessionState.ROAMING,
                MoshDisconnectReason.NONE,
                MoshErrorCode.NONE,
                "Network changed",
                7,
            ),
            MoshSessionEvent.CREATOR,
        )

        assertEquals(4, capabilities.maximumConcurrentSessions)
        assertEquals(MoshAddressFamily.IPV6, hint.addressFamily)
        assertEquals(MoshSessionState.ROAMING, event.state)
        assertEquals("Network changed", event.redactedDetail)
    }

    @Test
    fun requestRoundTripKeepsKeyInOneShotFileDescriptorAndCopiesAddress() {
        val keyPipe = ParcelFileDescriptor.createPipe()
        val address = byteArrayOf(192.toByte(), 0, 2, 25)
        val request = MoshSessionRequest(
            modelVersion = 1,
            sessionId = SESSION_ID,
            serverAddress = address,
            addressFamily = MoshAddressFamily.IPV4,
            udpPort = 60_001,
            moshKeyRead = keyPipe[0],
            initialColumns = 120,
            initialRows = 40,
            locale = "en_GB.UTF-8",
            optionFlags = MoshOption.PREDICTION_ALWAYS,
        )
        address[0] = 10
        val received = roundTrip(request, MoshSessionRequest.CREATOR)

        ParcelFileDescriptor.AutoCloseOutputStream(keyPipe[1]).use { output -> output.write(MOSH_KEY) }
        val actualKey = ParcelFileDescriptor.AutoCloseInputStream(received.moshKeyRead).use { input ->
            input.readExactly(MOSH_KEY.size)
        }

        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 25), request.serverAddress)
        val returned = received.serverAddress
        returned[0] = 10
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 25), received.serverAddress)
        assertArrayEquals(MOSH_KEY, actualKey)
        request.moshKeyRead.close()
    }

    @Test
    fun sessionHandleFileDescriptorsCarryTerminalBytesOutsideBinder() {
        val inputPipe = ParcelFileDescriptor.createPipe()
        val outputPipe = ParcelFileDescriptor.createPipe()
        val handle = MoshSessionHandle(
            modelVersion = 1,
            sessionId = SESSION_ID,
            terminalInputWrite = inputPipe[1],
            terminalOutputRead = outputPipe[0],
            initialState = MoshSessionState.CONNECTING,
            negotiatedCapabilityFlags = MoshCapability.IPV4,
        )
        val received = roundTrip(handle, MoshSessionHandle.CREATOR)

        ParcelFileDescriptor.AutoCloseOutputStream(received.terminalInputWrite).use {
            it.write("input".encodeToByteArray())
        }
        val inputBytes = ParcelFileDescriptor.AutoCloseInputStream(inputPipe[0]).use {
            it.readExactly("input".length)
        }

        ParcelFileDescriptor.AutoCloseOutputStream(outputPipe[1]).use {
            it.write("output".encodeToByteArray())
        }
        val outputBytes = ParcelFileDescriptor.AutoCloseInputStream(received.terminalOutputRead).use {
            it.readExactly("output".length)
        }

        assertArrayEquals("input".encodeToByteArray(), inputBytes)
        assertArrayEquals("output".encodeToByteArray(), outputBytes)
        handle.terminalInputWrite.close()
        handle.terminalOutputRead.close()
    }

    @Test
    fun boundedModelsRejectOversizedOrInvalidValuesBeforeBinder() {
        val keyPipe = ParcelFileDescriptor.createPipe()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                MoshSessionRequest(
                    1,
                    SESSION_ID,
                    byteArrayOf(127, 0, 0, 1),
                    MoshAddressFamily.IPV4,
                    60_001,
                    keyPipe[0],
                    MoshContractLimit.MAX_COLUMNS + 1,
                    40,
                    "en_GB.UTF-8",
                    0,
                )
            }
        } finally {
            keyPipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test
    fun truncatedSizePrefixedParcelableIsRejected() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(Int.SIZE_BYTES)
            parcel.setDataPosition(0)

            assertThrows(BadParcelableException::class.java) {
                MoshNetworkHint.CREATOR.createFromParcel(parcel)
            }
        } finally {
            parcel.recycle()
        }
    }

    private fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun InputStream.readExactly(byteCount: Int): ByteArray {
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = read(result, offset, byteCount - offset)
            check(read >= 0) { "Pipe closed after $offset of $byteCount bytes" }
            offset += read
        }
        return result
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
        val MOSH_KEY = "abcdefghijklmnopqrstuv".encodeToByteArray()
    }
}
