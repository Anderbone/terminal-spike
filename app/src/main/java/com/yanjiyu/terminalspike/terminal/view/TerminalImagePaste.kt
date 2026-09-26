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

/** One selected media file or pasted image opened only for the bounded upload. */
data class TerminalImagePasteSource(
    val mimeType: String,
    val open: () -> InputStream?,
    val onFinished: () -> Unit = {},
)

fun interface TerminalImageContentCallback {
    /** Returns true only when ownership of [request] and its permission grant was accepted. */
    fun onImageContent(request: TerminalImageContentRequest): Boolean
}

internal const val MAX_PASTED_IMAGE_BYTES: Long = 100L * 1024L * 1024L

/** Supported clipboard-image formats, kept narrower than the image MIME wildcard. */
internal fun pastedImageExtension(mimeType: String): String? = when (
    mimeType.substringBefore(';').trim().lowercase()
) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> null
}

/** Videos are uploaded unchanged and pasted as remote file paths. */
internal fun selectedMediaExtension(mimeType: String): String? = pastedImageExtension(mimeType) ?: when (
    mimeType.substringBefore(';').trim().lowercase()
) {
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    "video/x-matroska" -> "mkv"
    "video/3gpp" -> "3gp"
    "video/3gpp2" -> "3g2"
    "video/mpeg" -> "mpeg"
    "video/x-msvideo" -> "avi"
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
    "Selected media exceeds the $maximumBytes-byte limit.",
)
