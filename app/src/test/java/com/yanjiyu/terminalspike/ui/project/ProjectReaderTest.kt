package com.yanjiyu.terminalspike.ui.project

import com.yanjiyu.terminalspike.connection.TmuxCommandRunner
import com.yanjiyu.terminalspike.connection.TmuxExecOutput
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectReaderTest {
    @Test fun statusPreservesOpaqueNamesAndRenameDirection() {
        val changes = parseProjectStatus(" M space name.kt\u0000R  new\nname.kt\u0000old name.kt\u0000?? --odd.kt\u0000")
        assertEquals(listOf("space name.kt", "new\nname.kt", "--odd.kt"), changes.map { it.path })
        assertEquals("old name.kt", changes[1].originalPath)
        assertThrows(IllegalArgumentException::class.java) { parseProjectStatus("R  new.kt\u0000") }
        assertThrows(IllegalArgumentException::class.java) { requireProjectPath("../outside") }
    }

    @Test fun fullFileKeepsContextAndDeletedCodeWithCorrectJumpTargets() {
        val document = projectDocument("test.kt", "one\nnew\nthree\nfour\n", """
            diff --git a/test.kt b/test.kt
            --- a/test.kt
            +++ b/test.kt
            @@ -2 +2 @@
            -old
            +new
            @@ -5 +4,0 @@
            -last
        """.trimIndent(), false)
        assertEquals(listOf("one", "old", "new", "three", "four", "last"), document.lines.map { it.text })
        assertEquals(listOf(1, 5), document.changeStarts)
        assertEquals(1, document.additions)
        assertEquals(2, document.deletions)
        assertEquals(ProjectLineKind.CONTEXT, document.lines.first().kind)
        assertEquals(2, document.lines[1].number)
        assertEquals(2, document.lines[2].number)
    }

    @Test fun deletionAtBeginningAndNewEmptyFilesAreRepresented() {
        val deleted = projectDocument("file", "two\n", "@@ -1 +0,0 @@\n-one\n", false)
        assertEquals(listOf("one", "two"), deleted.lines.map { it.text })
        assertEquals(listOf(0), deleted.changeStarts)
        val new = projectDocument("file", "a\nb\n", "", true)
        assertEquals(2, new.additions)
        assertEquals(listOf(0), new.changeStarts)
        assertTrue(projectDocument("empty", "", "", true).lines.isEmpty())
    }

    @Test fun deletedLongLinesAreBoundedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) {
            projectDocument("file.kt", "short\n", "@@ -1 +1 @@\n-" + "x".repeat(16_001) + "\n+short\n", false)
        }
        val modeOnly = projectDocument("file.sh", "echo hello\n", "old mode 100644\nnew mode 100755\n", false)
        assertEquals(listOf("old mode 100644", "new mode 100755"), modeOnly.metadata)
        assertEquals(0, modeOnly.additions)
    }

    @Test fun syntaxDoesNotInterpretKeywordsInsideStringsOrComments() {
        val text = "val x = \"class 123\" // return"
        val spans = projectSyntax(text, "file.kt")
        assertEquals(listOf(CodeTokenKind.KEYWORD, CodeTokenKind.STRING, CodeTokenKind.COMMENT), spans.map { it.kind })
        assertEquals("\"class 123\"", text.substring(spans[1].start, spans[1].end))
        assertTrue(projectSyntax(text, "file.txt").isEmpty())
    }

    @Test fun realGitReadsStagedUnstagedUntrackedDeletedAndUnbornWithoutChangingIndex() {
        val root = Files.createTempDirectory("project-review '").toFile()
        val runner = TmuxCommandRunner { command ->
            val process = ProcessBuilder("sh", "-c", command).redirectError(java.io.File("/dev/null")).start()
            val stdout = process.inputStream.readBytes()
            TmuxExecOutput(stdout, process.waitFor())
        }
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", root.path) + args)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        try {
            git("init", "-q")
            val reader = ProjectReader(runner)
            root.resolve("new.kt").writeText("val first = 1\n")
            val unborn = reader.snapshot(root.path)
            assertNull(unborn.head)
            assertEquals(1, reader.document(unborn, "new.kt").additions)
            root.resolve("gone.kt").writeText("old\n")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "base")
            root.resolve("new.kt").writeText("val first = 2\n")
            git("add", "new.kt")
            root.resolve("new.kt").appendText("val second = 3\n")
            root.resolve("gone.kt").delete()
            val unusual = "--odd \$(touch injected) ' file.kt"
            root.resolve(unusual).writeText("hello\n")
            root.resolve("binary").writeBytes(byteArrayOf(0, 1, 2))
            val before = root.resolve(".git/index").readBytes()
            val snapshot = reader.snapshot(root.path)
            assertEquals("MM", snapshot.changes.single { it.path == "new.kt" }.status)
            val document = reader.document(snapshot, "new.kt")
            assertEquals(2, document.additions)
            assertEquals(1, document.deletions)
            assertEquals("old", reader.document(snapshot, "gone.kt").lines.single().text)
            assertEquals("hello", reader.document(snapshot, unusual).lines.single().text)
            assertFalse(root.resolve("injected").exists())
            assertThrows(IllegalArgumentException::class.java) { reader.document(snapshot, "binary") }
            assertArrayEquals(before, root.resolve(".git/index").readBytes())
            val outside = Files.createTempFile("outside-project", ".txt")
            try {
                Files.createSymbolicLink(root.resolve("outside-link").toPath(), outside)
                assertThrows(IllegalStateException::class.java) {
                    reader.document(reader.snapshot(root.path), "outside-link")
                }
            } finally { Files.deleteIfExists(outside) }
        } finally { root.deleteRecursively() }
    }

    @Test fun fileChangingDuringReadFailsRatherThanShowingMismatchedDiff() {
        var readCount = 0
        val runner = TmuxCommandRunner { command ->
            val output = if (command.contains("p=\$(realpath")) {
                if (readCount++ == 0) "first\n" else "second\n"
            } else ""
            TmuxExecOutput(output.toByteArray(), 0)
        }
        val snapshot = ProjectSnapshot("/repo", "main", "a".repeat(40), emptyList(), listOf("file.kt"))
        assertThrows(IllegalStateException::class.java) { ProjectReader(runner).document(snapshot, "file.kt") }
    }
}
