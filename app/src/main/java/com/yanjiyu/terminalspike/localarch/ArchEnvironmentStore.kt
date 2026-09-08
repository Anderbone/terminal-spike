package com.yanjiyu.terminalspike.localarch

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One persistent Arch environment. Incomplete generations are never advertised as installed. */
internal class ArchEnvironmentStore(private val directory: File) {
    private val lock = Any()
    private val generations = File(directory, "generations")
    private val activeMarker = File(directory, "active.properties")
    private var operationActive = false
    private var leases = 0
    val downloadFile = File(directory, "arch-rootfs.tar.xz.part")

    init {
        require(directory.isAbsolute)
        Files.createDirectories(generations.toPath())
    }

    data class Installation(val id: String, val rootfs: File, val version: String, val checksum: String)

    fun installed(): Installation? = synchronized(lock) { readInstalled() }

    fun acquire(): Lease = synchronized(lock) {
        check(!operationActive) { "Arch installation or reset is in progress" }
        val installed = readInstalled() ?: throw IOException("Arch Linux is not installed")
        leases++
        Lease(installed)
    }

    fun beginOperation(): Operation = synchronized(lock) {
        check(!operationActive) { "Another Arch operation is in progress" }
        check(leases == 0) { "Close all Local Arch tabs before resetting or reinstalling" }
        operationActive = true
        Operation()
    }

    inner class Lease internal constructor(val installation: Installation) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(lock) { leases-- }
        }
    }

    inner class Operation internal constructor() : Closeable {
        private var closed = false
        private val id = UUID.randomUUID().toString()
        val generation = File(generations, id)
        val rootfs = File(generation, "rootfs")

        fun prepare() {
            checkOpen()
            cleanupInactive()
            Files.createDirectory(generation.toPath())
        }

        /** Runs validation before the only activation point. The old environment survives failure. */
        fun activate(validate: (File) -> Unit): Installation {
            checkOpen()
            validate(rootfs)
            if (!Files.isDirectory(rootfs.toPath(), NOFOLLOW_LINKS)) throw IOException("Missing Arch rootfs")
            val metadata = Properties().apply {
                setProperty("generation", id)
                setProperty("version", ArchRootfsManifest.VERSION)
                setProperty("sha256", ArchRootfsManifest.SHA256)
                setProperty("runtime", ArchRootfsManifest.RUNTIME_VERSION)
                setProperty("format", "1")
            }
            val pending = File(directory, "active.pending")
            FileOutputStream(pending).use { output ->
                metadata.store(output, "Validated Local Arch Linux installation")
                output.fd.sync()
            }
            Files.move(pending.toPath(), activeMarker.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(directory.toPath())
            return Installation(id, rootfs, ArchRootfsManifest.VERSION, ArchRootfsManifest.SHA256)
        }

        /** Caller obtains the user's destructive confirmation before beginning this operation. */
        fun reset() {
            checkOpen()
            Files.deleteIfExists(activeMarker.toPath())
            syncDirectory(directory.toPath())
            cleanupInactive()
            Files.deleteIfExists(downloadFile.toPath())
            Files.deleteIfExists(File(directory, "active.pending").toPath())
        }

        fun cleanupInactive() {
            checkOpen()
            val activeId = readInstalled()?.id
            Files.newDirectoryStream(generations.toPath()).use { entries ->
                entries.forEach { entry ->
                    if (entry.fileName.toString() != activeId) deleteTree(entry)
                }
            }
        }

        private fun checkOpen() = synchronized(lock) { check(!closed && operationActive) }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                operationActive = false
            }
        }
    }

    private fun readInstalled(): Installation? {
        if (!Files.exists(activeMarker.toPath(), NOFOLLOW_LINKS)) return null
        // An unreadable marker is not permission to garbage-collect a user's filesystem.
        if (!Files.isRegularFile(activeMarker.toPath(), NOFOLLOW_LINKS) || activeMarker.length() > 4096) {
            throw IOException("Arch installation metadata needs recovery; existing files are retained")
        }
        val metadata = Properties()
        try {
            activeMarker.inputStream().use(metadata::load)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid Arch installation metadata", failure)
        }
        val id = metadata.getProperty("generation").orEmpty()
        if (!GENERATION_ID.matches(id) || metadata.getProperty("format") != "1") {
            throw IOException("Unsupported Arch installation metadata")
        }
        val version = metadata.getProperty("version")?.takeIf { it.length in 1..128 }
            ?: throw IOException("Missing Arch installation version")
        val checksum = metadata.getProperty("sha256")?.takeIf { SHA256.matches(it) }
            ?: throw IOException("Missing Arch installation checksum")
        val generation = File(generations, id)
        val rootfs = File(generation, "rootfs")
        if (!Files.isDirectory(generation.toPath(), NOFOLLOW_LINKS) ||
            !Files.isDirectory(rootfs.toPath(), NOFOLLOW_LINKS)
        ) throw IOException("Arch installation filesystem is missing; existing files are retained")
        // Do not require today's manifest digest: an APK update must retain installed user data.
        return Installation(id, rootfs, version, checksum)
    }

    companion object {
        private val GENERATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun storageBytes(root: Path, checkCancelled: () -> Unit = {}): Long {
            var bytes = 0L
            if (!Files.exists(root, NOFOLLOW_LINKS)) return bytes
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled()
                    bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled()
                    return FileVisitResult.CONTINUE
                }
            })
            return bytes
        }

        /** Never follows guest symlinks, including absolute /proc and /dev targets. */
        fun deleteTree(root: Path) {
            if (!Files.exists(root, NOFOLLOW_LINKS)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.setPosixFilePermissions(dir, Files.getPosixFilePermissions(dir) + setOf(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    ))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // walkFileTree opens a directory before preVisitDirectory.
                    // systemd creates run/systemd/dissect-root with mode 000.
                    if (exc !is java.nio.file.AccessDeniedException || !Files.isDirectory(file, NOFOLLOW_LINKS)) throw exc
                    val permissions = Files.getPosixFilePermissions(file, NOFOLLOW_LINKS)
                    val ownerAccess = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE)
                    if (permissions.containsAll(ownerAccess)) throw exc // Do not retry an unrelated access failure.
                    Files.setPosixFilePermissions(file, permissions + ownerAccess)
                    deleteTree(file)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        private fun syncDirectory(path: Path) {
            FileChannel.open(path, READ).use { it.force(true) }
        }
    }
}
