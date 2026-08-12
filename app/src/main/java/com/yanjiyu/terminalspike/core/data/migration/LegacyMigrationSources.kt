package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.settings.SshPasswordScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Injectable boundary around the existing-key-only encrypted settings reader. */
internal fun interface LegacyUserSettingsSource {
    fun read(): LegacyUserSettingsReadResult
}

internal class LegacyUserSettingsSourceAdapter(
    private val reader: LegacyUserSettingsReader,
) : LegacyUserSettingsSource {
    override fun read(): LegacyUserSettingsReadResult = reader.read()
}

/** Injectable boundary around the two legacy encrypted-secret sidecar readers. */
internal interface LegacySecretsSource {
    fun readPassword(scope: SshPasswordScope): LegacySecretReadResult

    fun readPrivateKey(identityId: Long): LegacySecretReadResult
}

internal class LegacySecretsSourceAdapter(
    private val readers: LegacySecretReaders,
) : LegacySecretsSource {
    override fun readPassword(scope: SshPasswordScope): LegacySecretReadResult =
        readers.readPassword(scope)

    override fun readPrivateKey(identityId: Long): LegacySecretReadResult =
        readers.readPrivateKey(identityId)
}

internal sealed interface LegacyKnownHostsReadResult {
    data object Missing : LegacyKnownHostsReadResult

    data class Loaded(
        val source: String,
        val sourceDigestSha256: String,
    ) : LegacyKnownHostsReadResult

    data class Blocked(val failure: LegacyKnownHostsFailure) : LegacyKnownHostsReadResult
}

internal enum class LegacyKnownHostsFailure {
    CORRUPT,
    IO_UNAVAILABLE,
}

internal fun interface LegacyKnownHostsSource {
    fun read(): LegacyKnownHostsReadResult
}

/** Bounded, strict-UTF-8 reader. It never creates, rewrites, moves, or deletes the source file. */
internal class FileLegacyKnownHostsSource(
    private val file: File,
) : LegacyKnownHostsSource {
    override fun read(): LegacyKnownHostsReadResult {
        if (!file.exists()) return LegacyKnownHostsReadResult.Missing
        if (!file.isFile) {
            return LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.IO_UNAVAILABLE)
        }

        val bytes = try {
            val declaredSize = file.length()
            if (declaredSize !in 0..MAX_SOURCE_BYTES.toLong()) {
                return LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.CORRUPT)
            }
            file.inputStream().buffered().use { input ->
                val source = input.readBounded(MAX_SOURCE_BYTES)
                if (source == null) {
                    return LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.CORRUPT)
                }
                source
            }
        } catch (_: FileNotFoundException) {
            return LegacyKnownHostsReadResult.Missing
        } catch (_: IOException) {
            return LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.IO_UNAVAILABLE)
        } catch (_: SecurityException) {
            return LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.IO_UNAVAILABLE)
        }

        return try {
            val source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            LegacyKnownHostsReadResult.Loaded(
                source = source,
                sourceDigestSha256 = bytes.sha256Hex(),
            )
        } catch (_: Exception) {
            LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.CORRUPT)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 1024 * 1024
    }
}

/** API-26-compatible bounded read that also handles a file growing after its size check. */
private fun InputStream.readBounded(maximumBytes: Int): ByteArray? {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    var total = 0
    return try {
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maximumBytes - count) return null
            output.write(buffer, 0, count)
            total += count
        }
        output.toByteArray()
    } finally {
        buffer.fill(0)
    }
}

internal fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
