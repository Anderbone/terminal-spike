package com.yanjiyu.terminalspike.connection

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ZoxideRecentProjectsTest {
    private fun database(vararg rows: Pair<String, Long>): ByteArray {
        val paths = rows.map { it.first.toByteArray() }
        val buffer = ByteBuffer.allocate(12 + paths.sumOf { 24 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(3).putLong(rows.size.toLong())
        rows.forEachIndexed { index, row ->
            buffer.putLong(paths[index].size.toLong()).put(paths[index]).putDouble(9999.0 - index).putLong(row.second)
        }
        return buffer.array()
    }

    private fun candidates(paths: List<String>, metadata: ByteArray): TmuxExecOutput = TmuxExecOutput(
        (HERDR_RECENT_PROJECTS_HEADER + "\n" + paths.joinToString("\n", postfix = "\n")).toByteArray() +
            byteArrayOf(0) + metadata, 0,
    )

    @Test fun recentAccessWinsOverFrequencyAndUnknownTimesGoLast() {
        val paths = listOf("/frequent", "/newest", "/older", "/unknown", "/open", "/newest/")
        val times = database("/frequent" to 1_000L, "/newest" to 3_000L, "/older/" to 2_000L, "/open" to 4_000L)
        assertEquals(listOf(
            RecentHerdrProject("/newest", 3_000_000), RecentHerdrProject("/older", 2_000_000),
            RecentHerdrProject("/frequent", 1_000_000), RecentHerdrProject("/unknown"),
        ), filterHerdrRecentProjects(candidates(paths, times), setOf("/open")))
    }

    @Test fun duplicatesKeepNewestTimeAndEqualTimesHaveStableOrder() {
        val times = database("/b" to 5L, "/a/" to 2L, "/a" to 5L)
        assertEquals(listOf(RecentHerdrProject("/a", 5_000), RecentHerdrProject("/b", 5_000)),
            filterHerdrRecentProjects(candidates(listOf("/b", "/a"), times), emptySet()))
    }

    @Test fun resultLimitAppliesAfterSortingAllCandidates() {
        val paths = (1..300).map { "/project/$it" }
        val times = database(*paths.mapIndexed { index, path -> path to index.toLong() + 1 }.toTypedArray())
        val projects = filterHerdrRecentProjects(candidates(paths, times), emptySet())
        assertEquals(256, projects.size)
        assertEquals("/project/300", projects.first().path)
        assertEquals("/project/45", projects.last().path)
    }

    @Test fun missingUnsupportedAndPartialMetadataNeverInventDatesOrHideCandidates() {
        val valid = database("/a" to 50L)
        val unsupported = valid.copyOf().also { it[0] = 4 }
        val corruptLength = valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(12, Long.MAX_VALUE) }
        for (metadata in listOf(byteArrayOf(), unsupported, valid.dropLast(1).toByteArray(),
            valid + byteArrayOf(0), corruptLength, ByteArray(MAX_ZOXIDE_METADATA_BYTES + 1))) {
            assertEquals(listOf(RecentHerdrProject("/a")),
                filterHerdrRecentProjects(candidates(listOf("/a"), metadata), emptySet()))
        }
    }

    @Test fun invalidTimesCountsAndUtf8AreRejected() {
        assertTrue(zoxideLastUsedTimes(database("/a" to -1L)).isEmpty())
        assertTrue(zoxideLastUsedTimes(database("/a" to Long.MAX_VALUE)).isEmpty())
        assertTrue(zoxideLastUsedTimes(database("/a" to 0)).isEmpty())
        val invalidCount = database().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(4, 1000) }
        assertTrue(zoxideLastUsedTimes(invalidCount).isEmpty())
        val invalidUtf8 = database("/a" to 5).also { it[21] = 0xff.toByte() }
        assertTrue(zoxideLastUsedTimes(invalidUtf8).isEmpty())
    }

    @Test fun shellCommandReadsConfiguredDatabaseWithoutModifyingIt() {
        val root = Files.createTempDirectory("recent projects '").toFile()
        try {
            val bin = root.resolve("bin").apply { mkdirs() }
            bin.resolve("zoxide").apply {
                writeText("#!/bin/sh\n[ \"\$1 \$2\" = 'query --list' ] || exit 9\nprintf '/old\\n/new\\n'\n")
                setExecutable(true)
            }
            val data = root.resolve("custom history").apply { mkdirs() }
            val file = data.resolve("db.zo").apply { writeBytes(database("/old" to 5, "/new" to 10)) }
            val before = file.readBytes()
            val process = ProcessBuilder("/bin/sh", "-c", HERDR_RECENT_PROJECTS_COMMAND).apply {
                environment()["PATH"] = "$bin:/usr/bin:/bin"
                environment()["_ZO_DATA_DIR"] = data.absolutePath
            }.start()
            val output = process.inputStream.readBytes()
            assertEquals(process.errorStream.bufferedReader().readText(), 0, process.waitFor())
            assertEquals(listOf(RecentHerdrProject("/new", 10_000), RecentHerdrProject("/old", 5_000)),
                filterHerdrRecentProjects(TmuxExecOutput(output, 0), emptySet()))
            assertArrayEquals(before, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulCreateRecordsVisitButFailedCreateDoesNot() {
        val root = Files.createTempDirectory("project visits ").toFile()
        try {
            val bin = root.resolve("bin").apply { mkdirs() }
            val log = root.resolve("visits")
            bin.resolve("zoxide").apply {
                writeText("#!/bin/sh\n[ \"\$1 \$2\" = 'add --' ] || exit 8\nprintf '%s\\n' \"\$3\" >> \"\$PROJECT_VISIT_LOG\"\n")
                setExecutable(true)
            }
            val herdr = bin.resolve("herdr").apply {
                writeText("#!/bin/sh\nexit \"\$PROJECT_CREATE_STATUS\"\n")
                setExecutable(true)
            }
            val path = root.resolve("a'\$(touch sentinel); b").apply { mkdirs() }.absolutePath
            var status = 0
            val runner = TmuxCommandRunner { command ->
                if (command.contains("'api' 'snapshot'")) {
                    TmuxExecOutput("""{"result":{"snapshot":{"panes":[]}}}""".toByteArray(), 0)
                } else {
                    val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                        environment()["PATH"] = "$bin:/usr/bin:/bin"
                        environment()["PROJECT_VISIT_LOG"] = log.absolutePath
                        environment()["PROJECT_CREATE_STATUS"] = status.toString()
                    }.start()
                    val output = process.inputStream.readBytes()
                    TmuxExecOutput(output, process.waitFor())
                }
            }
            val choice = HerdrStartupChoice(herdr.absolutePath, "selected")
            assertTrue(openHerdrProject(runner, choice, path))
            assertEquals(listOf(path), log.readLines())
            status = 1
            assertFalse(openHerdrProject(runner, choice, path))
            assertEquals(listOf(path), log.readLines())
            assertFalse(root.resolve("sentinel").exists())
        } finally { root.deleteRecursively() }
    }
}
