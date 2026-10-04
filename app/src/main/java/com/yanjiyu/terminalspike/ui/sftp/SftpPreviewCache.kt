package com.yanjiyu.terminalspike.ui.sftp

import java.io.File
import java.io.OutputStream
import java.util.UUID

/** Each open gets its own directory, so equal remote names can never share content. */
internal class SftpPreviewCache(private val root: File) {
    fun download(name: String, write: (OutputStream) -> Unit): File {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Invalid file name."
        }
        check(root.mkdirs() || root.isDirectory) { "Cannot create the file preview cache." }
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdir()) { "Cannot create the file preview cache." }
        val file = File(directory, name)
        try {
            file.outputStream().use(write)
            return file
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }
}
