package com.yanjiyu.terminalspike.ui.project

import java.security.MessageDigest

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

internal fun projectDocument(path: String, content: String, patch: String, allAdded: Boolean): ProjectDocument {
    val current = sourceLines(content)
    val added = mutableSetOf<Int>()
    val removed = mutableMapOf<Int, MutableList<ProjectLine>>()
    val hunk = Regex("^@@ -([0-9]+)(?:,([0-9]+))? \\+([0-9]+)(?:,([0-9]+))? @@.*$")
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    var deletedLines = 0
    val changeNumbers = mutableListOf<Int>()
    for (line in patch.lineSequence()) {
        require(!line.startsWith("Binary files ")) { "Binary files cannot be shown as code." }
        val match = hunk.matchEntire(line)
        if (match != null) {
            oldLine = requireNotNull(match.groupValues[1].toIntOrNull()) { "The diff contains an unsupported line number." }
            newLine = requireNotNull(match.groupValues[3].toIntOrNull()) { "The diff contains an unsupported line number." }
            // A zero-length new range inserts deletions AFTER the stated new line.
            if (match.groupValues[4] == "0") {
                require(newLine < Int.MAX_VALUE) { "The diff contains an unsupported line number." }
                newLine += 1
            }
            require(newLine in 1..current.size + 1) { "The diff contains an unsupported line number." }
            changeNumbers += newLine
            inHunk = true
        } else if (inHunk) {
            when {
                line.startsWith("diff --git ") -> inHunk = false
                line.startsWith('-') -> {
                    require(deletedLines < MAX_PROJECT_LINES - current.size) {
                        "The full file and deleted code exceed the $MAX_PROJECT_LINES line limit."
                    }
                    require(oldLine > 0) { "The diff contains an unsupported line number." }
                    requireProjectLineLength(line.length - 1)
                    deletedLines++
                    removed.getOrPut(newLine) { mutableListOf() }
                        .add(ProjectLine(oldLine++, line.drop(1), ProjectLineKind.DELETED))
                }
                line.startsWith('+') -> {
                    require(added.size < MAX_PROJECT_LINES) { "The diff exceeds the $MAX_PROJECT_LINES line limit." }
                    added += newLine
                    newLine++
                }
                line.startsWith(' ') -> { oldLine++; newLine++ }
            }
        }
    }
    if (allAdded) {
        added += 1..current.size
        if (current.isNotEmpty()) changeNumbers += 1
    }
    // Record each displayed line's first row while assembling it, including preceding deletions.
    // Resolving many independent hunks must not rescan the complete document for each jump.
    val startsByNumber = IntArray(current.size + 2) { -1 }
    val lines = buildList {
        current.forEachIndexed { index, text ->
            startsByNumber[index + 1] = size
            addAll(removed[index + 1].orEmpty())
            add(ProjectLine(index + 1, text, if (index + 1 in added) ProjectLineKind.ADDED else ProjectLineKind.CONTEXT))
        }
        val trailingDeletions = removed[current.size + 1].orEmpty()
        if (trailingDeletions.isNotEmpty()) startsByNumber[current.size + 1] = size
        addAll(trailingDeletions)
    }
    require(lines.size <= MAX_PROJECT_LINES) { "The full file and deleted code exceed the $MAX_PROJECT_LINES line limit." }
    val starts = changeNumbers.mapNotNull { number ->
        startsByNumber.getOrNull(number)?.takeIf { it >= 0 }
    }.distinct().sorted()
    return ProjectDocument(path, projectRevision(content + "\u0000" + patch), lines, starts,
        lines.count { it.kind == ProjectLineKind.ADDED }, lines.count { it.kind == ProjectLineKind.DELETED },
        patch.lineSequence().filter { it.startsWith("old mode ") || it.startsWith("new mode ") ||
            it.startsWith("new file mode ") || it.startsWith("deleted file mode ") }.toList())
}

internal fun projectRevision(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Preserve LF splitting, including literal CR characters, without allocating an unbounded list. */
private fun sourceLines(content: String): List<String> = buildList {
    var start = 0
    while (start < content.length) {
        require(size < MAX_PROJECT_LINES) { "This file exceeds the $MAX_PROJECT_LINES line limit." }
        val newline = content.indexOf('\n', start)
        val end = if (newline < 0) content.length else newline
        requireProjectLineLength(end - start)
        add(content.substring(start, end))
        start = if (newline < 0) content.length else newline + 1
    }
}

private fun requireProjectLineLength(length: Int) {
    require(length <= 16_000) { "This file contains a line longer than the 16,000 character reading limit." }
}
