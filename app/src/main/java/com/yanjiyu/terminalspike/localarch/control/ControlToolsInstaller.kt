package com.yanjiyu.terminalspike.localarch.control

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Updates only our managed helper files; never touches agent auth, projects, or shell profiles. */
internal class ControlToolsInstaller(private val context: Context) {
    fun install(rootfs: File, connectionInfo: String) {
        write(rootfs, "usr/local/lib/terminal-spike/spikectl.cjs", asset("local-arch/control/spikectl.cjs"))
        val launcher = write(rootfs, "usr/local/bin/spikectl", "#!/bin/sh\nexec node /usr/local/lib/terminal-spike/spikectl.cjs \"\$@\"\n")
        check(launcher.setExecutable(true, true)) { "Could not make spikectl executable." }
        write(rootfs, "etc/terminal-spike/control.json", connectionInfo)
        write(rootfs, "root/.agents/skills/terminal-spike-settings/SKILL.md", asset("local-arch/control/SKILL.md"))
    }

    fun installBrowser(rootfs: File, connectionInfo: String) {
        write(rootfs, "usr/local/lib/terminal-spike/spike-open.cjs", asset("local-arch/spike-open.cjs"))
        val launcher = "#!/bin/sh\nexec node /usr/local/lib/terminal-spike/spike-open.cjs \"\$@\"\n"
        check(write(rootfs, "usr/local/bin/spike-open", launcher).setExecutable(true, true))
        write(rootfs, "etc/terminal-spike/browser.json", connectionInfo)
        // Our PATH precedes /usr/bin. Preserve an existing user-owned local opener.
        val xdg = File(rootfs, "usr/local/bin/xdg-open").toPath()
        if (!Files.exists(xdg, NOFOLLOW_LINKS) ||
            (Files.isRegularFile(xdg, NOFOLLOW_LINKS) && Files.size(xdg) == launcher.toByteArray().size.toLong() &&
                Files.newInputStream(xdg).bufferedReader().use { it.readText() } == launcher)) {
            check(write(rootfs, "usr/local/bin/xdg-open", launcher).setExecutable(true, true))
        }
    }

    private fun asset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private fun write(rootfs: File, relative: String, content: String): File =
        ManagedGuestFiles(rootfs).write(relative, content,
            runtimeData = relative == "etc/terminal-spike/control.json" || relative == "etc/terminal-spike/browser.json")
}
