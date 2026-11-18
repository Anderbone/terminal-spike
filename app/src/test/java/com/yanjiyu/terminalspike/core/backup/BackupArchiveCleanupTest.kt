package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveCleanupTest {
    private var decoded: BackupPayloadReadResult? = null

    @After
    fun closeCapturedSnapshot() {
        decoded?.snapshot?.close()
    }

    @Test
    fun inputCloseFailureWipesTheDecodedSnapshotBeforeOwnershipCanTransfer() {
        val expected = IOException("Injected input close failure")
        val area = ClosingStaging(inputCloseFailure = expected)
        val passphrase = PASSPHRASE.toCharArray()

        val failure = assertThrows(BackupArchiveException.Staging::class.java) {
            codec(area).readAndWipePassphrase(ByteArrayInputStream(archive()), passphrase)
        }

        assertDecodedWiped()
        assertSame(expected, failure.cause)
        assertEquals(1, area.closes)
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun stagingCloseFailureWipesTheDecodedSnapshotBeforeOwnershipCanTransfer() {
        val expected = IOException("Injected staging close failure")
        val area = ClosingStaging(areaCloseFailure = expected)
        val passphrase = PASSPHRASE.toCharArray()

        val failure = assertThrows(Exception::class.java) {
            codec(area).readAndWipePassphrase(ByteArrayInputStream(archive()), passphrase)
        }

        assertDecodedWiped()
        assertTrue(failure is BackupArchiveException.Staging)
        assertSame(expected, failure.cause)
        assertEquals(1, area.closes)
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun stagingCloseFailureDoesNotHideAnAuthenticationFailure() {
        val expected = IOException("Injected staging close failure")
        val area = ClosingStaging(areaCloseFailure = expected)
        val passphrase = "wrong passphrase".toCharArray()

        val failure = assertThrows(BackupEnvelopeException.UnlockFailed::class.java) {
            codec(area).readAndWipePassphrase(ByteArrayInputStream(archive()), passphrase)
        }

        assertNull(decoded)
        assertSame(expected, failure.suppressed.single())
        assertEquals(1, area.closes)
        assertTrue(passphrase.all { it == '\u0000' })
    }

    private fun assertDecodedWiped() {
        assertNotNull(decoded)
        val secret = decoded!!.snapshot.credentials.single().portableSecret!!
        assertTrue("Decoded secrets have no caller when resource cleanup fails", secret.isWiped)
    }

    private fun codec(area: ClosingStaging): BackupArchiveCodec {
        val payloads = BackupPayloadCodec()
        return BackupArchiveCodec(
            BackupStagingAreaFactory { area }, BackupEnvelopeCrypto(), payloads,
        ) { input, mode -> payloads.read(input, mode).also { decoded = it } }
    }

    private fun archive(): ByteArray {
        val snapshot = BackupPayloadSnapshot(
            mode = BackupMode.FULL,
            credentials = listOf(
                BackupCredentialRecord(
                    metadata = SshCredential(
                        id = "10000000-0000-4000-8000-000000000001",
                        displayName = "Test credential",
                        authentication = SshAuthentication.Password("20000000-0000-4000-8000-000000000002"),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 1,
                    ),
                    portableSecret = PortableBackupSecret.copyAndWipe("synthetic-test-secret".encodeToByteArray()),
                ),
            ),
        )
        val output = ByteArrayOutputStream()
        BackupArchiveCodec(BackupStagingAreaFactory { error("Export does not stage imports") })
            .writeAndWipeSecrets(
                snapshot, output, PASSPHRASE.toCharArray(),
                BackupEnvelopeMetadata(
                    mode = BackupMode.FULL,
                    createdAtEpochMillis = 1,
                    appVersion = BackupAppVersion(1, "test"),
                    payloadSchemaVersion = 1,
                    compatibility = BackupCompatibility(26, 37, 0),
                ),
                BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            )
        return output.toByteArray()
    }

    /** In-memory staging fixture; envelope authentication and payload decoding remain real. */
    private class ClosingStaging(
        private val inputCloseFailure: IOException? = null,
        private val areaCloseFailure: IOException? = null,
    ) : BackupStagingArea {
        private var plaintext = ByteArray(0)
        var closes = 0
            private set

        override fun write(writer: (OutputStream) -> Unit) {
            val output = ByteArrayOutputStream()
            writer(output)
            plaintext = output.toByteArray()
        }

        override fun openInput(): InputStream = object : ByteArrayInputStream(plaintext) {
            override fun close() {
                super.close()
                inputCloseFailure?.let { throw it }
            }
        }

        override fun close() {
            closes++
            plaintext.fill(0)
            areaCloseFailure?.let { throw it }
        }
    }

    private companion object {
        const val PASSPHRASE = "test archive passphrase"
    }
}
