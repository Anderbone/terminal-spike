package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.repository.InvalidSshPrivateKeyException
import java.io.InputStream
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import androidx.activity.result.contract.ActivityResultContracts
import com.yanjiyu.terminalspike.settings.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val MAX_SSH_PRIVATE_KEY_BYTES = 256 * 1024
internal const val PENDING_IDENTITY_IMPORT_URI = "pending_identity_import_uri"
internal const val PENDING_IDENTITY_RECOVERY_TOKEN = "pending_identity_recovery_token"

internal data class IdentityPickerRequest(val recoveryToken: String?)

/** In-memory ownership survives rotation with the ViewModel, but never survives process death. */
internal class IdentityPickerRequestOwner {
    private var pending: IdentityPickerRequest? = null

    fun begin(recoveryToken: String?): Boolean {
        if (pending != null) return false
        pending = IdentityPickerRequest(recoveryToken)
        return true
    }

    fun take(): IdentityPickerRequest? = pending.also { pending = null }

    fun cancel() { pending = null }
}

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

/** One import task; the callback releases the caller-owned document grant. */
internal fun CoroutineScope.launchSshIdentityImport(
    finished: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> Unit,
): Job = launch(dispatcher) {
    block()
}.also { job ->
    // Completion also runs when cancellation prevents the worker body from starting.
    job.invokeOnCompletion { finished() }
}

internal fun importedSshIdentityLabel(name: String, fallback: String): String {
    val basename = name.substringAfterLast('/')
    val normalized = buildString(basename.length.coerceAtMost(UserSettings.MAX_LABEL_LENGTH)) {
        var index = 0
        while (index < basename.length) {
            val codePoint = basename.codePointAt(index)
            if (!Character.isISOControl(codePoint) &&
                Character.getType(codePoint) != Character.FORMAT.toInt() &&
                codePoint !in 0xD800..0xDFFF
            ) {
                appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }.trim()
    val end = minOf(normalized.length, UserSettings.MAX_LABEL_LENGTH)
    val safeEnd = if (end > 0 && normalized[end - 1].isHighSurrogate()) end - 1 else end
    return normalized.substring(0, safeEnd).takeIf(String::isNotBlank) ?: fallback
}

internal fun InputStream.readSensitiveBounded(
    maximumBytes: Int,
    onBufferWiped: (ByteArray) -> Unit = {},
): ByteArray {
    require(maximumBytes > 0) { "Maximum sensitive input size must be positive." }
    val output = SensitiveByteAccumulator(
        initialCapacity = minOf(maximumBytes, 16 * 1024),
        maximumCapacity = maximumBytes,
        onBufferWiped = onBufferWiped,
    )
    val scratch = ByteArray(minOf(maximumBytes, 8 * 1024))
    try {
        while (true) {
            val count = read(scratch)
            if (count < 0) break
            if (count == 0) continue
            if (output.size > maximumBytes - count) throw SensitiveInputTooLargeException()
            output.append(scratch, count)
        }
        return output.copyValue()
    } finally {
        scratch.fill(0)
        onBufferWiped(scratch)
        output.close()
    }
}

private class SensitiveByteAccumulator(
    initialCapacity: Int,
    private val maximumCapacity: Int,
    private val onBufferWiped: (ByteArray) -> Unit,
) {
    private var storage = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    fun append(source: ByteArray, count: Int) {
        require(count in 0..source.size)
        ensureCapacity(size + count)
        source.copyInto(storage, destinationOffset = size, endIndex = count)
        size += count
    }

    fun copyValue(): ByteArray = storage.copyOf(size)

    fun close() {
        wipe(storage)
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) return
        require(required <= maximumCapacity) { "Selected file is too large." }
        var capacity = storage.size.coerceAtLeast(1)
        while (capacity < required) {
            capacity = minOf(maximumCapacity, maxOf(required, capacity * 2))
        }
        val replacement = ByteArray(capacity)
        storage.copyInto(replacement, endIndex = size)
        wipe(storage)
        storage = replacement
    }

    private fun wipe(buffer: ByteArray) {
        buffer.fill(0)
        onBufferWiped(buffer)
    }
}
