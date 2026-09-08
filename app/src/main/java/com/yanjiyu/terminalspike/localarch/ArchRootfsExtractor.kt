package com.yanjiyu.terminalspike.localarch

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.FileTime
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream

/** Extracts only the pinned Arch archive shape into a new, never-exposed root. */
internal class ArchRootfsExtractor(
    private val maximumBytes: Long = ArchRootfsManifest.MAX_EXPANDED_BYTES,
    private val maximumEntries: Int = ArchRootfsManifest.MAX_ENTRIES,
) {
    fun extract(archive: File, rootfs: File, checkCancelled: () -> Unit = {}) {
        archive.inputStream().buffered().use { input ->
            XZInputStream(input, 64 * 1024).use { xz ->
                extractTar(xz, rootfs.toPath(), checkCancelled)
            }
        }
    }

    internal fun extractTar(input: InputStream, root: Path, checkCancelled: () -> Unit = {}) {
        require(maximumBytes > 0 && maximumEntries > 0)
        require(root.isAbsolute && root == root.normalize())
        // Caller owns rollback. An existing filesystem is never an extraction target.
        Files.createDirectory(root)
        val seen = HashSet<Path>()
        val attributes = ArrayList<Triple<Path, Int, FileTime>>()
        val links = ArrayList<Pair<Path, String>>()
        var expandedBytes = 0L
        TarArchiveInputStream(input).use { tar ->
            var entryCount = 0
            while (true) {
                checkCancelled()
                val entry = tar.nextEntry ?: break
                if (++entryCount > maximumEntries) throw IOException("Arch archive has too many entries")
                val name = entry.name.trimEnd('/')
                if (name == ArchRootfsManifest.ARCHIVE_PREFIX) {
                    if (!entry.isDirectory) throw IOException("Invalid Arch root directory")
                    continue
                }
                val prefix = ArchRootfsManifest.ARCHIVE_PREFIX + "/"
                if (!name.startsWith(prefix)) throw IOException("Unexpected Arch archive prefix")
                val relative = name.removePrefix(prefix)
                if (relative.isEmpty() || '\u0000' in relative ||
                    relative.split('/').any { it.isEmpty() || it == "." || it == ".." }
                ) throw IOException("Unsafe Arch archive path")
                val target = root.resolve(relative).normalize()
                if (!target.startsWith(root) || !seen.add(target)) throw IOException("Conflicting Arch archive path")
                if (entry.isSparse || entry.isLink) throw IOException("Unsupported Arch archive link or sparse entry")
                ensureParents(root, target.parent)
                when {
                    entry.isDirectory -> {
                        if (!Files.exists(target, NOFOLLOW_LINKS)) Files.createDirectory(target)
                        if (!Files.isDirectory(target, NOFOLLOW_LINKS)) throw IOException("Conflicting Arch directory")
                        attributes.add(Triple(target, entry.mode, entry.lastModifiedTime))
                    }
                    entry.isSymbolicLink -> {
                        if (entry.linkName.isEmpty() || '\u0000' in entry.linkName) throw IOException("Invalid Arch symlink")
                        links.add(target to entry.linkName)
                    }
                    entry.isFile -> {
                        if (entry.size < 0 || entry.size > maximumBytes - expandedBytes) {
                            throw IOException("Arch archive exceeds extraction size limit")
                        }
                        expandedBytes += entry.size
                        Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = entry.size
                            while (remaining > 0) {
                                checkCancelled()
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count <= 0) throw IOException("Truncated Arch archive entry")
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                        attributes.add(Triple(target, entry.mode, entry.lastModifiedTime))
                    }
                    else -> throw IOException("Unsupported Arch archive entry")
                }
            }
        }
        // Links are created only after all file writes. If a link path was used
        // as a parent by any entry, creation fails instead of replacing that directory.
        links.forEach { (path, target) ->
            checkCancelled()
            ensureParents(root, path.parent)
            Files.createSymbolicLink(path, Paths.get(target))
        }
        // Every chmod target was created as a file/directory by this extractor.
        // Restore deepest-first so restrictive ancestors do not block children.
        attributes.sortedByDescending { it.first.nameCount }.forEach { (path, mode, modified) ->
            checkCancelled()
            Files.setLastModifiedTime(path, modified)
            Files.setPosixFilePermissions(path, modePermissions(mode))
        }
    }

    private fun ensureParents(root: Path, directory: Path) {
        var current = root
        root.relativize(directory).forEach { segment ->
            current = current.resolve(segment)
            if (!Files.exists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) throw IOException("Arch path crosses a non-directory")
        }
    }

    private fun modePermissions(mode: Int): Set<PosixFilePermission> {
        val flags = listOf(
            0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE,
            0x40 to PosixFilePermission.OWNER_EXECUTE, 0x20 to PosixFilePermission.GROUP_READ,
            0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
            0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE,
            0x1 to PosixFilePermission.OTHERS_EXECUTE,
        )
        return flags.filter { (flag, _) -> mode and flag != 0 }.map { it.second }.toSet()
    }
}
