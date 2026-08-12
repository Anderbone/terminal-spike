package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveCodecTest {
    @Test
    fun standardArchiveRoundTripsThroughNonSeekableStreams() = withArchiveCodec { codec, _ ->
        val snapshot = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            snippets = listOf(testSnippet()),
        )
        val passphrase = "correct horse battery staple".toCharArray()
        val output = ByteArrayOutputStream()

        val writtenHeader = codec.writeAndWipeSecrets(
            snapshot = snapshot,
            output = output,
            passphrase = passphrase,
            metadata = metadata(BackupMode.STANDARD),
            kdfIterations = BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )

        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(BackupMode.STANDARD, writtenHeader.mode)
        val input = NonSeekableInputStream(output.toByteArray())
        val unlockPassphrase = "correct horse battery staple".toCharArray()
        codec.readAndWipePassphrase(input, unlockPassphrase).use { restored ->
            assertEquals(BackupMode.STANDARD, restored.header.mode)
            assertEquals(listOf("Deploy"), restored.payload.snapshot.snippets.map { it.name })
            assertTrue(restored.payload.compatibility.incompatibleRecords.isEmpty())
        }
        assertTrue(unlockPassphrase.all { it == '\u0000' })
    }

    @Test
    fun fullArchiveRoundTripConsumesSourceAndReturnedSecrets() = withArchiveCodec { codec, _ ->
        val originalBytes = "portable password".toByteArray()
        val secret = PortableBackupSecret.copyAndWipe(originalBytes)
        assertTrue(originalBytes.all { it == 0.toByte() })
        val snapshot = BackupPayloadSnapshot(
            mode = BackupMode.FULL,
            credentials = listOf(
                BackupCredentialRecord(
                    metadata = SshCredential(
                        id = CREDENTIAL_ID,
                        displayName = "Production prompt",
                        authentication = SshAuthentication.Password(SECRET_ID),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                    portableSecret = secret,
                ),
            ),
        )
        val archive = ByteArrayOutputStream()

        codec.writeAndWipeSecrets(
            snapshot = snapshot,
            output = archive,
            passphrase = "full backup passphrase".toCharArray(),
            metadata = metadata(BackupMode.FULL),
            kdfIterations = BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )

        assertTrue(secret.isWiped)
        codec.readAndWipePassphrase(
            ByteArrayInputStream(archive.toByteArray()),
            "full backup passphrase".toCharArray(),
        ).use { restored ->
            val restoredSecret = requireNotNull(
                restored.payload.snapshot.credentials.single().portableSecret,
            ) { "Full backup omitted its portable secret." }
            restoredSecret.withBytes { bytes ->
                assertArrayEquals("portable password".toByteArray(), bytes)
            }
            assertFalse(restoredSecret.isWiped)
        }
    }

    @Test
    fun wrongPassphraseFailsBeforePayloadParsingAndDeletesStaging() = withArchiveCodec { codec, directory ->
        val archive = exportStandard(codec)
        val wrong = "wrong backup passphrase".toCharArray()

        expectThrows<BackupEnvelopeException.UnlockFailed> {
            codec.readAndWipePassphrase(ByteArrayInputStream(archive), wrong)
        }

        assertTrue(wrong.all { it == '\u0000' })
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun tamperedAndTruncatedArchivesFailWithoutRetainingStaging() = withArchiveCodec { codec, directory ->
        val original = exportStandard(codec)
        val tampered = original.copyOf().also { it[it.lastIndex] = (it.last() xor 0x01) }
        expectThrows<BackupEnvelopeException.UnlockFailed> {
            codec.readAndWipePassphrase(
                ByteArrayInputStream(tampered),
                "archive passphrase".toCharArray(),
            )
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        expectThrows<BackupEnvelopeException.Truncated> {
            codec.readAndWipePassphrase(
                ByteArrayInputStream(original.copyOf(original.size - 1)),
                "archive passphrase".toCharArray(),
            )
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun inspectReturnsBoundedMetadataWithoutPassphrase() = withArchiveCodec { codec, _ ->
        val header = codec.inspect(NonSeekableInputStream(exportStandard(codec)))

        assertEquals(BackupMode.STANDARD, header.mode)
        assertEquals("1.2.3", header.appVersion.name)
        assertEquals(1_725_000_000_000L, header.createdAtEpochMillis)
    }

    @Test
    fun modeValidationFailureStillWipesPassphraseAndPortableSecret() = withArchiveCodec { codec, _ ->
        val secret = PortableBackupSecret.copyAndWipe("secret".toByteArray())
        val snapshot = BackupPayloadSnapshot(
            mode = BackupMode.FULL,
            credentials = listOf(
                BackupCredentialRecord(
                    SshCredential(
                        id = CREDENTIAL_ID,
                        displayName = "Credential",
                        authentication = SshAuthentication.Password(SECRET_ID),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 1,
                    ),
                    secret,
                ),
            ),
        )
        val passphrase = "do not retain this".toCharArray()

        expectThrows<IllegalArgumentException> {
            codec.writeAndWipeSecrets(
                snapshot,
                ByteArrayOutputStream(),
                passphrase,
                metadata(BackupMode.STANDARD),
                BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            )
        }

        assertTrue(passphrase.all { it == '\u0000' })
        assertTrue(secret.isWiped)
    }

    @Test
    fun privateStagingFileContainsOnlyCiphertextAndIsDeleted() {
        val directory = Files.createTempDirectory("backup-stage-test")
        try {
            val area = EncryptedFileBackupStagingFactory(directory.toFile()).create()
            val plaintext = "private-key-material-that-must-not-reach-disk".toByteArray()
            area.write { it.write(plaintext) }
            val stagedFile = directory.toFile().listFiles().orEmpty().single()

            val onDisk = stagedFile.readBytes()
            assertFalse(onDisk.asList().windowed(plaintext.size).any { it.toByteArray().contentEquals(plaintext) })
            area.openInput().use { restored ->
                assertArrayEquals(plaintext, restored.readBytes())
            }

            area.close()
            assertFalse(stagedFile.exists())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun exportStandard(codec: BackupArchiveCodec): ByteArray = ByteArrayOutputStream().also { output ->
        codec.writeAndWipeSecrets(
            snapshot = BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                snippets = listOf(testSnippet()),
            ),
            output = output,
            passphrase = "archive passphrase".toCharArray(),
            metadata = metadata(BackupMode.STANDARD),
            kdfIterations = BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )
    }.toByteArray()

    private fun metadata(mode: BackupMode) = BackupEnvelopeMetadata(
        mode = mode,
        createdAtEpochMillis = 1_725_000_000_000L,
        appVersion = BackupAppVersion(12, "1.2.3"),
        payloadSchemaVersion = BackupPayloadFormat.SCHEMA_VERSION,
        compatibility = BackupCompatibility(
            minimumSdk = 26,
            targetSdk = 37,
            capabilityBits = 0,
        ),
    )

    private fun testSnippet() = com.yanjiyu.terminalspike.core.model.Snippet(
        id = SNIPPET_ID,
        name = "Deploy",
        command = "printf done",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private inline fun withArchiveCodec(block: (BackupArchiveCodec, File) -> Unit) {
        val directory = Files.createTempDirectory("terminal-spike-archive-test").toFile()
        try {
            block(
                BackupArchiveCodec(EncryptedFileBackupStagingFactory(directory)),
                directory,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}.")
    }

    private class NonSeekableInputStream(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        override fun markSupported(): Boolean = false

        override fun mark(readlimit: Int) = Unit

        override fun reset() = throw IOException("reset is unsupported")

        override fun available(): Int = 0
    }

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()

    private companion object {
        val CREDENTIAL_ID: String = UUID.fromString("10000000-0000-4000-8000-000000000001").toString()
        val SECRET_ID: String = UUID.fromString("20000000-0000-4000-8000-000000000002").toString()
        val SNIPPET_ID: String = UUID.fromString("30000000-0000-4000-8000-000000000003").toString()
    }
}
