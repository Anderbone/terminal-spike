package com.yanjiyu.terminalspike.ui.sftp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class SftpPreviewProvider : FileProvider()

internal fun openSftpPreview(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.sftp-preview", file)
    openSftpDownload(context, uri, file.name)
}

internal fun openSftpDownload(context: Context, uri: Uri, name: String) {
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        throw IllegalStateException("File saved to Downloads. No app can open this file type.", error)
    }
}
