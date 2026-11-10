package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.repository.InvalidSshPrivateKeyException
import java.io.InputStream

// Private keys have no required extension or provider MIME type. Let the importer
// validate the bounded file contents rather than filtering valid keys in DocumentsUI.
internal fun sshIdentityImportMimeTypes(): Array<String> = arrayOf("*/*")

internal class SensitiveInputTooLargeException : IllegalArgumentException("Selected file is too large.")
internal class SshIdentityReadException(cause: Exception) : Exception("Could not read selected key.", cause)

internal fun readSshIdentityBytes(maximumBytes: Int, openInput: () -> InputStream?): ByteArray = try {
    requireNotNull(openInput()) { "Could not open selected key." }.use { it.readSensitiveBounded(maximumBytes) }
} catch (error: SensitiveInputTooLargeException) {
    throw error
} catch (error: Exception) {
    throw SshIdentityReadException(error)
}

internal fun sshIdentityImportFailureMessage(error: Exception): Int = when (error) {
    is InvalidSshPrivateKeyException -> R.string.notice_key_import_invalid_format
    is SensitiveInputTooLargeException -> R.string.notice_key_import_too_large
    is SshIdentityReadException -> R.string.notice_key_import_read_failed
    else -> R.string.notice_key_import_failed
}
