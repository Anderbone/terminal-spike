package com.yanjiyu.terminalspike.core.backup

import java.io.InputStream

/** The caller receives ownership only after its provider input closes successfully. */
internal suspend fun <T : AutoCloseable> InputStream.readOwnedResult(read: suspend (InputStream) -> T): T {
    var result: T? = null
    try {
        use { result = read(it) }
    } catch (failure: Throwable) {
        try {
            result?.close()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    return checkNotNull(result)
}
