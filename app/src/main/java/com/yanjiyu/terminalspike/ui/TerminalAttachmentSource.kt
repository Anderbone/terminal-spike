package com.yanjiyu.terminalspike.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.yanjiyu.terminalspike.terminal.view.TerminalImagePasteSource

/** Defer provider queries and reads until the transfer runs off the UI thread. */
internal fun terminalAttachmentSource(
    resolver: ContentResolver,
    uri: Uri,
    mimeType: String = "application/octet-stream",
    onFinished: () -> Unit = {},
): TerminalImagePasteSource = TerminalImagePasteSource(
    mimeType = mimeType,
    open = { resolver.openInputStream(uri) },
    onFinished = onFinished,
    displayName = {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        name?.takeIf { it.substringAfterLast('.', "").isNotEmpty() } ?: runCatching {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
                ?.let { "attachment.$it" }
        }.getOrNull()
    },
)
