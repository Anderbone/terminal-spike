package com.yanjiyu.terminalspike.localarch.control

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Updates only our managed helper files; never touches agent auth, projects, or shell profiles. */
internal class ControlToolsInstaller(private val context: Context) {
    fun install(rootfs: File, connectionInfo: String) {
        write(rootfs, "usr/local/lib/terminal-spike/spikectl.cjs", asset("local-arch/control/spikectl.cjs"))
        val launcher = write(rootfs, "usr/local/bin/spikectl", "#!/bin/sh\nexec node /usr/local/lib/terminal-spike/spikectl.cjs \"\$@\"\n")
        check(launcher.setExecutable(true, true)) { "Could not make spikectl executable." }
        write(rootfs, "etc/terminal-spike/control.json", connectionInfo)
        write(rootfs, "root/.agents/skills/terminal-spike-settings/SKILL.md", asset("local-arch/control/SKILL.md"))
    }

    private fun asset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private fun write(rootfs: File, relative: String, content: String): File {
        var parent = rootfs.toPath()
        relative.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (!Files.exists(parent, NOFOLLOW_LINKS)) {
                try { Files.createDirectory(parent) } catch (_: FileAlreadyExistsException) {
                    // Another local tab may be refreshing the same managed tools concurrently.
                }
            }
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Control tools directory is not a regular directory.")
        }
        val target = parent.resolve(relative.substringAfterLast('/'))
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) throw IOException("Control tools file is not a regular file.")
            if (Files.size(target) <= 128 * 1024 && Files.newInputStream(target).bufferedReader().use { it.readText() } == content) return target.toFile()
        }
        val temporary = Files.createTempFile(parent, ".spikectl-", ".tmp")
        try {
            Files.newOutputStream(temporary, WRITE).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target.toFile()
    }
}
