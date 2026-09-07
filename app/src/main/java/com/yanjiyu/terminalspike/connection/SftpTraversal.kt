package com.yanjiyu.terminalspike.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SftpTraversalLimits(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxDepth >= 0) { "SFTP traversal depth must not be negative." }
        require(maxEntries > 0) { "SFTP traversal entry limit must be positive." }
    }

    private companion object {
        const val DEFAULT_MAX_DEPTH = 64
        const val DEFAULT_MAX_ENTRIES = 10_000
    }
}

internal data class SftpTreeNode<T>(
    val value: T,
    val isDirectory: Boolean,
    val directoryIdentity: String? = null,
)

/** Iterative, cancellation-aware depth-first traversal of one untrusted tree. */
internal suspend fun <T> walkSftpTree(
    root: SftpTreeNode<T>,
    limits: SftpTraversalLimits = SftpTraversalLimits(),
    children: suspend (T) -> List<SftpTreeNode<T>>,
    onEnter: suspend (T, depth: Int) -> Unit,
    onLeaveDirectory: suspend (T, depth: Int) -> Unit = { _, _ -> },
) {
    val pending = ArrayDeque<SftpWalkStep<T>>()
    val visitedDirectories = mutableSetOf<String>()
    var entryCount = 0
    pending.addLast(SftpWalkStep.Enter(root, 0))
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        when (val step = pending.removeLast()) {
            is SftpWalkStep.Leave -> onLeaveDirectory(step.node.value, step.depth)
            is SftpWalkStep.Enter -> {
                if (step.depth > limits.maxDepth) {
                    throw SftpTraversalException("The selected folder is nested too deeply.")
                }
                entryCount += 1
                if (entryCount > limits.maxEntries) {
                    throw SftpTraversalException("The selected folder contains too many items.")
                }
                if (step.node.isDirectory) {
                    val identity = requireNotNull(step.node.directoryIdentity) {
                        "A directory traversal identity is required."
                    }
                    if (!visitedDirectories.add(identity)) {
                        throw SftpTraversalException("The selected folder contains a directory cycle.")
                    }
                }
                onEnter(step.node.value, step.depth)
                if (step.node.isDirectory) {
                    currentCoroutineContext().ensureActive()
                    val childNodes = children(step.node.value)
                    pending.addLast(SftpWalkStep.Leave(step.node, step.depth))
                    childNodes.asReversed().forEach { child ->
                        pending.addLast(SftpWalkStep.Enter(child, step.depth + 1))
                    }
                }
            }
        }
    }
}

internal class SftpTraversalException(message: String) : IllegalArgumentException(message)

private sealed interface SftpWalkStep<T> {
    val node: SftpTreeNode<T>
    val depth: Int

    data class Enter<T>(
        override val node: SftpTreeNode<T>,
        override val depth: Int,
    ) : SftpWalkStep<T>

    data class Leave<T>(
        override val node: SftpTreeNode<T>,
        override val depth: Int,
    ) : SftpWalkStep<T>
}
