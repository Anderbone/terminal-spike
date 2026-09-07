package com.yanjiyu.terminalspike.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpPathTest {
    @Test
    fun normalizesRemotePathsWithoutEscapingRoot() {
        assertEquals("/home/files", normalizeAbsolutePath("//home/./user/../files/"))
        assertEquals("/etc", normalizeAbsolutePath("../../etc"))
    }

    @Test
    fun buildsChildAndParentPaths() {
        assertEquals("/report.txt", childPath("/", "report.txt"))
        assertEquals("/home/report.txt", childPath("/home/", "report.txt"))
        assertEquals("/home", parentPath("/home/report.txt"))
        assertEquals("/", parentPath("/home"))
    }

    @Test
    fun preservesLiteralBackslashAsPartOfPosixFileName() {
        assertEquals("/home/a\\b", normalizeAbsolutePath("/home/a\\b"))
        assertEquals("/home/a\\b", childPath("/home", "a\\b"))
        assertEquals("/home", parentPath("/home/a\\b"))
        assertEquals("a\\b", fileName("/home/a\\b"))
        assertEquals("/home/a\\b", childPath(parentPath("/home/a\\b"), "a\\b"))
    }

    @Test
    fun rejectsNamesThatCouldChangeDirectories() {
        assertThrows(IllegalArgumentException::class.java) { childPath("/home", "../tmp") }
        assertThrows(IllegalArgumentException::class.java) { childPath("/home", "") }
        assertThrows(IllegalArgumentException::class.java) { childPath("/home", "a/b") }
        assertThrows(IllegalArgumentException::class.java) { childPath("/home", "a\u0000b") }
    }
}
