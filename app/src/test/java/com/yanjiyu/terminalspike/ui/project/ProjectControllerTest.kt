package com.yanjiyu.terminalspike.ui.project

import com.yanjiyu.terminalspike.connection.TmuxCommandRunner
import com.yanjiyu.terminalspike.connection.TmuxExecOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectControllerTest {
    @Test fun refreshWaitsForReconnectAndRetriesRememberedDirectoryAfterFailure() = runTest {
        val store = MemoryProjectStore()
        store.rememberDirectory("host", "/repo")
        var online = false
        var allowReconnect = false
        var reconnects = 0
        var reads = 0
        val runner = TmuxCommandRunner { command ->
            check(online)
            reads++
            val output = when {
                command.contains("rev-parse --show-toplevel") -> "/repo\n"
                command.contains("rev-parse --verify") -> "a".repeat(40) + "\n"
                command.contains("symbolic-ref") -> "main\n"
                else -> ""
            }
            TmuxExecOutput(output.toByteArray(), 0)
        }
        val controller = ProjectController(this, { runner }, store, StandardTestDispatcher(testScheduler),
            ensureConnected = {
                reconnects++
                check(allowReconnect) { "Offline" }
                online = true
            })
        controller.open(10, "Host", "host")
        advanceUntilIdle()
        assertEquals("Offline", controller.state.value.error)
        assertEquals(0, reads)
        allowReconnect = true
        controller.refresh()
        controller.refresh() // Busy refreshes must not start competing connections.
        advanceUntilIdle()
        assertEquals(2, reconnects)
        assertEquals("/repo", controller.state.value.snapshot?.root)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.busy)
    }

    @Test fun commentsKeepOriginalSnapshotAndAreScopedByEndpointAndWorktree() = runTest {
        val store = MemoryProjectStore()
        var content = "val x = 1\n"
        val runner = TmuxCommandRunner { command ->
            val output = when {
                command.contains("rev-parse --show-toplevel") -> if (command.contains("/other")) "/other\n" else "/repo\n"
                command.contains("rev-parse --verify") -> "a".repeat(40) + "\n"
                command.contains("symbolic-ref") -> "main\n"
                command.contains("status --") -> "?? file.kt\u0000"
                command.contains("ls-files") -> "file.kt\u0000"
                command.contains("p=\$(realpath") -> content
                else -> ""
            }
            TmuxExecOutput(output.toByteArray(), 0)
        }
        val controller = ProjectController(this, { runner }, store, StandardTestDispatcher(testScheduler))
        controller.open(10, "Host", "endpoint-a")
        controller.load("/repo")
        advanceUntilIdle()
        controller.openFile("file.kt")
        advanceUntilIdle()
        controller.addFeedback(0, 0, "Keep this simple")
        val original = controller.state.value.feedback.single()
        content = "val x = 2\n"
        controller.openFile("file.kt")
        advanceUntilIdle()
        assertNotEquals(original.revision, controller.state.value.document!!.revision)
        assertEquals("val x = 1", controller.state.value.feedback.single().code)
        assertTrue(projectFeedbackText("/repo", controller.state.value.feedback).contains("val x = 1"))
        controller.close()
        controller.open(11, "Host", "endpoint-a")
        advanceUntilIdle()
        assertEquals(original, controller.state.value.feedback.single())
        controller.load("/other")
        advanceUntilIdle()
        assertTrue(controller.state.value.feedback.isEmpty())
        controller.open(12, "Other host", "endpoint-b")
        controller.load("/repo")
        advanceUntilIdle()
        assertTrue(controller.state.value.feedback.isEmpty())
    }

    @Test fun closingBeforeReadRunsCannotReopenProjectOrReadAnotherSession() = runTest {
        var reads = 0
        val controller = ProjectController(this, {
            reads++
            error("Should not run")
        }, MemoryProjectStore(), StandardTestDispatcher(testScheduler))
        controller.open(1, "One", "one")
        controller.load("/repo")
        controller.close()
        advanceUntilIdle()
        assertEquals(0, reads)
        assertNull(controller.state.value.sessionId)
        controller.open(2, "Two", "two")
        advanceUntilIdle()
        assertEquals(2L, controller.state.value.sessionId)
        assertNull(controller.state.value.snapshot)
    }

    @Test fun feedbackCannotInjectTerminalControlSequences() {
        val item = ProjectFeedback("id", "file", 1, 1, false, "x\u001b[201~", "\u0000context", "hash", "fix\u0007")
        val text = projectFeedbackText("/repo", listOf(item))
        assertFalse(text.any { it.isISOControl() && it != '\n' && it != '\t' })
        assertTrue(text.contains("My feedback:"))
    }

    @Test fun fileBrowserGroupsAndCompactsDirectoriesWithoutRepeatingParentPaths() {
        val files = listOf("README.md", "app/src/main/A.kt", "app/src/main/B.kt", "docs/a.md")
        val roots = projectTreeEntries(files)
        assertEquals(listOf("app/src/main", "docs", "README.md"), roots.map { it.path })
        assertEquals("app/src/main", roots.first().label)
        val changed = projectTreeEntries(files, expandAll = true)
        assertEquals(listOf("app/src/main", "app/src/main/A.kt", "app/src/main/B.kt", "docs", "docs/a.md", "README.md"), changed.map { it.path })
        assertEquals(1, changed[1].depth)
        assertEquals("A.kt", changed[1].label)
        assertFalse(projectTreeEntries(files, collapsed = setOf("app/src/main"), expandAll = true).any { it.path.endsWith("A.kt") })
        val replaced = projectTreeEntries(listOf("a", "a/new.kt"), expandAll = true)
        assertEquals(3, replaced.size)
        assertEquals(2, replaced.count { it.path == "a" })
    }

    @Test fun oneHostKeepsMultipleProjectDocumentsAndDraftsAndDeduplicatesTheirRoots() = runTest {
        var root = "/repo"
        val runner = TmuxCommandRunner { command ->
            val output = when {
                command.contains("rev-parse --show-toplevel") -> "$root\n"
                command.contains("rev-parse --verify") -> "a".repeat(40)
                command.contains("symbolic-ref") -> "main"
                command.contains("status --") -> "?? file.kt\u0000"
                command.contains("ls-files") -> "file.kt\u0000"
                command.contains("p=\$(realpath") -> "val x = 1\n"
                else -> ""
            }
            TmuxExecOutput(output.toByteArray(), 0)
        }
        val store = MemoryProjectStore()
        val controller = ProjectController(this, { runner }, store, StandardTestDispatcher(testScheduler),
            locationsFor = { listOf(com.yanjiyu.terminalspike.connection.RemoteProjectLocation("/repo/subfolder", "Workspace", true)) })
        controller.open(10, "Host", "endpoint")
        advanceUntilIdle()
        assertEquals("/repo", controller.state.value.snapshot?.root)
        val first = controller.state.value.tabId
        controller.openFile("file.kt")
        advanceUntilIdle()
        controller.addFeedback(0, 0, "Keep the first project comment")
        controller.newProject()
        advanceUntilIdle()
        root = "/other"
        controller.load("/other")
        advanceUntilIdle()
        val second = controller.state.value.tabId
        assertNotEquals(first, second)
        assertEquals(2, controller.tabs.value.size)
        assertTrue(controller.state.value.feedback.isEmpty())
        controller.selectTab(first)
        assertEquals("file.kt", controller.state.value.document?.path)
        assertEquals("Keep the first project comment", controller.state.value.feedback.single().message)
        root = "/repo"
        controller.open(10, "Host", "endpoint")
        advanceUntilIdle()
        assertEquals(first, controller.state.value.tabId)
        assertEquals(2, controller.tabs.value.size)
        assertEquals("file.kt", controller.state.value.document?.path)
        controller.closeTab(second)
        assertEquals(first, controller.state.value.tabId)
        assertEquals(1, controller.tabs.value.size)
    }

    @Test fun openingADifferentDirectoryPreservesTheExistingProjectTab() = runTest {
        var root = "/one"
        val runner = TmuxCommandRunner { command ->
            TmuxExecOutput(when {
                command.contains("rev-parse --show-toplevel") -> root
                command.contains("symbolic-ref") -> "main"
                else -> ""
            }.toByteArray(), if (command.contains("rev-parse --verify")) 1 else 0)
        }
        val controller = ProjectController(this, { runner }, MemoryProjectStore(), StandardTestDispatcher(testScheduler))
        controller.open(1, "host", "same-host")
        controller.load(root)
        advanceUntilIdle()
        val first = controller.state.value.tabId
        root = "/two"
        controller.load(root)
        advanceUntilIdle()
        assertEquals(listOf("/one", "/two"), controller.tabs.value.map { it.snapshot?.root })
        controller.selectTab(first)
        assertEquals("/one", controller.state.value.snapshot?.root)
        controller.closeTab(first)
        assertEquals("/two", controller.state.value.snapshot?.root)
    }

}

private class MemoryProjectStore : ProjectDraftStore {
    private val paths = mutableMapOf<String, String>()
    private val drafts = mutableMapOf<Pair<String, String>, List<ProjectFeedback>>()
    override fun directory(endpoint: String) = paths[endpoint].orEmpty()
    override fun rememberDirectory(endpoint: String, directory: String) { paths[endpoint] = directory }
    override fun load(endpoint: String, root: String) = drafts[endpoint to root].orEmpty()
    override fun save(endpoint: String, root: String, feedback: List<ProjectFeedback>) { drafts[endpoint to root] = feedback }
}
