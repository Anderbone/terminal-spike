package com.yanjiyu.terminalspike.ui.project

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectDocumentLimitsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oversizedDeletionIsRejectedWithinABoundedHeap() = probe("deleted")

    @Test fun oversizedCurrentFileIsRejectedWithinABoundedHeap() = probe("current")

    @Test fun oversizedAddedPatchIsRejectedWithinABoundedHeap() = probe("added")

    @Test fun unsupportedLineNumbersProduceABoundedErrorWithoutEchoingRemoteInput() {
        val coordinate = "9".repeat(60_000)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            projectDocument("coordinate.txt", "new\n", "@@ -$coordinate +1 @@\n-old\n+new\n", false)
        }
        assertTrue("Remote line coordinates must not become an unbounded UI error", failure.message.orEmpty().length <= 240)
    }

    @Test fun exactCurrentLineLimitIsAcceptedAndTheNextLineIsRejected() {
        val content = "current\n".repeat(MAX_PROJECT_LINES)
        assertEquals(MAX_PROJECT_LINES, projectDocument("boundary.txt", content, "", true).lines.size)
        assertThrows(IllegalArgumentException::class.java) {
            projectDocument("boundary.txt", content + "excess\n", "", true)
        }
    }

    @Test fun unsupportedCoordinatesDoNotWrapOrSilentlyLoseDeletedRows() {
        val last = projectDocument("coordinate.txt", "", "@@ -${Int.MAX_VALUE} +0,0 @@\n-old\n", false)
        assertEquals(Int.MAX_VALUE, last.lines.single().number)
        listOf(
            "@@ -${Int.MAX_VALUE},2 +0,0 @@\n-first\n-second\n",
            "@@ -1 +5,0 @@\n-old\n",
        ).forEach { patch ->
            assertThrows(IllegalArgumentException::class.java) {
                projectDocument("coordinate.txt", "", patch, false)
            }
        }
    }

    @Test fun zeroLengthRangeCannotOverflowItsNewLineCoordinate() {
        assertThrows(IllegalArgumentException::class.java) {
            projectDocument("coordinate.txt", "", "@@ -1 +${Int.MAX_VALUE},0 @@\n-old\n", false)
        }
    }

    @Test fun binaryPatchIsRejectedEvenWhenCurrentFileIsText() {
        assertThrows(IllegalArgumentException::class.java) {
            projectDocument("binary.txt", "now text\n", "Binary files a/binary.txt and b/binary.txt differ\n", false)
        }
    }

    @Test fun displayedRowLimitIncludesCurrentAndDeletedRows() {
        val current = "current\n".repeat(MAX_PROJECT_LINES / 2)
        val deleted = "-old\n".repeat(MAX_PROJECT_LINES / 2)
        val patch = "@@ -1,${MAX_PROJECT_LINES / 2} +0,0 @@\n" + deleted
        val document = projectDocument("boundary.txt", current, patch, false)
        assertEquals(MAX_PROJECT_LINES, document.lines.size)
        assertEquals(MAX_PROJECT_LINES / 2, document.deletions)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            projectDocument("boundary.txt", current, patch + "-excess\n", false)
        }
        assertTrue(failure.message.orEmpty().contains("$MAX_PROJECT_LINES line limit"))
    }

    @Test fun sourceLineSplittingPreservesCarriageReturnsAndTrailingEmptyLines() {
        val document = projectDocument("endings.txt", "one\r\n\nlast\r", "", false)
        assertEquals(listOf("one\r", "", "last\r"), document.lines.map { it.text })
        assertEquals(listOf(""), projectDocument("empty-line.txt", "\n", "", false).lines.map { it.text })
        assertEquals(emptyList<ProjectLine>(), projectDocument("empty.txt", "", "", false).lines)
    }

    private fun probe(scenario: String) {
        // Isolate an allocation regression from Gradle's worker and the developer's Android app.
        val classpath = listOf(
            ProjectDocumentMemoryProbe::class.java,
            Class.forName("com.yanjiyu.terminalspike.ui.project.ProjectDocumentKt"),
            kotlin.Unit::class.java,
        ).map { File(requireNotNull(it.protectionDomain?.codeSource).location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        val output = temporary.newFile("$scenario.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx64m", "-cp", classpath,
            ProjectDocumentMemoryProbe::class.java.name, scenario,
        ).directory(temporary.root).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("Memory probe timed out", process.waitFor(60, TimeUnit.SECONDS))
            val log = output.readText()
            assertEquals(log, 0, process.exitValue())
            assertTrue(log, log.contains("rejected by line limit"))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}

/** Separate JVM entry point: input bytes stay below the product's existing 4 MiB read limit. */
internal object ProjectDocumentMemoryProbe {
    @JvmStatic fun main(args: Array<String>) {
        val rows = 1_000_000
        val scenario = args.single()
        val current = if (scenario == "current") "x\n".repeat(rows) else ""
        val patch = when (scenario) {
            "deleted" -> "@@ -1,$rows +0,0 @@\n" + "-x\n".repeat(rows)
            "added" -> "@@ -0,0 +1,$rows @@\n" + "+x\n".repeat(rows)
            "current" -> ""
            else -> error("Unknown memory probe")
        }
        check(current.toByteArray().size <= MAX_PROJECT_BYTES && patch.toByteArray().size <= MAX_PROJECT_BYTES)
        try {
            projectDocument("oversized.txt", current, patch, false)
            error("Oversized document was accepted")
        } catch (expected: IllegalArgumentException) {
            check(expected.message.orEmpty().contains("$MAX_PROJECT_LINES line limit")) { expected.toString() }
            println("rejected by line limit")
        }
    }
}
