package com.yanjiyu.terminalspike.terminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VtTerminalResidentHyperlinkTest {
    @Test fun uniqueLinksRespectOneEngineBudgetWithoutLosingTextStyleOrColumns() {
        val engine = VtTerminalEngine(500, 4)
        val frame = engine.accept(("\u001b[31;1m" + buildString {
            repeat(1_000) { append(ResidentHyperlinkFixture.linked(it)) }
        }).encodeToByteArray())
        val reference = VtTerminalEngine(500, 4).accept(("\u001b[31;1m" + "x".repeat(1_000)).encodeToByteArray())
        assertEquals(reference.screen.map { it.text }, frame.screen.map { it.text })
        assertEquals(reference.cursor, frame.cursor)
        frame.screen.take(2).forEach { line ->
            assertTrue(line.runs.all { it.style == reference.screen.first().runs.first().style })
            assertEquals(500, line.runs.sumOf { it.columnWidth })
        }
        val links = ResidentHyperlinkFixture.residentLinks(engine)
        assertTrue("Some bounded metadata must remain useful", links.isNotEmpty())
        assertTrue("Overflow cells become ordinary text", links.size < 1_000)
        assertTrue("Charge actual identity-owned objects, not structural equality", ResidentHyperlinkFixture.identityCharge(engine) <= ResidentHyperlinkFixture.BUDGET)
    }

    @Test fun equalLinksActuallyShareCanonicalObjectsAcrossBothScreens() {
        val engine = VtTerminalEngine(80, 4)
        val uri = ResidentHyperlinkFixture.uri(7)
        repeat(80) { engine.accept(ResidentHyperlinkFixture.linked(it, uri = uri).encodeToByteArray()) }
        engine.accept("\u001b[?1049h".encodeToByteArray())
        repeat(80) { engine.accept(ResidentHyperlinkFixture.linked(it, uri = uri).encodeToByteArray()) }
        val links = ResidentHyperlinkFixture.residentLinks(engine)
        assertEquals(160, links.size)
        links.forEach { assertSame("Cells must retain the canonical object", links.first(), it) }
    }

    @Test fun primaryAndAlternateShareOneBudget() {
        val engine = VtTerminalEngine(500, 2)
        ResidentHyperlinkFixture.fill(engine, count = 450)
        engine.accept("\u001b[?1049h".encodeToByteArray())
        ResidentHyperlinkFixture.fill(engine, first = 1_000, count = 450)
        assertTrue(ResidentHyperlinkFixture.residentLinks(engine).any { it.uri == ResidentHyperlinkFixture.uri(1_000) })
        assertTrue(ResidentHyperlinkFixture.identityCharge(engine) <= ResidentHyperlinkFixture.BUDGET)
    }

    @Test fun clearEraseAndResetReleaseCapacityAndStaleInactiveCaches() {
        val engine = VtTerminalEngine(500, 2)
        ResidentHyperlinkFixture.fill(engine)
        val oldScreen = screen(engine, "primary")
        val frame = engine.accept("\u001b[2J\u001b[H\u001b[?1049h".encodeToByteArray())
        assertTrue(frame.screen.all { it.text.isBlank() })
        val cache = oldScreen.javaClass.getDeclaredField("cachedLines").run { isAccessible = true; get(oldScreen) as Array<*> }
        assertTrue("Erased inactive rows cannot independently retain old links", cache.all { it == null })
        val fresh = engine.accept(ResidentHyperlinkFixture.linked(9_000).encodeToByteArray())
        assertNotNull(fresh.screen.first().runs.first().hyperlink)
        engine.reset()
        assertTrue(ResidentHyperlinkFixture.residentLinks(engine).isEmpty())
        assertNotNull(engine.accept(ResidentHyperlinkFixture.linked(9_001).encodeToByteArray()).screen.first().runs.first().hyperlink)
    }

    @Test fun rowAndCharacterMutationsReleaseTheObjectsTheyRemove() {
        for (mutation in listOf("\u001b[2J\u001b[H", "\u001b[H\u001b[500X", "\u001b[H\u001b[500P", "\u001b[H\u001b[500@", "\u001b[H\u001b[2M", "\u001b[H\u001b[2L", "\u001b[2S", "\u001b[2T")) {
            val engine = VtTerminalEngine(500, 2)
            ResidentHyperlinkFixture.fill(engine)
            engine.accept((mutation + "\u001b[H").encodeToByteArray())
            val frame = engine.accept(ResidentHyperlinkFixture.linked(10_000).encodeToByteArray())
            assertNotNull("Removed cells must free capacity: $mutation", frame.screen.first().runs.first().hyperlink)
            assertTrue(ResidentHyperlinkFixture.identityCharge(engine) <= ResidentHyperlinkFixture.BUDGET)
        }
    }

    @Test fun wideOverflowPreservesBothColumnsAndResizeReleasesDroppedRows() {
        val engine = VtTerminalEngine(500, 4)
        ResidentHyperlinkFixture.fill(engine)
        val frame = engine.accept(("\u001b[3;1H\u001b[32m" + ResidentHyperlinkFixture.linked(20_000, "界")).encodeToByteArray())
        val run = frame.screen[2].runs.first()
        assertEquals("界", run.text)
        assertEquals(2, run.columnWidth)
        val reference = VtTerminalEngine(500, 4).accept("\u001b[3;1H\u001b[32m界".encodeToByteArray())
        assertEquals(reference.screen[2].runs.first().style, run.style)
        assertTrue("New unique link at a full pool must be refused", run.hyperlink == null)
        engine.accept("\u001b[4;1H".encodeToByteArray())
        engine.resize(500, 1)
        assertTrue(ResidentHyperlinkFixture.residentLinks(engine).isEmpty())
        assertNotNull(engine.accept(ResidentHyperlinkFixture.linked(20_001, "界").encodeToByteArray()).screen.first().runs.first().hyperlink)
    }

    @Test fun plainAsciiNeverAllocatesTheMetadataMap() {
        val engine = VtTerminalEngine(80, 4)
        engine.accept("plain ascii\r\n\u001b[2Jhello".encodeToByteArray())
        assertTrue(ResidentHyperlinkFixture.residentLinks(engine).isEmpty())
    }

    private fun screen(engine: VtTerminalEngine, name: String): Any =
        engine.javaClass.getDeclaredField(name).run { isAccessible = true; get(engine) }
}
