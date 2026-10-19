package com.yanjiyu.terminalspike.ui.sftp

import java.security.MessageDigest

/** Maps remote identities to completed local downloads, independently of SFTP connectivity. */
internal class SftpDownloadedFileIndex(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val readable: (String) -> Boolean,
) {
    fun find(key: String): String? {
        val item = read(key) ?: return null
        if (readable(item)) return item
        write(key, null)
        return null
    }

    fun remember(key: String, item: String) = write(key, item)

    companion object {
        fun key(endpoint: List<String>, path: String): String {
            val identity = (endpoint + path).joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
