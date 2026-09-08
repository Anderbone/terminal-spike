package com.yanjiyu.terminalspike.localarch

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Downloads only the reviewed bootstrap; redirects never receive credentials. */
internal class ArchRootfsDownloader {
    fun download(part: File, checkCancelled: () -> Unit, progress: (Long) -> Unit): File {
        if (Files.exists(part.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(part.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Invalid Arch download file")
        }
        if (part.length() > ArchRootfsManifest.DOWNLOAD_BYTES) Files.delete(part.toPath())
        var restarted = false
        while (part.length() < ArchRootfsManifest.DOWNLOAD_BYTES) {
            checkCancelled()
            val offset = part.length()
            val connection = connect(offset, checkCancelled)
            try {
                val response = connection.responseCode
                if (response == 416 && offset > 0 && !restarted) {
                    Files.delete(part.toPath())
                    restarted = true
                    continue
                }
                val append = validateResponse(response, connection.getHeaderField("Content-Range"), offset)
                val startingAt = if (append) offset else 0L
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0 && contentLength != ArchRootfsManifest.DOWNLOAD_BYTES - startingAt) {
                    throw IOException("Unexpected Arch download size")
                }
                connection.inputStream.buffered().use { input ->
                    java.io.FileOutputStream(part, append).use { output ->
                        var received = startingAt
                        progress(received)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count.toLong() > ArchRootfsManifest.DOWNLOAD_BYTES - received) {
                                throw IOException("Arch download exceeds expected size")
                            }
                            output.write(buffer, 0, count)
                            received += count
                            progress(received)
                        }
                        output.fd.sync()
                        if (received != ArchRootfsManifest.DOWNLOAD_BYTES) throw IOException("Arch download was interrupted; retry to resume")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        try {
            verify(part, checkCancelled)
        } catch (failure: IOException) {
            // A complete corrupt file cannot be resumed. Never pass it to an archive parser.
            Files.deleteIfExists(part.toPath())
            throw failure
        }
        return part
    }

    private fun connect(offset: Long, checkCancelled: () -> Unit): HttpsURLConnection {
        var url = URL(ArchRootfsManifest.URL)
        repeat(6) {
            checkCancelled()
            if (url.protocol != "https" || url.userInfo != null) throw IOException("Unsafe Arch download redirect")
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location") ?: throw IOException("Missing Arch download redirect")
                url = URL(url, location)
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        throw IOException("Too many Arch download redirects")
    }

    internal fun validateResponse(status: Int, contentRange: String?, offset: Long): Boolean = when (status) {
        200 -> false // A server ignoring Range must replace, never append to, the partial file.
        206 -> {
            val expected = "bytes $offset-${ArchRootfsManifest.DOWNLOAD_BYTES - 1}/${ArchRootfsManifest.DOWNLOAD_BYTES}"
            if (offset <= 0 || contentRange != expected) throw IOException("Invalid Arch download resume response")
            true
        }
        else -> throw IOException("Arch download failed (HTTP $status)")
    }

    internal fun verify(file: File, checkCancelled: () -> Unit = {}) {
        if (file.length() != ArchRootfsManifest.DOWNLOAD_BYTES) throw IOException("Arch archive size does not match")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != ArchRootfsManifest.SHA256) throw IOException("Arch archive checksum does not match; retry the download")
    }
}
