package com.yanjiyu.terminalspike.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpTraversalTest {
    @Test
    fun visitsExactlyTheConfiguredEntryAndDepthLimitsInStableOrder() = runTest {
        val tree = mapOf("root" to listOf("a"), "a" to listOf("leaf"))
        val visited = mutableListOf<String>()

        walkSftpTree(
            root = node("root", tree),
            limits = SftpTraversalLimits(maxDepth = 2, maxEntries = 3),
            children = { name -> tree[name].orEmpty().map { node(it, tree) } },
            onEnter = { name, _ -> visited += name },
        )

        assertEquals(listOf("root", "a", "leaf"), visited)
    }

    @Test
    fun rejectsTheFirstEntryBeyondTheConfiguredLimit() {
        val tree = mapOf("root" to listOf("a", "b", "c"))

        assertThrows(SftpTraversalException::class.java) {
            runTest {
                walkSftpTree(
                    root = node("root", tree),
                    limits = SftpTraversalLimits(maxEntries = 3),
                    children = { name -> tree[name].orEmpty().map { node(it, tree) } },
                    onEnter = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun rejectsDepthSixtyFiveWithProductionDepthLimit() {
        val tree = (0..65).associate { depth ->
            depth.toString() to if (depth == 65) emptyList() else listOf((depth + 1).toString())
        }

        assertThrows(SftpTraversalException::class.java) {
            runTest {
                walkSftpTree(
                    root = node("0", tree),
                    children = { name -> tree.getValue(name).map { node(it, tree) } },
                    onEnter = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun rejectsRepeatedDirectoryIdentityAsACycle() {
        val tree = mapOf("root" to listOf("alias"), "alias" to emptyList())

        assertThrows(SftpTraversalException::class.java) {
            runTest {
                walkSftpTree(
                    root = SftpTreeNode("root", isDirectory = true, directoryIdentity = "same"),
                    children = { name ->
                        tree[name].orEmpty().map {
                            SftpTreeNode(it, isDirectory = true, directoryIdentity = "same")
                        }
                    },
                    onEnter = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun observesCancellationBetweenEntries() {
        val visited = mutableListOf<String>()

        assertThrows(CancellationException::class.java) {
            runTest {
                walkSftpTree(
                    root = SftpTreeNode("root", true, "root"),
                    children = {
                        listOf(
                            SftpTreeNode("first", false),
                            SftpTreeNode("second", false),
                        )
                    },
                    onEnter = { name, _ ->
                        visited += name
                        if (name == "first") cancel()
                    },
                )
            }
        }
        assertEquals(listOf("root", "first"), visited)
    }

    private fun node(name: String, tree: Map<String, List<String>>): SftpTreeNode<String> =
        SftpTreeNode(
            value = name,
            isDirectory = tree.containsKey(name),
            directoryIdentity = name.takeIf(tree::containsKey),
        )
}
