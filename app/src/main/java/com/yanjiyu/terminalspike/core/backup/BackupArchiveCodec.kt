package com.yanjiyu.terminalspike.core.backup

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A complete authenticated archive together with its parsed, caller-owned payload. */
class BackupArchiveReadResult(
    val header: BackupEnvelopeHeader,
    val payload: BackupPayloadReadResult,
) : AutoCloseable {
    override fun close() = payload.snapshot.close()
}

/** Stable integration failures that are not wire-format or cryptographic failures. */
sealed class BackupArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PayloadProducer(cause: Throwable) : BackupArchiveException(
        "The backup payload could not be produced.",
        cause,
    )

    class Staging(cause: Throwable) : BackupArchiveException(
        "The authenticated backup could not be staged safely.",
        cause,
    )
}

/**
 * Joins the deterministic payload codec to the versioned AES-GCM envelope.
 *
 * Export uses a bounded pipe, so secret-bearing plaintext is never written to a temporary file or
 * retained as one complete byte array. Import must authenticate the whole outer envelope before it
 * parses any record. Its temporary staging file is therefore encrypted again with a one-use random
 * in-memory key and is deleted on every exit.
 *
 * These blocking methods belong on an IO dispatcher. Neither method closes caller-owned streams.
 */
class BackupArchiveCodec(
    private val stagingFactory: BackupStagingAreaFactory,
    private val envelopeCrypto: BackupEnvelopeCrypto = BackupEnvelopeCrypto(),
    private val payloadCodec: BackupPayloadCodec = BackupPayloadCodec(),
) {
    /**
     * Writes exactly one archive and consumes both [passphrase] and every portable secret in
     * [snapshot], including validation, provider-write, and cancellation failures.
     */
    fun writeAndWipeSecrets(
        snapshot: BackupPayloadSnapshot,
        output: OutputStream,
        passphrase: CharArray,
        metadata: BackupEnvelopeMetadata,
        kdfIterations: Int,
    ): BackupEnvelopeHeader {
        var pipeInput: PipedInputStream? = null
        var producer: Thread? = null
        val producerFailure = AtomicReference<Throwable?>(null)
        var result: BackupEnvelopeHeader? = null
        var failure: Throwable? = null
        try {
            require(metadata.mode == snapshot.mode) { "Envelope and payload backup modes must match." }
            require(metadata.payloadSchemaVersion == BackupPayloadFormat.SCHEMA_VERSION) {
                "Envelope and payload schema versions must match."
            }
            val payloadLength = payloadCodec.encodedSize(snapshot)
            pipeInput = PipedInputStream(PIPE_BUFFER_BYTES)
            val pipeOutput = PipedOutputStream(pipeInput)
            producer = Thread(
                {
                    try {
                        pipeOutput.use { payloadOutput ->
                            val written = payloadCodec.writeAndWipeSecrets(snapshot, payloadOutput)
                            check(written == payloadLength) { "Backup payload size changed during export." }
                        }
                    } catch (error: Throwable) {
                        producerFailure.compareAndSet(null, error)
                        runCatching { pipeOutput.close() }
                    }
                },
                PRODUCER_THREAD_NAME,
            ).apply {
                isDaemon = true
                start()
            }

            result = envelopeCrypto.encryptAndWipePassphrase(
                payload = pipeInput,
                payloadLength = payloadLength,
                output = output,
                passphrase = passphrase,
                metadata = metadata,
                kdfIterations = kdfIterations,
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            passphrase.fill('\u0000')
            runCatching { pipeInput?.close() }.exceptionOrNull()?.let { closeFailure ->
                failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
            }
            producer?.let { thread ->
                var interrupted: InterruptedException? = null
                while (thread.isAlive) {
                    try {
                        thread.join()
                    } catch (error: InterruptedException) {
                        interrupted = interrupted ?: error
                        thread.interrupt()
                    }
                }
                interrupted?.let { interruption ->
                    Thread.currentThread().interrupt()
                    val wrapped = InterruptedIOException("Interrupted while finishing backup export.").apply {
                        initCause(interruption)
                    }
                    failure?.addSuppressed(wrapped) ?: run { failure = wrapped }
                }
            }
            // The producer is the only owner allowed to read portable secrets. Wipe again only
            // after it has stopped, covering validation failures before the producer was created.
            snapshot.close()
            producerFailure.get()?.let { producerError ->
                if (failure == null) {
                    failure = BackupArchiveException.PayloadProducer(producerError)
                } else if (producerError !== failure) {
                    failure.addSuppressed(BackupArchiveException.PayloadProducer(producerError))
                }
            }
        }
        failure?.let { throw it }
        return checkNotNull(result)
    }

    /** Reads only bounded, non-secret envelope metadata. The caller may close [input] immediately. */
    fun inspect(input: InputStream): BackupEnvelopeHeader = envelopeCrypto.open(input).header

    /**
     * Authenticates, decrypts, and parses a stream without relying on seeking, a path, or
     * `InputStream.available()`. The returned result owns portable secrets and must be closed.
     */
    fun readAndWipePassphrase(
        input: InputStream,
        passphrase: CharArray,
    ): BackupArchiveReadResult {
        var staging: BackupStagingArea? = null
        try {
            val reader = envelopeCrypto.open(input)
            staging = try {
                stagingFactory.create()
            } catch (error: Throwable) {
                throw BackupArchiveException.Staging(error)
            }
            try {
                staging.write { stagingOutput ->
                    reader.decryptToStagingAndWipePassphrase(stagingOutput, passphrase)
                }
            } catch (error: BackupEnvelopeException) {
                throw error
            } catch (error: Throwable) {
                throw BackupArchiveException.Staging(error)
            }
            val payload = try {
                staging.openInput().use { authenticatedPlaintext ->
                    payloadCodec.read(authenticatedPlaintext, reader.header.mode)
                }
            } catch (error: BackupPayloadException) {
                throw error
            } catch (error: Throwable) {
                throw BackupArchiveException.Staging(error)
            }
            return BackupArchiveReadResult(reader.header, payload)
        } finally {
            passphrase.fill('\u0000')
            staging?.close()
        }
    }

    companion object {
        private const val PIPE_BUFFER_BYTES = 64 * 1024
        private const val PRODUCER_THREAD_NAME = "terminal-spike-backup-payload"
    }
}

fun interface BackupStagingAreaFactory {
    fun create(): BackupStagingArea
}

interface BackupStagingArea : Closeable {
    /** Invokes [writer] exactly once and seals the encrypted staging file only on success. */
    fun write(writer: (OutputStream) -> Unit)

    /** Opens authenticated plaintext only after [write] completed successfully. */
    fun openInput(): InputStream
}

/**
 * Private-file staging for authenticated import. Only secondary AES-GCM ciphertext reaches disk;
 * the random key and nonce live for this handle's bounded lifetime and are wiped on close.
 */
class EncryptedFileBackupStagingFactory(
    private val directory: File,
) : BackupStagingAreaFactory {
    override fun create(): BackupStagingArea = EncryptedFileBackupStagingArea(directory)
}

private class EncryptedFileBackupStagingArea(
    directory: File,
) : BackupStagingArea {
    private val keyBytes = ByteArray(STAGING_KEY_BYTES).also(SECURE_RANDOM::nextBytes)
    private val nonce = ByteArray(STAGING_NONCE_BYTES).also(SECURE_RANDOM::nextBytes)
    private val file: File
    private var state = State.NEW

    init {
        require(directory.isDirectory || directory.mkdirs()) { "Backup staging directory is unavailable." }
        file = File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, directory)
        val privatePermissionsApplied = file.setReadable(false, false) &&
            file.setWritable(false, false) &&
            file.setExecutable(false, false) &&
            file.setReadable(true, true) &&
            file.setWritable(true, true)
        if (!privatePermissionsApplied) {
            keyBytes.fill(0)
            nonce.fill(0)
            file.delete()
            throw IOException("Could not restrict backup staging file permissions.")
        }
    }

    override fun write(writer: (OutputStream) -> Unit) {
        check(state == State.NEW) { "Backup staging output is single-use." }
        state = State.WRITING
        try {
            val cipher = newCipher(Cipher.ENCRYPT_MODE)
            CipherOutputStream(FileOutputStream(file, false), cipher).use(writer)
            state = State.SEALED
        } catch (error: Throwable) {
            state = State.FAILED
            throw error
        }
    }

    override fun openInput(): InputStream {
        check(state == State.SEALED) { "Backup staging input is not authenticated and sealed." }
        val cipher = newCipher(Cipher.DECRYPT_MODE)
        return object : FilterInputStream(CipherInputStream(FileInputStream(file), cipher)) {}
    }

    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        keyBytes.fill(0)
        nonce.fill(0)
        if (file.exists() && !file.delete()) {
            // The file contains only one-use AES-GCM ciphertext. Truncate it before a best-effort
            // second deletion so even an unusual provider/filesystem failure retains no archive.
            runCatching { FileOutputStream(file, false).use { } }
            file.delete()
        }
    }

    private fun newCipher(mode: Int): Cipher = try {
        Cipher.getInstance(STAGING_TRANSFORMATION).apply {
            init(mode, SecretKeySpec(keyBytes, STAGING_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(STAGING_AAD)
        }
    } catch (error: GeneralSecurityException) {
        throw IOException("Private backup staging encryption is unavailable.", error)
    }

    private enum class State {
        NEW,
        WRITING,
        SEALED,
        FAILED,
        CLOSED,
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private val STAGING_AAD = "TerminalSpikeBackupImportStagingV1".toByteArray(Charsets.US_ASCII)
        private const val STAGING_PREFIX = ".terminal-spike-backup-stage-"
        private const val STAGING_SUFFIX = ".bin"
        private const val STAGING_ALGORITHM = "AES"
        private const val STAGING_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val STAGING_KEY_BYTES = 32
        private const val STAGING_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
