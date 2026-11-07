package com.yanjiyu.terminalspike.terminal.view

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Names are generated locally; provider names can never become paths or shell syntax. */
internal fun terminalAttachmentFileName(mimeType: String, displayName: String?): String {
    val extension = displayName?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}")) }
        ?: when (mimeType.substringBefore(';').trim().lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "application/pdf" -> "pdf"
            else -> "bin"
        }
    return "${UUID.randomUUID()}.$extension"
}

// Share a small IO worker pool without limiting the number of selected files.
private val attachmentTransferDispatcher = Dispatchers.IO.limitedParallelism(4)

/** Transfers run concurrently; paths are inserted in the user's original selection order. */
internal suspend fun transferTerminalAttachments(
    sources: List<TerminalImagePasteSource>,
    transfer: suspend (String, InputStream) -> String,
    insert: (String) -> Boolean,
): Int = coroutineScope {
    val uploads = sources.map { source ->
        // Enter the finally block even if cancelled before an IO worker starts.
        async(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(attachmentTransferDispatcher) {
                    val context = currentCoroutineContext()
                    val name = terminalAttachmentFileName(source.mimeType, source.displayName())
                    val input = requireNotNull(source.open()) { "The selected file is no longer available." }
                    input.use {
                        val cancellable = object : FilterInputStream(it) {
                            override fun read(): Int {
                                context.ensureActive()
                                return `in`.read()
                            }

                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                context.ensureActive()
                                return `in`.read(bytes, offset, length)
                            }
                        }
                        transfer(name, cancellable)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                runCatching(source.onFinished)
            }
        }
    }
    var inserted = 0
    uploads.forEach { upload ->
        val path = upload.await()
        if (path != null && insert(path)) inserted++
    }
    inserted
}

/** Same streaming path for local files, with partial-file cleanup on failure. */
internal fun storeTerminalAttachment(directory: File, fileName: String, source: InputStream): String {
    require(fileName.matches(Regex("[a-f0-9-]{36}\\.[a-z0-9][a-z0-9_-]{0,31}")))
    check(directory.mkdirs() || directory.isDirectory) { "Could not create the attachment directory." }
    val destination = File(directory, fileName)
    try {
        destination.outputStream().use { output -> source.copyTo(output) }
        return destination.absolutePath
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}
