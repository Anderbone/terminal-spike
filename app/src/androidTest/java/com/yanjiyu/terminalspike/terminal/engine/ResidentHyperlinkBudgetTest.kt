package com.yanjiyu.terminalspike.terminal.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Safe parser/model device gate: 1000 cells and bounded metadata, never an Android OOM test. */
@RunWith(AndroidJUnit4::class)
class ResidentHyperlinkBudgetTest {
    @Test fun metadataOverflowKeepsTextAndReusesReleasedCapacity() {
        val engine = VtTerminalEngine(500, 4)
        val frame = ResidentHyperlinkFixture.fill(engine)
        assertEquals(listOf("x".repeat(500), "x".repeat(500), "", ""), frame.screen.map { it.text })
        assertTrue(ResidentHyperlinkFixture.residentLinks(engine).isNotEmpty())
        assertTrue(ResidentHyperlinkFixture.residentLinks(engine).size < 1_000)
        assertTrue(ResidentHyperlinkFixture.identityCharge(engine) <= ResidentHyperlinkFixture.BUDGET)
        engine.accept("\u001b[2J\u001b[H".encodeToByteArray())
        val fresh = engine.accept(ResidentHyperlinkFixture.linked(50_000, "界").encodeToByteArray())
        val run = fresh.screen.first().runs.first()
        assertEquals("界", run.text)
        assertEquals(2, run.columnWidth)
        assertNotNull(run.hyperlink)
        val links = ResidentHyperlinkFixture.residentLinks(engine)
        assertEquals(2, links.size)
        assertTrue("Both glyph columns must own the same admitted link", links.first() === links.last())
    }
}
