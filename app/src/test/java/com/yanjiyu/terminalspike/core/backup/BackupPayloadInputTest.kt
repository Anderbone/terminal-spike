package com.yanjiyu.terminalspike.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadInputTest {
    @Test
    fun truncatedRecordWipesItsPartiallyReadPlaintext() {
        assertFailedReadWipes(EndOfInput.EOF)
    }

    @Test
    fun providerFailureWipesItsPartiallyReadPlaintextAndPreservesTheException() {
        assertFailedReadWipes(EndOfInput.IO_FAILURE)
    }

    @Test
    fun zeroLengthReadFollowedByEofStillWipesPartiallyReadPlaintext() {
        assertFailedReadWipes(EndOfInput.ZERO_THEN_EOF)
    }

    @Test
    fun recordThatCannotFitTheRemainingBudgetIsRejectedBeforeItsContentsAreRead() {
        val input = CapturingInput(EndOfInput.EOF)
        val failure = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            BackupPayloadCodec(maximumPayloadBytes = RECORD_BYTES + 26L).read(input, BackupMode.FULL)
        }

        assertEquals(BackupPayloadLimit.TOTAL_BYTES, failure.limit)
        assertNull(input.recordDestination)
    }

    private fun assertFailedReadWipes(end: EndOfInput) {
        val input = CapturingInput(end)
        val failure = assertThrows(Exception::class.java) {
            BackupPayloadCodec().read(input, BackupMode.FULL)
        }
        if (end == EndOfInput.IO_FAILURE) {
            assertSame(input.providerFailure, failure)
        } else {
            assertTrue(failure is BackupPayloadException.Truncated)
            assertEquals(BackupPayloadSection.RECORD_BODY, (failure as BackupPayloadException.Truncated).section)
        }
        assertNotNull(input.recordDestination)
        assertTrue("Abandoned plaintext must be wiped", input.recordDestination!!.all { it == 0.toByte() })
    }

    private enum class EndOfInput { EOF, IO_FAILURE, ZERO_THEN_EOF }

    /** Retains only the array lent to the provider, without accessing decoder internals. */
    private class CapturingInput(private val end: EndOfInput) : InputStream() {
        private val delegate = ByteArrayInputStream(ByteArrayOutputStream().apply {
            write(BackupPayloadWire.MAGIC_V1)
            write(byteArrayOf(0, BackupPayloadWire.COLLECTION_HOSTS.toByte()))
            writeU32(4)
            writeU32(0)
            write(byteArrayOf(0, BackupPayloadWire.COLLECTION_CREDENTIALS.toByte()))
            writeU32(4L + 6L + RECORD_BYTES)
            writeU32(1)
            write(byteArrayOf(0, BackupPayloadWire.RECORD_PRIMARY.toByte()))
            writeU32(RECORD_BYTES.toLong())
            write("partial-private-record".encodeToByteArray())
        }.toByteArray())
        val providerFailure = IOException("Injected provider failure")
        var recordDestination: ByteArray? = null
            private set

        override fun read(): Int = delegate.read()

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == RECORD_BYTES) recordDestination = destination
            if (delegate.available() == 0) {
                return when (end) {
                    EndOfInput.EOF -> -1
                    EndOfInput.IO_FAILURE -> throw providerFailure
                    EndOfInput.ZERO_THEN_EOF -> 0
                }
            }
            return delegate.read(destination, offset, length)
        }
    }

    private companion object {
        const val RECORD_BYTES = 64
    }
}
