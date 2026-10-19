package com.yanjiyu.terminalspike.ui.project

import com.yanjiyu.terminalspike.connection.TmuxCommandRunner
import com.yanjiyu.terminalspike.connection.quotePosixShellArgument
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class ProjectChange(val path: String, val status: String, val originalPath: String? = null)
internal data class ProjectSnapshot(
    val root: String,
    val branch: String,
    val head: String?,
    val changes: List<ProjectChange>,
    val files: List<String>,
)
internal enum class ProjectLineKind { CONTEXT, ADDED, DELETED }
internal data class ProjectLine(val number: Int, val text: String, val kind: ProjectLineKind)
internal data class ProjectDocument(
    val path: String,
    val revision: String,
    val lines: List<ProjectLine>,
    val changeStarts: List<Int>,
    val additions: Int,
    val deletions: Int,
    val metadata: List<String> = emptyList(),
)

/** The remote Git executable is authoritative. No checkout, index write, textconv or external diff. */
internal class ProjectReader(private val runner: TmuxCommandRunner) {
    fun snapshot(directory: String): ProjectSnapshot {
        require(directory.startsWith('/') && '\u0000' !in directory) { "Enter an absolute remote repository path." }
        val root = read(git(directory, "rev-parse --show-toplevel")).removeSuffix("\n")
        require(root.startsWith('/')) { "This directory is not in a Git repository." }
        val headOutput = runner.run(git(root, "rev-parse --verify HEAD"))
        val head = headOutput.stdout.toString(Charsets.UTF_8).trim().takeIf {
            headOutput.exitStatus == 0 && it.matches(Regex("[0-9a-f]{40,64}"))
        }
        val branchOutput = runner.run(git(root, "symbolic-ref --quiet --short HEAD"))
        val branch = if (branchOutput.exitStatus == 0) branchOutput.stdout.toString(Charsets.UTF_8).trim()
            else head?.take(8) ?: "HEAD"
        val changes = parseProjectStatus(read(git(root, "status --porcelain=v1 -z --untracked-files=all"), allowNul = true))
        val files = read(git(root, "ls-files -z --cached --others --exclude-standard"), allowNul = true)
            .split('\u0000').filter(String::isNotEmpty).distinct().sorted()
        require(files.size <= MAX_PROJECT_FILES && changes.size <= MAX_PROJECT_FILES) {
            "This repository has too many files to browse (limit $MAX_PROJECT_FILES)."
        }
        (files + changes.map { it.path }).forEach(::requireProjectPath)
        return ProjectSnapshot(root, branch, head, changes, files)
    }

    fun document(snapshot: ProjectSnapshot, path: String): ProjectDocument {
        requireProjectPath(path)
        require(path in snapshot.files || snapshot.changes.any { it.path == path }) { "Refresh the file list first." }
        val deleted = snapshot.changes.firstOrNull { it.path == path }?.status?.contains('D') == true
        val content = if (deleted) "" else readWorkingFile(snapshot.root, path)
        val patch = if (snapshot.head == null) "" else read(git(snapshot.root,
            "diff --no-ext-diff --no-textconv --no-renames --unified=0 ${snapshot.head} -- ${quotePosixShellArgument(path)}"))
        // The agent may write while we read. Keep the visible snapshot stable, or ask for a retry.
        if (!deleted) check(content == readWorkingFile(snapshot.root, path)) {
            "This file changed while loading. Open it again."
        }
        val untracked = snapshot.changes.any { it.path == path && it.status == "??" }
        return projectDocument(path, content, patch, snapshot.head == null || untracked)
    }

    private fun readWorkingFile(root: String, path: String): String {
        val absolute = root.trimEnd('/') + "/" + path
        val quoted = quotePosixShellArgument(absolute)
        // Reject symlinks escaping the selected worktree, including symlinked parent directories.
        val command = "test ! -L $quoted && p=\$(realpath -e -- $quoted) && " +
            "case \"\$p\" in ${quotePosixShellArgument(root.trimEnd('/') + "/")}*) " +
            "test -f \"\$p\" && cat -- \"\$p\" ;; *) exit 1 ;; esac"
        return read(command, "Cannot read this file. It may be deleted, a directory, or a symbolic link.")
    }

    private fun read(command: String, failure: String = "Cannot read this repository. Check the path, Git installation and connection.", allowNul: Boolean = false): String {
        val output = runner.run(command)
        check(output.exitStatus == 0) { failure }
        require(output.stdout.size <= MAX_PROJECT_BYTES) { "This file or repository exceeds the 4 MiB reading limit." }
        require(0.toByte() !in output.stdout || allowNul) { "Binary files cannot be shown as code." }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.stdout)).toString()
    }
}

private fun git(root: String, arguments: String): String =
    "GIT_OPTIONAL_LOCKS=0 GIT_LITERAL_PATHSPECS=1 git --no-pager -c core.fsmonitor=false -c color.ui=false " +
        "-C ${quotePosixShellArgument(root)} $arguments"

internal fun requireProjectPath(path: String) {
    require(path.isNotEmpty() && !path.startsWith('/') && '\u0000' !in path &&
        path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Invalid project file path." }
}

internal fun parseProjectStatus(output: String): List<ProjectChange> {
    val fields = output.split('\u0000')
    val result = mutableListOf<ProjectChange>()
    var index = 0
    while (index < fields.size) {
        val entry = fields[index++]
        if (entry.isEmpty()) continue
        require(entry.length >= 4 && entry[2] == ' ') { "Invalid Git status response." }
        val status = entry.take(2)
        val path = entry.substring(3)
        requireProjectPath(path)
        val original = if ('R' in status || 'C' in status) {
            require(index < fields.size && fields[index].isNotEmpty()) { "Incomplete Git rename response." }
            fields[index++].also(::requireProjectPath)
        } else null
        result += ProjectChange(path, status, original)
    }
    return result
}

internal fun projectDocument(path: String, content: String, patch: String, allAdded: Boolean): ProjectDocument {
    require(!patch.lineSequence().any { it.startsWith("Binary files ") }) { "Binary files cannot be shown as code." }
    val current = sourceLines(content)
    require(current.none { it.length > 16_000 }) { "This file contains a line longer than the 16,000 character reading limit." }
    require(current.size <= MAX_PROJECT_LINES) { "This file exceeds the $MAX_PROJECT_LINES line limit." }
    val added = mutableSetOf<Int>()
    val removed = mutableMapOf<Int, MutableList<ProjectLine>>()
    val hunk = Regex("^@@ -([0-9]+)(?:,([0-9]+))? \\+([0-9]+)(?:,([0-9]+))? @@.*$")
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    val changeNumbers = mutableListOf<Int>()
    for (line in patch.lineSequence()) {
        val match = hunk.matchEntire(line)
        if (match != null) {
            oldLine = match.groupValues[1].toInt()
            newLine = match.groupValues[3].toInt()
            // A zero-length new range inserts deletions AFTER the stated new line.
            if (match.groupValues[4] == "0") newLine += 1
            changeNumbers += newLine
            inHunk = true
        } else if (inHunk) {
            when {
                line.startsWith("diff --git ") -> inHunk = false
                line.startsWith('-') -> {
                    removed.getOrPut(newLine) { mutableListOf() }
                        .add(ProjectLine(oldLine++, line.drop(1), ProjectLineKind.DELETED))
                }
                line.startsWith('+') -> { added += newLine; newLine++ }
                line.startsWith(' ') -> { oldLine++; newLine++ }
            }
        }
    }
    if (allAdded) {
        added += 1..current.size
        if (current.isNotEmpty()) changeNumbers += 1
    }
    val lines = buildList {
        current.forEachIndexed { index, text ->
            addAll(removed[index + 1].orEmpty())
            add(ProjectLine(index + 1, text, if (index + 1 in added) ProjectLineKind.ADDED else ProjectLineKind.CONTEXT))
        }
        addAll(removed[current.size + 1].orEmpty())
    }
    require(lines.none { it.text.length > 16_000 }) { "This file contains a line longer than the 16,000 character reading limit." }
    require(lines.size <= MAX_PROJECT_LINES) { "The full file and deleted code exceed the $MAX_PROJECT_LINES line limit." }
    val starts = changeNumbers.mapNotNull { number ->
        lines.indexOfFirst { it.number == number && it.kind != ProjectLineKind.DELETED }
            .takeIf { it >= 0 }?.let { index ->
                var start = index
                while (start > 0 && lines[start - 1].kind == ProjectLineKind.DELETED) start--
                start
            } ?: lines.indexOfFirst { it.kind == ProjectLineKind.DELETED && it in removed[number].orEmpty() }
                .takeIf { it >= 0 }
    }.distinct().sorted()
    return ProjectDocument(path, projectRevision(content + "\u0000" + patch), lines, starts,
        lines.count { it.kind == ProjectLineKind.ADDED }, lines.count { it.kind == ProjectLineKind.DELETED },
        patch.lineSequence().filter { it.startsWith("old mode ") || it.startsWith("new mode ") ||
            it.startsWith("new file mode ") || it.startsWith("deleted file mode ") }.toList())
}

internal fun projectRevision(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun sourceLines(content: String): List<String> = if (content.isEmpty()) emptyList()
    else content.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

internal const val MAX_PROJECT_BYTES = 4 * 1024 * 1024
internal const val MAX_PROJECT_FILES = 25_000
internal const val MAX_PROJECT_LINES = 30_000

internal data class ProjectTreeEntry(val path: String, val label: String, val directory: Boolean, val depth: Int, val expanded: Boolean)

/** Compress single-directory chains like VS Code, while preserving real directory boundaries. */
internal fun projectTreeEntries(
    files: List<String>,
    expanded: Set<String> = emptySet(),
    collapsed: Set<String> = emptySet(),
    expandAll: Boolean = false,
): List<ProjectTreeEntry> {
    class Node(val path: String, val name: String, val directory: Boolean) {
        val children = sortedMapOf<String, Node>()
    }
    val root = Node("", "", true)
    files.forEach { path ->
        val segments = path.split('/')
        var parent = root
        segments.forEachIndexed { index, name ->
            val directory = index < segments.lastIndex
            parent = parent.children.getOrPut((if (directory) "d:" else "f:") + name) {
                Node(if (parent.path.isEmpty()) name else "${parent.path}/$name", name, directory)
            }
        }
    }
    return buildList {
        fun visit(parent: Node, depth: Int) {
            parent.children.values.sortedWith(compareByDescending<Node> { it.directory }.thenBy { it.name }).forEach { child ->
                var node = child
                var label = child.name
                while (node.directory && node.children.size == 1 && node.children.values.single().directory) {
                    node = node.children.values.single()
                    label += "/${node.name}"
                }
                val open = node.directory && if (expandAll) node.path !in collapsed else node.path in expanded
                add(ProjectTreeEntry(node.path, label, node.directory, depth, open))
                if (open) visit(node, depth + 1)
            }
        }
        visit(root, 0)
    }
}
