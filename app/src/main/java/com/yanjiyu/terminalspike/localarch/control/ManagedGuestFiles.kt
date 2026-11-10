package com.yanjiyu.terminalspike.localarch.control

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Only files identical to our last installed bytes are eligible for automatic replacement. */
internal class ManagedGuestFiles(private val rootfs: File) {
    fun write(relative: String, content: String, runtimeData: Boolean = false): File = synchronized(lock) {
        writeLocked(relative, content, runtimeData)
    }

    private fun writeLocked(relative: String, content: String, runtimeData: Boolean): File {
        require(relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
        var parent = rootfs.toPath()
        relative.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (!Files.exists(parent, NOFOLLOW_LINKS)) {
                try { Files.createDirectory(parent) } catch (_: FileAlreadyExistsException) { }
            }
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Control tools directory is not a regular directory.")
        }
        val target = parent.resolve(relative.substringAfterLast('/'))
        if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            throw IOException("Control tools file is not a regular file.")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = sha(bytes)
        val records = File(rootfs.parentFile, "managed-helper-hashes").toPath()
        if (!Files.exists(records, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(records) } catch (_: FileAlreadyExistsException) { }
        }
        if (!Files.isDirectory(records, NOFOLLOW_LINKS)) throw IOException("Invalid helper ownership directory.")
        val marker = records.resolve(sha(relative.toByteArray(Charsets.UTF_8)))
        val previous = if (Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) == 64L) marker.toFile().readText(Charsets.US_ASCII) else null
        val exists = Files.exists(target, NOFOLLOW_LINKS)
        val actual = if (exists) Files.newInputStream(target).use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            hash.digest().joinToString("") { "%02x".format(it) }
        } else null
        if (exists && actual != digest && actual != previous && !runtimeData) {
            // Keep the user's path and ownership marker intact. The versioned sibling is reviewable.
            val candidate = parent.resolve(target.fileName.toString() + ".terminal-spike-$digest.new")
            try { Files.write(candidate, bytes, java.nio.file.StandardOpenOption.CREATE_NEW) }
            catch (_: FileAlreadyExistsException) {
                if (!Files.isRegularFile(candidate, NOFOLLOW_LINKS) || Files.size(candidate) != bytes.size.toLong() ||
                    !Files.readAllBytes(candidate).contentEquals(bytes)) throw IOException("Helper update candidate already exists with different content.")
            }
            return target.toFile()
        }
        if (actual != digest) atomicWrite(target, bytes)
        atomicWrite(marker, digest.toByteArray(Charsets.US_ASCII))
        return target.toFile()
    }

    private fun atomicWrite(target: java.nio.file.Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(target.parent, ".terminal-spike-", ".tmp")
        try {
            Files.newOutputStream(temporary, WRITE).use { it.write(bytes) }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private companion object { val lock = Any() }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
