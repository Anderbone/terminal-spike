package com.yanjiyu.terminalspike.core.data.migration

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.settings.UserSettingsCodec
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface LegacyUserSettingsReadResult {
    data object Missing : LegacyUserSettingsReadResult

    data class Loaded(
        val settings: UserSettings,
        val sourceVersion: Int,
        val sourceDigestSha256: String,
    ) : LegacyUserSettingsReadResult

    data class Blocked(val failure: LegacyUserSettingsFailure) : LegacyUserSettingsReadResult
}

internal enum class LegacyUserSettingsFailure {
    KEY_UNAVAILABLE,
    CORRUPT,
    UNSUPPORTED_VERSION,
    IO_UNAVAILABLE,
}

/**
 * Existing-key-only reader used by the one-time Room migration. It never deletes, resets, saves,
 * or creates a Keystore entry when a legacy source cannot be opened.
 */
internal class LegacyUserSettingsReader(
    context: Context,
    fileName: String = SETTINGS_FILE,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val file = AtomicFile(context.applicationContext.filesDir.resolve(fileName))

    @Synchronized
    fun read(): LegacyUserSettingsReadResult {
        val backup = file.baseFile.resolveSibling("${file.baseFile.name}.bak")
        if (!file.baseFile.isFile && !backup.isFile) return LegacyUserSettingsReadResult.Missing

        val source = try {
            file.openRead().use { input ->
                val size = input.channel.size()
                require(size in 1..MAX_ENCRYPTED_BYTES.toLong()) { "Invalid legacy settings size." }
                input.readBytes().also { bytes -> require(bytes.size.toLong() == size) }
            }
        } catch (_: FileNotFoundException) {
            return LegacyUserSettingsReadResult.Missing
        } catch (_: IOException) {
            return LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.IO_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            return LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
        }

        val key = try {
            existingKey()
        } catch (_: GeneralSecurityException) {
            null
        }
        if (key == null) {
            source.fill(0)
            return LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.KEY_UNAVAILABLE)
        }

        return try {
            val envelope = readEnvelope(source)
            val cleartext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
                updateAAD(ASSOCIATED_DATA)
                doFinal(envelope.ciphertext)
            }
            try {
                val payload = LegacyUserSettingsPayloadParser.parse(cleartext)
                LegacyUserSettingsReadResult.Loaded(
                    settings = payload.settings,
                    sourceVersion = payload.version,
                    sourceDigestSha256 = source.sha256Hex(),
                )
            } finally {
                cleartext.fill(0)
            }
        } catch (_: UnsupportedLegacySettingsVersionException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.UNSUPPORTED_VERSION)
        } catch (_: KeyPermanentlyInvalidatedException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.KEY_UNAVAILABLE)
        } catch (_: AEADBadTagException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
        } catch (_: GeneralSecurityException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.KEY_UNAVAILABLE)
        } catch (_: EOFException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
        } catch (_: IOException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
        } catch (_: IllegalArgumentException) {
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
        } finally {
            source.fill(0)
        }
    }

    private fun existingKey(): SecretKey? = KeyStore.getInstance(KEYSTORE_PROVIDER).run {
        load(null)
        getKey(keyAlias, null) as? SecretKey
    }

    private fun readEnvelope(source: ByteArray): EncryptedEnvelope =
        DataInputStream(ByteArrayInputStream(source)).use { data ->
            require(data.readInt() == ENVELOPE_MAGIC) { "Invalid legacy settings header." }
            val version = data.readInt()
            if (version != ENVELOPE_VERSION) throw UnsupportedLegacySettingsVersionException(version)
            val ivLength = data.readInt()
            require(ivLength in 12..32) { "Invalid legacy settings IV." }
            val iv = ByteArray(ivLength).also(data::readFully)
            val ciphertextLength = data.readInt()
            require(ciphertextLength in 16..MAX_ENCRYPTED_BYTES) { "Invalid legacy ciphertext size." }
            val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
            require(data.read() == -1) { "Trailing legacy settings data." }
            EncryptedEnvelope(iv, ciphertext)
        }

    private data class EncryptedEnvelope(val iv: ByteArray, val ciphertext: ByteArray)

    private companion object {
        const val SETTINGS_FILE = "secure_user_settings.bin"
        const val KEY_ALIAS = "terminal_spike_user_settings_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val ENVELOPE_MAGIC = 0x54534531
        const val ENVELOPE_VERSION = 1
        const val MAX_ENCRYPTED_BYTES = 1024 * 1024
        val ASSOCIATED_DATA = "terminal-spike-user-settings-v1".toByteArray(Charsets.UTF_8)
    }
}

internal data class LegacyUserSettingsPayload(
    val version: Int,
    val settings: UserSettings,
)

internal object LegacyUserSettingsPayloadParser {
    private const val PAYLOAD_MAGIC = 0x54535031
    private const val MIN_VERSION = 1
    private const val MAX_VERSION = 3

    fun parse(bytes: ByteArray): LegacyUserSettingsPayload {
        val prefix = DataInputStream(ByteArrayInputStream(bytes))
        require(prefix.readInt() == PAYLOAD_MAGIC) { "Invalid legacy settings payload header." }
        val version = prefix.readInt()
        if (version !in MIN_VERSION..MAX_VERSION) {
            throw UnsupportedLegacySettingsVersionException(version)
        }
        val input = ByteArrayInputStream(bytes)
        val settings = UserSettingsCodec.read(input)
        require(input.available() == 0) { "Trailing legacy settings payload data." }
        return LegacyUserSettingsPayload(version, settings)
    }
}

internal class UnsupportedLegacySettingsVersionException(
    val sourceVersion: Int,
) : IllegalArgumentException("Unsupported legacy settings version $sourceVersion.")

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
