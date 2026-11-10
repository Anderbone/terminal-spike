package com.yanjiyu.terminalspike.localarch.control

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedGuestFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = temporary.newFolder("installation").resolve("rootfs").apply { mkdir() }

    @Test fun upgradesUntouchedFilesAndPreservesUserChangesAcrossRepeatedUpdates() {
        val root = root()
        val files = ManagedGuestFiles(root)
        val tool = files.write("usr/local/bin/spikectl", "v1")
        files.write("usr/local/bin/spikectl", "v2")
        assertEquals("v2", tool.readText())
        tool.writeText("my custom tool")
        repeat(2) { files.write("usr/local/bin/spikectl", "v3") }
        assertEquals("my custom tool", tool.readText())
        assertEquals("v3", tool.parentFile!!.listFiles()!!.single { it.name.endsWith(".new") }.readText())
        tool.writeText("v2")
        files.write("usr/local/bin/spikectl", "v3")
        assertEquals("v3", tool.readText())
    }

    @Test fun legacyUnknownFileIsPreservedAndUnrelatedHomeDataIsUntouched() {
        val root = root()
        val custom = File(root, "root/.agents/skills/terminal-spike-settings/SKILL.md")
        custom.parentFile!!.mkdirs(); custom.writeText("custom skill")
        val project = File(root, "root/project.txt").apply { writeText("important") }
        ManagedGuestFiles(root).write("root/.agents/skills/terminal-spike-settings/SKILL.md", "new skill")
        assertEquals("custom skill", custom.readText())
        assertEquals("important", project.readText())
    }

    @Test fun refusesGuestSymlinksAndRefreshesOnlyExplicitRuntimeData() {
        val root = root()
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "usr").toPath(), outside.parentFile!!.toPath())
        assertThrows(java.io.IOException::class.java) { ManagedGuestFiles(root).write("usr/other", "overwrite") }
        assertEquals("keep", outside.readText())
        val files = ManagedGuestFiles(root)
        val config = files.write("etc/terminal-spike/control.json", "old", runtimeData = true)
        config.writeText("stale")
        files.write("etc/terminal-spike/control.json", "new", runtimeData = true)
        assertEquals("new", config.readText())
    }
}
