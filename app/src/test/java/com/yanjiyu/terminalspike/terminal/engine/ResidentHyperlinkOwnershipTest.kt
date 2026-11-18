package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check real cell ownership, including mutations whose final rendered rows can hide refcount drift. */
class ResidentHyperlinkOwnershipTest {
    @Test fun registryReferencesExactlyMatchResidentCellsAcrossMutations() {
        val engine = VtTerminalEngine(500, 4)
        ResidentHyperlinkFixture.fill(engine)
        assertOwnership(engine)
        for (command in listOf(
            "\u001b[3;1H" + ResidentHyperlinkFixture.linked(0, "界"),
            "\u001b[3;2H\u001b[1P", "\u001b[3;1H\u001b[1@", "\u001b[3;1H\u001b[1X",
            "\u001b[H\u001b[3P", "\u001b[H\u001b[2@", "\u001b[H\u001b[1M", "\u001b[H\u001b[1L",
            "\u001b[1S", "\u001b[1T", "\u001b[?47h" + ResidentHyperlinkFixture.linked(30_000),
            "\u001b[?47l", "\u001b[2J\u001b[?1049h", "\u001b[?1049l",
        )) {
            engine.accept(command.encodeToByteArray())
            assertOwnership(engine)
        }
        ResidentHyperlinkFixture.fill(engine, first = 40_000)
        engine.resize(137, 3)
        assertOwnership(engine)
        engine.resize(1, 1)
        assertOwnership(engine)
        engine.reset()
        assertOwnership(engine)
    }

    @Test fun ordinaryAsciiDoesNotAllocateThePoolMap() {
        val engine = VtTerminalEngine(80, 4)
        engine.accept("ascii\r\n\u001b[2J\u001b[Habc\u001b[1@\u001b[1P".encodeToByteArray())
        engine.resize(90, 6)
        assertOwnership(engine)
        val pool = field(engine, "residentHyperlinks")
        assertNull(fieldOrNull(pool, "entries"))
    }

    @Test fun fourEnginesBoundOnlyTheirCombinedResidentMetadataPayload() {
        val engines = List(4) { VtTerminalEngine(500, 4) }
        engines.forEachIndexed { index, engine ->
            ResidentHyperlinkFixture.fill(engine, first = index * 10_000)
            assertOwnership(engine)
        }
        val charged = engines.sumOf { field(field(it, "residentHyperlinks"), "chargedBytes") as Long }
        assertTrue("This is a metadata bound, not a whole-process heap claim", charged <= 4 * ResidentHyperlinkFixture.BUDGET)
        engines.forEach { engine ->
            engine.reset()
            assertOwnership(engine)
        }
    }

    private fun assertOwnership(engine: VtTerminalEngine) {
        val cellsByIdentity = IdentityHashMap<TerminalHyperlink, Int>()
        val cachedLinks = mutableListOf<TerminalHyperlink>()
        for (name in listOf("primary", "alternate")) {
            val screen = field(engine, name)
            @Suppress("UNCHECKED_CAST")
            val cells = field(screen, "cells") as Array<Array<Any>>
            val rowCounts = field(screen, "rowLinkedCells") as IntArray
            cells.forEachIndexed { row, columns ->
                var actual = 0
                columns.forEach { cell -> (fieldOrNull(cell, "hyperlink") as? TerminalHyperlink)?.let { link ->
                    actual++
                    cellsByIdentity[link] = (cellsByIdentity[link] ?: 0) + 1
                } }
                assertEquals("Row accounting must move with the actual row", actual, rowCounts[row])
            }
            (field(screen, "cachedLines") as Array<*>).filterIsInstance<TerminalLine>().forEach { line ->
                line.runs.mapNotNullTo(cachedLinks) { it.hyperlink }
            }
        }
        val pool = field(engine, "residentHyperlinks")
        val entries = fieldOrNull(pool, "entries") as? Map<*, *>
        assertEquals(cellsByIdentity.size, entries?.size ?: 0)
        var charged = 0L
        entries?.forEach { (key, rawEntry) ->
            val entry = requireNotNull(rawEntry)
            val canonical = field(entry, "canonical") as TerminalHyperlink
            assertSame(key, canonical)
            assertTrue("Registry canonical object must be held by actual cells", cellsByIdentity.containsKey(canonical))
            assertEquals(cellsByIdentity[canonical], field(entry, "references"))
            charged += 256L + 2L * (canonical.uri.length + (canonical.id?.length ?: 0))
        }
        cachedLinks.forEach { assertTrue("Cached rows cannot independently retain released links", cellsByIdentity.containsKey(it)) }
        assertEquals(charged, field(pool, "chargedBytes"))
        assertTrue(charged <= ResidentHyperlinkFixture.BUDGET)
    }

    private fun field(owner: Any, name: String): Any = requireNotNull(fieldOrNull(owner, name))
    private fun fieldOrNull(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).run { isAccessible = true; get(owner) }
}
