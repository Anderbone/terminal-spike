package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.repository.InvalidSshPrivateKeyException
import java.io.InputStream
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import androidx.activity.result.contract.ActivityResultContracts

internal const val MAX_SSH_PRIVATE_KEY_BYTES = 256 * 1024

internal fun sshIdentityDocumentIntent(context: Context): Intent =
    ActivityResultContracts.OpenDocument().createIntent(context, sshIdentityImportMimeTypes())
        .addCategory(Intent.CATEGORY_OPENABLE)

// Resolve exported system components dynamically: OEMs use different Activity names.
// This is an optional route, never a prerequisite for the independent text importer.
internal fun sshIdentitySystemDocumentIntent(context: Context): Intent? {
    val intent = sshIdentityDocumentIntent(context)
    @Suppress("DEPRECATION")
    val candidates = context.packageManager.queryIntentActivities(intent, 0)
    val activity = candidates.mapNotNull { it.activityInfo }.firstOrNull {
        it.exported && it.enabled && it.applicationInfo.enabled &&
            it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            it.packageName in setOf("com.google.android.documentsui", "com.android.documentsui")
    } ?: return null
    return intent.setComponent(ComponentName(activity.packageName, activity.name))
}

internal fun sshIdentityTextBytes(text: String, maximumBytes: Int = MAX_SSH_PRIVATE_KEY_BYTES): ByteArray {
    // Bound before encoding too; do not trim or otherwise rewrite private-key material.
    if (text.length > maximumBytes) throw SensitiveInputTooLargeException()
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size > maximumBytes) {
        bytes.fill(0)
        throw SensitiveInputTooLargeException()
    }
    return bytes
}

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

internal fun tryLaunchSshIdentityDocument(intent: Intent, launch: (Intent) -> Unit): Boolean = try {
    launch(intent)
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
