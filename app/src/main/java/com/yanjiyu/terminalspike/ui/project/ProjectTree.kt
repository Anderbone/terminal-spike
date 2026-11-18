package com.yanjiyu.terminalspike.ui.project

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
        // Keys sort directories before files, then by name. Keep one iterator per open level.
        val pending = ArrayDeque<Pair<Iterator<Node>, Int>>()
        pending.addLast(root.children.values.iterator() to 0)
        while (pending.isNotEmpty()) {
            val (siblings, depth) = pending.last()
            if (!siblings.hasNext()) {
                pending.removeLast()
                continue
            }
            var node = siblings.next()
            var label = node.name
            while (node.directory && node.children.size == 1 && node.children.values.single().directory) {
                node = node.children.values.single()
                label += "/${node.name}"
            }
            val open = node.directory && if (expandAll) node.path !in collapsed else node.path in expanded
            add(ProjectTreeEntry(node.path, label, node.directory, depth, open))
            if (open) pending.addLast(node.children.values.iterator() to depth + 1)
        }
    }
}
