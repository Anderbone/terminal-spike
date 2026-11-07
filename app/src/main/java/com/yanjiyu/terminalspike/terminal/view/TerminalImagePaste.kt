package com.yanjiyu.terminalspike.terminal.view

import android.net.Uri
import java.io.InputStream

/** One temporary read grant delivered by an IME rich-content paste. */
data class TerminalImageContentRequest(
    val uri: Uri,
    val mimeType: String,
    val releasePermission: () -> Unit = {},
)

/** A user-selected file. Its bytes and metadata are opened only during transfer. */
data class TerminalImagePasteSource(
    val mimeType: String,
    val open: () -> InputStream?,
    val onFinished: () -> Unit = {},
    val displayName: () -> String? = { null },
)

fun interface TerminalImageContentCallback {
    /** Returns true only when ownership of [request] and its permission grant was accepted. */
    fun onImageContent(request: TerminalImageContentRequest): Boolean
}

/** Shares temporary read-grant ownership across both input modes for any file type. */
internal fun receiveTerminalImageContent(
    inputContentInfo: android.view.inputmethod.InputContentInfo,
    flags: Int,
    callback: TerminalImageContentCallback,
): Boolean {
    val description = inputContentInfo.description
    val mimeType = (0 until description.mimeTypeCount)
        .map(description::getMimeType)
        .firstOrNull()
        ?: "application/octet-stream"
    val ownsPermission = flags and android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
    if (ownsPermission && runCatching { inputContentInfo.requestPermission() }.isFailure) return false
    var permissionReleased = false
    val releasePermission = {
        if (!permissionReleased) {
            permissionReleased = true
            if (ownsPermission) runCatching { inputContentInfo.releasePermission() }
        }
    }
    val accepted = runCatching {
        callback.onImageContent(
            TerminalImageContentRequest(
                uri = inputContentInfo.contentUri,
                mimeType = mimeType,
                releasePermission = releasePermission,
            ),
        )
    }.getOrDefault(false)
    if (!accepted) releasePermission()
    return accepted
}
