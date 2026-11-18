package com.yanjiyu.terminalspike.core.backup

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupInputOwnershipTest {
    @Test
    fun providerCloseFailureReleasesAnOtherwiseSuccessfulOwnedResult() = runTest {
        val expected = IOException("Provider close failed")
        val input = ClosingInput(expected)
        val result = OwnedResult()

        val failure = runCatching { input.readOwnedResult { result } }.exceptionOrNull()

        assertSame(expected, failure)
        assertEquals(1, input.closes)
        assertEquals(1, result.closes)
        assertTrue(result.bytes.all { it == 0.toByte() })
    }

    @Test
    fun resultCloseFailureIsSuppressedBehindTheProviderFailure() = runTest {
        val expected = IOException("Provider close failed")
        val cleanup = IOException("Result close failed")
        val result = OwnedResult(cleanup)

        val failure = runCatching { ClosingInput(expected).readOwnedResult { result } }.exceptionOrNull()

        assertSame(expected, failure)
        assertEquals(1, result.closes)
        assertSame(cleanup, failure!!.suppressed.single())
        assertTrue(result.bytes.all { it == 0.toByte() })
    }

    @Test
    fun providerCloseFailureDoesNotHideTheOriginalReadFailure() = runTest {
        val expected = IOException("Read failed")
        val cleanup = IOException("Provider close failed")

        val failure = runCatching {
            ClosingInput(cleanup).readOwnedResult<OwnedResult> { throw expected }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertSame(cleanup, failure!!.suppressed.single())
    }

    @Test
    fun successfulReadTransfersTheOpenResultToTheCallerAfterClosingInput() = runTest {
        val input = ClosingInput()
        val result = OwnedResult()

        val received = input.readOwnedResult { result }

        assertSame(result, received)
        assertEquals(1, input.closes)
        assertEquals(0, received.closes)
        assertArrayEquals(byteArrayOf(1, 2, 3), received.bytes)
        received.close()
        assertTrue(received.bytes.all { it == 0.toByte() })
    }

    private class ClosingInput(private val failure: IOException? = null) : ByteArrayInputStream(ByteArray(0)) {
        var closes = 0
        override fun close() {
            closes++
            failure?.let { throw it }
        }
    }

    private class OwnedResult(private val failure: IOException? = null) : AutoCloseable {
        val bytes = byteArrayOf(1, 2, 3)
        var closes = 0
        override fun close() {
            closes++
            bytes.fill(0)
            failure?.let { throw it }
        }
    }
}
