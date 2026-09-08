package com.yanjiyu.terminalspike.localarch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Configures and validates a staged Arch filesystem through the production PRoot command. */
internal class ArchBootstrap(private val nativeLibraries: File) {
    fun validateRuntime() {
        listOf("libproot.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so").forEach { name ->
            requireArm64Elf(File(nativeLibraries, name))
        }
        if (!File(nativeLibraries, "libproot.so").canExecute() ||
            !File(nativeLibraries, "libproot_loader.so").canExecute()
        ) throw IOException("Packaged Arch runtime is not executable")
    }

    fun configure(rootfs: File, dnsServers: List<String>) {
        require(dnsServers.isNotEmpty()) { "No network DNS is available; connect to a network and retry" }
        writeGuestFile(rootfs, "etc/resolv.conf", resolverText(dnsServers))
        File(rootfs.parentFile, "managed-resolv.conf").writeText(resolverText(dnsServers))
        writeGuestFile(rootfs, "etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        writeGuestFile(rootfs, "etc/hostname", "localhost\n")
        // The bootstrap's sync databases describe old mirror contents. Never let
        // their timestamps suppress the first refresh. Installed package metadata
        // under var/lib/pacman/local is retained unchanged.
        val sync = guestFile(rootfs, "var/lib/pacman/sync").toPath()
        if (!Files.isDirectory(sync, NOFOLLOW_LINKS)) throw IOException("Invalid Arch package cache")
        Files.newDirectoryStream(sync).use { entries -> entries.forEach(ArchEnvironmentStore::deleteTree) }

        // Official Arch Linux ARM mirror, verified over HTTPS. Package signatures remain required.
        writeGuestFile(rootfs, "etc/pacman.d/mirrorlist",
            "# Local Arch Linux bootstrap mirror; you may change this file.\n" +
                "Server = https://de3.mirror.archlinuxarm.org/\$arch/\$repo\n")
    }

    /** Preserve user edits: update only a resolver file identical to our last generated version. */
    @Synchronized
    fun refreshDns(rootfs: File, dnsServers: List<String>) {
        if (dnsServers.isEmpty()) return // An offline shell and its existing configuration remain usable.
        val guest = guestFile(rootfs, "etc/resolv.conf")
        val previous = File(rootfs.parentFile, "managed-resolv.conf")
        if (!Files.isRegularFile(guest.toPath(), NOFOLLOW_LINKS) || guest.length() > 8192 ||
            !previous.isFile || previous.length() > 8192 || guest.readText() != previous.readText()
        ) return
        val updated = resolverText(dnsServers)
        if (guest.readText() != updated) {
            writeGuestFile(rootfs, "etc/resolv.conf", updated)
            previous.writeText(updated)
        }
    }

    fun validate(rootfs: File, temporaryDirectory: File, checkCancelled: () -> Unit = {}) {
        validateRuntime()
        listOf("usr/bin/bash", "usr/bin/env", "usr/bin/pacman", "usr/lib/ld-linux-aarch64.so.1")
            .forEach { requireArm64Elf(guestFile(rootfs, it)) }
        val release = guestFile(rootfs, "usr/lib/os-release")
        if (release.length() > 8192 || release.readLines().none { it == "ID=archarm" }) {
            throw IOException("Downloaded filesystem is not Arch Linux ARM")
        }
        val output = runGuest(rootfs, temporaryDirectory, listOf("/bin/bash", "--noprofile", "--norc", "-c", VALIDATE),
            timeoutMillis = 8 * 60_000L, checkCancelled = checkCancelled)
        if (!output.contains("LOCAL_ARCH_BOOTSTRAP_OK")) throw IOException("Arch validation did not finish")
    }

    fun command(rootfs: File, temporaryDirectory: File, guestCommand: List<String> = listOf("/bin/bash", "--login")): ProotCommand {
        Files.createDirectories(temporaryDirectory.toPath())
        return ProotCommand.archShell(nativeLibraries, rootfs, temporaryDirectory, guestCommand)
    }

    fun runGuest(
        rootfs: File,
        temporaryDirectory: File,
        guestCommand: List<String>,
        timeoutMillis: Long,
        checkCancelled: () -> Unit = {},
    ): String {
        command(rootfs, temporaryDirectory, guestCommand).start(100, 30).use { pty ->
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            val output = StringBuilder()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkCancelled()
                if (System.nanoTime() >= deadline) throw IOException("Arch command timed out")
                val count = pty.read(buffer)
                if (count < 0) break
                if (count > 0) output.append(String(buffer, 0, count, Charsets.UTF_8))
                if (output.length > 64 * 1024) output.delete(0, output.length - 64 * 1024)
            }
            var exitCode = pty.exitCode()
            while (exitCode == null && System.nanoTime() < deadline) {
                checkCancelled()
                Thread.sleep(10)
                exitCode = pty.exitCode()
            }
            if (exitCode != 0) {
                throw IOException("Arch command exited with status $exitCode: " + output.take(3072) +
                    if (output.length > 3072) "\n[...bounded output...]\n" + output.takeLast(2048) else "")
            }
            return output.toString()
        }
    }

    private fun requireArm64Elf(file: File) {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Missing Arch runtime file: ${file.name}")
        val header = ByteArray(20)
        file.inputStream().use { java.io.DataInputStream(it).readFully(header) }
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte() || header[4] != 2.toByte() || header[5] != 1.toByte() ||
            header[18] != 183.toByte() || header[19] != 0.toByte()
        ) throw IOException("Arch runtime file is not Linux ARM64: ${file.name}")
    }

    private fun resolverText(servers: List<String>): String {
        require(servers.size <= 16 && servers.all { it.isNotEmpty() && it.all { c -> c in "0123456789abcdefABCDEF:." } })
        return "# Managed by Terminal Spike Local Arch Linux\n" + servers.distinct().joinToString("") { "nameserver $it\n" }
    }

    private fun guestFile(rootfs: File, relative: String): File {
        var parent = rootfs.toPath()
        val parts = relative.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." })
        parts.dropLast(1).forEach { part ->
            parent = parent.resolve(part)
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Arch configuration path is not a directory")
        }
        return parent.resolve(parts.last()).toFile()
    }

    private fun writeGuestFile(rootfs: File, relative: String, contents: String) {
        val target = guestFile(rootfs, relative).toPath()
        // Refuse guest links: never overwrite something in the Android filesystem through one.
        Files.newOutputStream(target, CREATE, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use {
            it.write(contents.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private val VALIDATE = """
            set -eu
            test "${'$'}(id -u)" = 0
            test "${'$'}(uname -m)" = aarch64
            test -d /var/lib/pacman/local
            pacman --version
            pacman-key --init
            pacman-key --populate archlinuxarm
            printf '\nLOCAL_ARCH_BOOTSTRAP_OK\n'
        """.trimIndent()
    }
}
