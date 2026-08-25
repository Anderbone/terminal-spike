package com.yanjiyu.terminalspike.terminal.view

import android.net.Uri
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** One temporary read grant delivered by an IME rich-content paste. */
data class TerminalImageContentRequest(
    val uri: Uri,
    val mimeType: String,
    val releasePermission: () -> Unit = {},
)

/** One locally selected or pasted image whose bytes are opened only for the bounded upload. */
data class TerminalImagePasteSource(
    val mimeType: String,
    val open: () -> InputStream?,
    val onFinished: () -> Unit = {},
)

fun interface TerminalImageContentCallback {
    /** Returns true only when ownership of [request] and its permission grant was accepted. */
    fun onImageContent(request: TerminalImageContentRequest): Boolean
}

internal const val MAX_PASTED_IMAGE_BYTES: Long = 20L * 1024L * 1024L

/** Codex CLI's supported clipboard-image formats, kept narrower than the image MIME wildcard. */
internal fun pastedImageExtension(mimeType: String): String? = when (
    mimeType.substringBefore(';').trim().lowercase()
) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> null
}

/**
 * Fails a streaming upload as soon as it exceeds the paste limit. The extra probe byte is never
 * returned to the caller, so a remote destination cannot receive an oversized payload in full.
 */
internal class PastedImageSizeLimitInputStream(
    source: InputStream,
    private val maximumBytes: Long = MAX_PASTED_IMAGE_BYTES,
) : FilterInputStream(source) {
    private var deliveredBytes = 0L

    init {
        require(maximumBytes >= 0L)
    }

    override fun read(): Int {
        if (deliveredBytes == maximumBytes) return verifyEndOfInput()
        return super.read().also { value -> if (value >= 0) deliveredBytes += 1L }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (deliveredBytes == maximumBytes) return verifyEndOfInput()
        val boundedLength = minOf(length.toLong(), maximumBytes - deliveredBytes).toInt()
        return super.read(buffer, offset, boundedLength).also { count ->
            if (count > 0) deliveredBytes += count.toLong()
        }
    }

    private fun verifyEndOfInput(): Int {
        if (super.read() < 0) return -1
        throw PastedImageTooLargeException(maximumBytes)
    }
}

internal class PastedImageTooLargeException(maximumBytes: Long) : IOException(
    "Pasted image exceeds the $maximumBytes-byte limit.",
)
