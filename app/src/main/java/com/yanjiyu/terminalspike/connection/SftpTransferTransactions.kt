package com.yanjiyu.terminalspike.connection

import java.io.InputStream
import java.io.OutputStream

internal inline fun writePendingDownload(
    pending: SftpPendingDownload,
    write: (OutputStream) -> Unit,
) {
    try {
        pending.stream.use(write)
        pending.commit()
    } catch (error: Throwable) {
        runCatching { pending.abort() }
        throw error
    }
}

internal suspend inline fun withDownloadDestination(
    destination: SftpDownloadDestination,
    transfer: suspend () -> Unit,
) {
    try {
        transfer()
        destination.complete()
    } catch (error: Throwable) {
        runCatching { destination.abort() }
        throw error
    }
}

internal inline fun atomicRemoteFileWrite(
    temporaryPath: String,
    finalPath: String,
    write: (String) -> Unit,
    rename: (String, String) -> Unit,
    removeTemporary: (String) -> Unit,
) {
    try {
        write(temporaryPath)
        rename(temporaryPath, finalPath)
    } catch (error: Throwable) {
        runCatching { removeTemporary(temporaryPath) }
        throw error
    }
}

internal suspend inline fun atomicRemoteDirectoryWrite(
    temporaryPath: String,
    finalPath: String,
    createTemporary: (String) -> Unit,
    populate: suspend (String) -> Unit,
    rename: (String, String) -> Unit,
    cleanupTemporary: suspend (String) -> Unit,
) {
    try {
        createTemporary(temporaryPath)
        populate(temporaryPath)
        rename(temporaryPath, finalPath)
    } catch (error: Throwable) {
        runCatching { cleanupTemporary(temporaryPath) }
        throw error
    }
}

internal fun InputStream.uploadTo(path: String, upload: (InputStream, String) -> Unit) {
    use { upload(it, path) }
}
