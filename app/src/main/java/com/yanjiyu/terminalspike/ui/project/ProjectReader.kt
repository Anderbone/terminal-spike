package com.yanjiyu.terminalspike.ui.project

import com.yanjiyu.terminalspike.connection.TmuxCommandRunner
import com.yanjiyu.terminalspike.connection.quotePosixShellArgument
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class ProjectChange(val path: String, val status: String, val originalPath: String? = null)
internal enum class ProjectDiffScope { ALL, UNSTAGED, STAGED, BRANCH }
internal data class ProjectComparison(val scope: ProjectDiffScope = ProjectDiffScope.ALL, val base: String = "main")
internal data class ProjectSnapshot(
    val root: String,
    val branch: String,
    val head: String?,
    val changes: List<ProjectChange>,
    val files: List<String>,
    val comparison: ProjectComparison = ProjectComparison(),
    val baseline: String? = head,
)

/** The remote Git executable is authoritative. No checkout, index write, textconv or external diff. */
internal class ProjectReader(private val runner: TmuxCommandRunner) {
    fun snapshot(directory: String, comparison: ProjectComparison = ProjectComparison()): ProjectSnapshot {
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
        val status = parseProjectStatus(read(git(root, "status --porcelain=v1 -z --untracked-files=all"), allowNul = true))
        val baseline = if (comparison.scope == ProjectDiffScope.BRANCH) {
            require(comparison.base.isNotBlank() && comparison.base.length <= 256 && comparison.base.none(Char::isISOControl)) {
                "Enter a base branch or commit."
            }
            val ref = read(git(root, "rev-parse --verify --end-of-options ${quotePosixShellArgument(comparison.base + "^{commit}")}"),
                "Cannot resolve the base branch or commit.").trim()
            require(ref.matches(Regex("[0-9a-f]{40,64}"))) { "Invalid base commit." }
            check(head != null) { "Commit this repository before comparing branches." }
            read(git(root, "merge-base $ref $head"), "The branches do not share a common ancestor.").trim().also {
                require(it.matches(Regex("[0-9a-f]{40,64}"))) { "Invalid merge base." }
            }
        } else head
        val changes = when (comparison.scope) {
            ProjectDiffScope.ALL -> status
            ProjectDiffScope.UNSTAGED -> status.filter { it.status[1] != ' ' }
            ProjectDiffScope.STAGED -> status.filter { it.status[0] != ' ' && it.status != "??" }
            ProjectDiffScope.BRANCH -> {
                val fields = read(git(root, "diff --no-ext-diff --no-textconv --no-renames --name-status -z $baseline --"), allowNul = true)
                    .split('\u0000').filter(String::isNotEmpty)
                require(fields.size % 2 == 0) { "Invalid branch diff response." }
                (status.filter { it.status == "??" } + fields.chunked(2).map { ProjectChange(it[1], it[0]) })
                    .distinctBy { it.path }
            }
        }
        val listing = if (comparison.scope == ProjectDiffScope.STAGED) "ls-files -z --cached"
            else "ls-files -z --cached --others --exclude-standard"
        val files = read(git(root, listing), allowNul = true)
            .split('\u0000').filter(String::isNotEmpty).distinct().sorted()
        require(files.size <= MAX_PROJECT_FILES && changes.size <= MAX_PROJECT_FILES) {
            "This repository has too many files to browse (limit $MAX_PROJECT_FILES)."
        }
        (files + changes.map { it.path }).forEach(::requireProjectPath)
        return ProjectSnapshot(root, branch, head, changes, files, comparison, baseline)
    }

    fun document(snapshot: ProjectSnapshot, path: String): ProjectDocument {
        requireProjectPath(path)
        require(path in snapshot.files || snapshot.changes.any { it.path == path }) { "Refresh the file list first." }
        val scope = snapshot.comparison.scope
        val status = snapshot.changes.firstOrNull { it.path == path }?.status.orEmpty()
        val untracked = snapshot.changes.any { it.path == path && it.status == "??" }
        val deleted = when (scope) {
            ProjectDiffScope.STAGED -> status.firstOrNull() == 'D'
            ProjectDiffScope.UNSTAGED -> status.getOrNull(1) == 'D'
            ProjectDiffScope.BRANCH -> status == "D"
            ProjectDiffScope.ALL -> !untracked && (status.getOrNull(1) == 'D' || status == "D ")
        }
        fun readContent(): String = if (deleted) "" else if (scope == ProjectDiffScope.STAGED) {
            read(git(snapshot.root, "show ${quotePosixShellArgument(":" + path)}"), "This file is not in the index. Choose another comparison.")
        } else readWorkingFile(snapshot.root, path)
        val content = readContent()
        val baseline = when (scope) {
            ProjectDiffScope.STAGED -> "--cached ${snapshot.head.orEmpty()}"
            ProjectDiffScope.UNSTAGED -> ""
            else -> snapshot.baseline.orEmpty()
        }
        val patch = if (snapshot.head == null && scope != ProjectDiffScope.STAGED && scope != ProjectDiffScope.UNSTAGED) ""
            else read(git(snapshot.root,
                "diff --no-ext-diff --no-textconv --no-renames --unified=0 $baseline -- ${quotePosixShellArgument(path)}"))
        // The agent may write while we read. Keep the visible snapshot stable, or ask for a retry.
        if (!deleted) check(content == readContent()) {
            "This file changed while loading. Open it again."
        }
        return projectDocument(path, content, patch,
            (snapshot.head == null && scope != ProjectDiffScope.UNSTAGED) || untracked)
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

internal const val MAX_PROJECT_BYTES = 4 * 1024 * 1024
internal const val MAX_PROJECT_FILES = 25_000
internal const val MAX_PROJECT_LINES = 30_000
