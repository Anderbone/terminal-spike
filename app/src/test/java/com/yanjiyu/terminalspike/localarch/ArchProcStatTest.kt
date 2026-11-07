package com.yanjiyu.terminalspike.localarch

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ArchProcStatTest {
    private val directory = Files.createTempDirectory("arch-proc-stat").toFile()
    private val deniedStat = File(directory, "unavailable-kernel-stat")

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun bootTimeStaysStableAcrossOwnersAndClockCorrectionsButChangesAfterReboot() {
        val first = ArchProcStat({ "first-boot" }, { 1_790_000_000 }, deniedStat).prepare(directory)!!
        val second = ArchProcStat({ "first-boot" }, { error("Must reuse this boot's epoch") }, deniedStat).prepare(directory)!!
        assertEquals(first, second)
        assertTrue(second.readText().contains("\nbtime 1790000000\n"))
        assertEquals("cpu  0 0 0 0 0 0 0 0 0 0", second.readLines().first())
        assertFalse(second.readText().lineSequence().any { it.startsWith("cpu0") })
        val rebooted = ArchProcStat({ "second-boot" }, { 1_790_100_000 }, deniedStat).prepare(directory)!!
        assertNotEquals(first, rebooted)
        assertTrue(rebooted.readText().contains("\nbtime 1790100000\n"))
        assertTrue(first.readText().contains("\nbtime 1790000000\n"))
    }

    @Test fun readableKernelStatisticsAreLeftUntouched() {
        val kernel = File(directory, "kernel-stat").apply { writeText("cpu 10 20 30\nbtime 1234\n") }
        assertNull(ArchProcStat({ error("No compatibility file needed") }, { 0 }, kernel).prepare(directory))
        assertEquals("cpu 10 20 30\nbtime 1234\n", kernel.readText())
    }

    @Test fun unavailableBootIdentityDoesNotPreventOpeningALocalShell() {
        assertNull(ArchProcStat({ null }, { error("No stable boot identity") }, deniedStat).prepare(directory))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun interruptedOrSymlinkedCompatibilityFilesAreReplacedWithoutFollowingTheLink() {
        val outside = File(directory, "unrelated").apply { writeText("preserve") }
        val target = File(directory, "proc-stat-this-boot")
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        val compatibility = ArchProcStat({ "this-boot" }, { 1234 }, deniedStat)
        assertEquals(target, compatibility.prepare(directory))
        assertEquals("preserve", outside.readText())
        target.writeText("partial write")
        assertTrue(compatibility.prepare(directory)!!.readText().contains("\nbtime 1234\n"))
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".pending") })
    }
}
