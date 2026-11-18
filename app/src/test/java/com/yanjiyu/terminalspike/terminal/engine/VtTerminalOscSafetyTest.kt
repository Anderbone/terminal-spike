package com.yanjiyu.terminalspike.terminal.engine

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VtTerminalOscSafetyTest {
    @Test
    fun supplementaryFormatsCannotReplaceTitlesInWholePackets() {
        assertTitleRejection(oneByteChunks = false)
    }

    @Test
    fun supplementaryFormatsCannotReplaceTitlesAcrossEveryUtf8ByteBoundary() {
        assertTitleRejection(oneByteChunks = true)
    }

    @Test
    fun supplementaryFormatsCannotEnterOsc8Uris() {
        for (format in formats) {
            assertHyperlinkRejection("id=docs;https://example.test/a${format}b")
        }
    }

    @Test
    fun supplementaryFormatsCannotEnterOsc8Identifiers() {
        for (format in formats) {
            assertHyperlinkRejection("id=doc${format}s;https://example.test/help")
        }
    }

    @Test
    fun supplementaryFormatsRejectWholeNotificationsLikeBmpFormats() {
        for (format in formats) {
            for (oneByteChunks in listOf(false, true)) {
                val engine = VtTerminalEngine(columns = 20, rows = 2)
                val updates = accept(engine, "\u001b]9;Build${format} done\u0007OK", oneByteChunks)
                assertTrue(updates.flatMap { it.terminalNotifications }.isEmpty())
                assertEquals("OK", updates.last().screen[0].text)
                assertTrue(updates.all { it.bellCount == 0 })
            }
        }
    }

    @Test
    fun visibleSupplementaryTextKeepsTitlesLinksAndNotificationsAcrossChunks() {
        val title = "build 🚀 שלום"
        val uri = "https://example.test/中文/🚀"
        for (oneByteChunks in listOf(false, true)) {
            val engine = VtTerminalEngine(columns = 20, rows = 2)
            val updates = accept(
                engine,
                "\u001b]2;$title\u0007\u001b]8;id=résumé🚀;$uri\u001b\\link" +
                    "\u001b]8;;\u0007\u001b]9;done 🚀\u0007",
                oneByteChunks,
            )
            val last = updates.last()
            assertEquals(title, last.terminalTitle)
            assertEquals("link", last.screen[0].text)
            assertEquals(uri, last.screen[0].runs.single().hyperlink?.uri)
            assertEquals("résumé🚀", last.screen[0].runs.single().hyperlink?.id)
            assertEquals(listOf("done 🚀"), updates.flatMap { it.terminalNotifications })
            assertTrue(updates.all { it.bellCount == 0 && it.responses.isEmpty() })
        }
    }

    @Test
    fun osc52DecodedSupplementaryFormatsRemainRejectedAcrossChunks() {
        for (format in formats) {
            val encoded = Base64.getEncoder().encodeToString("clip${format}text".encodeToByteArray())
            for (oneByteChunks in listOf(false, true)) {
                val engine = VtTerminalEngine(columns = 20, rows = 2)
                val updates = accept(engine, "\u001b]52;c;$encoded\u0007OK", oneByteChunks)
                assertTrue(updates.flatMap { it.remoteClipboardRequests }.isEmpty())
                assertTrue(updates.all { it.responses.isEmpty() && it.bellCount == 0 })
                assertEquals("OK", updates.last().screen[0].text)
            }
        }
    }

    private fun assertTitleRejection(oneByteChunks: Boolean) {
        for (format in formats) {
            for (selector in listOf("0", "2")) {
                for (terminator in listOf("\u0007", "\u001b\\", "\u009c")) {
                    val engine = VtTerminalEngine(columns = 20, rows = 2)
                    engine.accept("\u001b]2;trusted\u0007".encodeToByteArray())
                    val updates = accept(engine, "\u001b]$selector;bad${format}title${terminator}OK", oneByteChunks)
                    assertTrue(updates.all { it.terminalTitle == "trusted" })
                    assertTrue(updates.all { it.bellCount == 0 })
                    assertEquals("OK", updates.last().screen[0].text)
                    assertEquals("recovered", engine.accept("\u001b]2;recovered\u0007".encodeToByteArray()).terminalTitle)
                }
            }
        }
    }

    private fun assertHyperlinkRejection(payload: String) {
        for (oneByteChunks in listOf(false, true)) {
            val engine = VtTerminalEngine(columns = 20, rows = 2)
            val updates = accept(engine, "\u001b]8;$payload\u001b\\label\u001b]8;;\u0007 plain", oneByteChunks)
            assertTrue(updates.flatMap { it.screen }.flatMap { it.runs }.all { it.hyperlink == null })
            assertEquals("label plain", updates.last().screen[0].text)
            assertTrue(updates.all { it.bellCount == 0 && it.responses.isEmpty() })
            val valid = engine.accept("\u001b]8;;https://example.test/good\u0007x".encodeToByteArray())
            assertEquals("https://example.test/good", valid.screen[0].runs.last().hyperlink?.uri)
        }
    }

    private fun accept(
        engine: VtTerminalEngine,
        text: String,
        oneByteChunks: Boolean,
    ): List<TerminalFrameUpdate> {
        val bytes = text.encodeToByteArray()
        return if (oneByteChunks) bytes.map { engine.accept(byteArrayOf(it)) } else listOf(engine.accept(bytes))
    }

    private val formats = listOf("\uDB40\uDC01", "\uDB40\uDC20", "\uD834\uDD73")
}
