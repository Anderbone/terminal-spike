package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.terminal.model.TerminalColour
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.random.Random

class VtTerminalEngineTest {
    @Test
    fun textModeBelPublishesBoundedCountsAndMonotonicSequenceAcrossChunks() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val first = engine.accept(byteArrayOf(0x07))
        val second = engine.accept(byteArrayOf(0x07, 'x'.code.toByte(), 0x07))
        val empty = engine.accept(byteArrayOf())

        assertEquals(1, first.bellCount)
        assertEquals(1L, first.bellSequence)
        assertEquals(2, second.bellCount)
        assertEquals(3L, second.bellSequence)
        assertEquals("x", second.screen[0].text)
        assertEquals(0, empty.bellCount)
        assertEquals(3L, empty.bellSequence)
    }

    @Test
    fun oscTerminatorBelIsNotAnAlertAndTextFloodIsCountBounded() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val title = engine.accept("\u001B]2;title\u0007".bytes())
        val flood = engine.accept(ByteArray(2_048) { 0x07.toByte() })

        assertEquals("title", title.terminalTitle)
        assertEquals(0, title.bellCount)
        assertEquals(0L, title.bellSequence)
        assertEquals(1_024, flood.bellCount)
        assertEquals(2_048L, flood.bellSequence)
    }

    @Test
    fun osc9PublishesBoundedOneShotTerminalNotificationsWithoutRenderingText() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val update = engine.accept(
            buildString {
                append("\u001B]9;Codex finished the requested change\u0007")
                repeat(4) { append("\u001B]9;extra-$it\u001B\\") }
            }.bytes(),
        )

        assertEquals(
            listOf(
                "Codex finished the requested change",
                "extra-0",
                "extra-1",
                "extra-2",
            ),
            update.terminalNotifications,
        )
        assertTrue(update.screen.all { it.text.isEmpty() })
        assertEquals(0, update.bellCount)
        assertTrue(engine.accept(byteArrayOf()).terminalNotifications.isEmpty())
    }

    @Test
    fun osc9RejectsFormattedControlPayloadAndPreservesVisibleInternationalText() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val update = engine.accept(
            buildString {
                append("\u001B]9;Build  \u202Egpj.exe\u202D\u2066\u2069 done\u0007")
                append("\u001B]9;\u202E\u2069\u0007")
                append("\u001B]9;שלום مرحبا\u0007")
            }.bytes(),
        )

        assertEquals(listOf("שלום مرحبا"), update.terminalNotifications)
        assertTrue(update.screen.all { it.text.isEmpty() })
    }

    @Test
    fun decscusrPublishesStandardShapeAndBlinkIncludingDefaultParameter() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val steadyBeam = engine.accept("\u001B[6 q".bytes())
        assertEquals(CursorStyle.BEAM, steadyBeam.cursor.styleOverride)
        assertEquals(false, steadyBeam.cursor.blinkOverride)

        val blinkingUnderline = engine.accept("\u001B[3 q".bytes())
        assertEquals(CursorStyle.UNDERLINE, blinkingUnderline.cursor.styleOverride)
        assertEquals(true, blinkingUnderline.cursor.blinkOverride)

        val explicitDefault = engine.accept("\u001B[0 q".bytes())
        assertEquals(CursorStyle.BLOCK, explicitDefault.cursor.styleOverride)
        assertEquals(true, explicitDefault.cursor.blinkOverride)

        val omittedDefault = engine.accept("\u001B[ q".bytes())
        assertEquals(CursorStyle.BLOCK, omittedDefault.cursor.styleOverride)
        assertEquals(true, omittedDefault.cursor.blinkOverride)
        assertTrue(omittedDefault.dirtyRows.isEmpty())
    }

    @Test
    fun malformedOrOutOfRangeDecscusrIsIgnoredAndParserRecovers() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        engine.accept(byteArrayOf())
        engine.accept("\u001B[3 q".bytes())

        val malformed = engine.accept(
            ("\u001B[7 q" +
                "\u001B[?2 q" +
                "\u001B[2;3 q" +
                "\u001B[2q" +
                "\u001B[2  q").bytes(),
        )
        assertEquals(CursorStyle.UNDERLINE, malformed.cursor.styleOverride)
        assertEquals(true, malformed.cursor.blinkOverride)
        assertTrue(malformed.dirtyRows.isEmpty())

        val recovered = engine.accept("OK\u001B[2 q".bytes())
        assertEquals("OK", recovered.screen[0].text)
        assertEquals(CursorStyle.BLOCK, recovered.cursor.styleOverride)
        assertEquals(false, recovered.cursor.blinkOverride)
    }

    @Test
    fun osc52EmitsBoundedRequestWithoutWritingOrAnsweringTheClipboard() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val encoded = Base64.getEncoder().encodeToString("first\nsecond".toByteArray())

        val request = engine.accept("\u001B]52;c;$encoded\u0007".bytes())

        assertEquals(listOf("first\nsecond"), request.remoteClipboardRequests.map { it.text })
        assertTrue(request.responses.isEmpty())
        assertTrue(engine.accept(byteArrayOf()).remoteClipboardRequests.isEmpty())
    }

    @Test
    fun osc52QueriesInvalidSelectorsAndUnsafePayloadsAreIgnored() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val bidi = Base64.getEncoder().encodeToString("safe\u202Etxt".toByteArray())

        val update = engine.accept(
            ("\u001B]52;c;?\u0007" +
                "\u001B]52;p;${Base64.getEncoder().encodeToString("text".toByteArray())}\u0007" +
                "\u001B]52;c;not-base64!\u0007" +
                "\u001B]52;c;$bidi\u0007").bytes(),
        )

        assertTrue(update.remoteClipboardRequests.isEmpty())
        assertTrue(update.responses.isEmpty())
    }

    @Test
    fun osc52RequestCountAndDecodedSizeAreBoundedPerNetworkBatch() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val tiny = Base64.getEncoder().encodeToString("x".toByteArray())
        val oversized = Base64.getEncoder().encodeToString(ByteArray(769) { 'a'.code.toByte() })
        val batch = buildString {
            append("\u001B]52;c;$oversized\u0007")
            repeat(5) { append("\u001B]52;c;$tiny\u0007") }
        }

        val update = engine.accept(batch.bytes())

        assertEquals(4, update.remoteClipboardRequests.size)
        assertTrue(update.remoteClipboardRequests.all { it.text == "x" })
    }

    @Test
    fun cursorKeypadAndOsc52RecordedTraceRetainsControlStateAndRequest() {
        val update = VtTerminalEngine(columns = 8, rows = 2).accept(
            fixtureBytes("cursor-keypad-osc52.trace"),
        )

        assertEquals(CursorStyle.BEAM, update.cursor.styleOverride)
        assertEquals(false, update.cursor.blinkOverride)
        assertTrue(update.modes.applicationKeypad)
        assertEquals(listOf("clip"), update.remoteClipboardRequests.map { it.text })
    }

    @Test
    fun immutableLineSnapshotsAreReusedAndDirtyRowsArePublished() {
        val engine = VtTerminalEngine(columns = 8, rows = 3)
        val first = engine.accept("a".bytes())
        assertArrayEquals(intArrayOf(0, 1, 2), first.dirtyRows)

        val second = engine.accept("b".bytes())
        assertArrayEquals(intArrayOf(0), second.dirtyRows)
        assertNotSame(first.screen[0], second.screen[0])
        assertSame(first.screen[1], second.screen[1])
        assertSame(first.screen[2], second.screen[2])

        val modeOnly = engine.accept("\u001B[?1h".bytes())
        assertTrue(modeOnly.dirtyRows.isEmpty())
        assertSame(second.screen[0], modeOnly.screen[0])
    }

    @Test
    fun preservesUtf8AcrossNetworkReadsAndCombiningMarks() {
        val engine = VtTerminalEngine(columns = 20, rows = 3)
        val bytes = "A🚀e\u0301".toByteArray(Charsets.UTF_8)

        engine.accept(bytes.copyOfRange(0, bytes.size - 2))
        val update = engine.accept(bytes.copyOfRange(bytes.size - 2, bytes.size))

        assertEquals("A🚀e\u0301", update.screen[0].text)
        assertEquals(4, update.cursor.column)
    }

    @Test
    fun fastAndSlowFiveHundredRowSshStreamsProduceTheSameNumberedHistory() {
        val fast = VtTerminalEngine(columns = 40, rows = 4)
        val slow = VtTerminalEngine(columns = 40, rows = 4)
        val text = (1..500).joinToString("\r\n") { "row-$it" }
        val fastRows = fast.accept(text.bytes()).completedScrollback.map { it.text }
        val slowRows = buildList {
            text.chunked(7).forEach { chunk ->
                addAll(slow.accept(chunk.bytes()).completedScrollback.map { it.text })
            }
        }

        assertEquals(fastRows, slowRows)
        assertEquals((1..496).map { "row-$it" }, fastRows)
    }

    @Test
    fun unicodeSplitInputProgressRewriteAndClearRemainMutableScreenState() {
        val engine = VtTerminalEngine(columns = 20, rows = 3)
        val unicode = "中🚀e\u0301".toByteArray(Charsets.UTF_8)
        unicode.forEach { byte -> engine.accept(byteArrayOf(byte)) }
        val progress = engine.accept("\r10%\r90%\u001B[2K\rready".bytes())
        val rewritten = engine.accept("\u001B[1;1HDONE".bytes())

        assertTrue(progress.completedScrollback.isEmpty())
        assertTrue(rewritten.completedScrollback.isEmpty())
        assertTrue(rewritten.screen.first().text.startsWith("DONE"))
    }

    @Test
    fun scrollsPrimaryScreenIntoScrollback() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val update = engine.accept("one\r\ntwo\r\nthree".bytes())

        assertEquals(listOf("one"), update.completedScrollback.map { it.text })
        assertEquals("two", update.screen[0].text)
        assertEquals("three", update.screen[1].text)
    }

    @Test
    fun cursorAddressingAndEraseWorkOnScreenGrid() {
        val engine = VtTerminalEngine(columns = 8, rows = 3)

        val update = engine.accept("first\u001B[2;3HXY\u001B[1K".bytes())

        assertEquals("first", update.screen[0].text)
        assertEquals("", update.screen[1].text)
        assertEquals(1, update.cursor.row)
        assertEquals(4, update.cursor.column)
    }

    @Test
    fun ed3RequestsScrollbackClearWithoutChangingVisibleScreenOrCursor() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val before = engine.accept("one\r\ntwo\r\nthree".bytes())

        val cleared = engine.accept("\u001B[3J".bytes())
        val afterSignal = engine.accept(byteArrayOf())

        assertTrue(before.completedScrollback.isNotEmpty())
        assertTrue(cleared.clearScrollbackRequested)
        assertTrue(cleared.completedScrollback.isEmpty())
        assertEquals(before.screen, cleared.screen)
        assertEquals(before.cursor, cleared.cursor)
        assertTrue(cleared.dirtyRows.isEmpty())
        assertFalse(afterSignal.clearScrollbackRequested)
    }

    @Test
    fun ed3DropsRowsScrolledEarlierInTheSameParserBatch() {
        val update = VtTerminalEngine(columns = 8, rows = 2).accept(
            "one\r\ntwo\r\nthree\u001B[3J".bytes(),
        )

        assertTrue(update.clearScrollbackRequested)
        assertTrue(update.completedScrollback.isEmpty())
        assertEquals(listOf("two", "three"), update.screen.map { it.text })
        assertEquals(1, update.cursor.row)
        assertEquals(5, update.cursor.column)
    }

    @Test
    fun alternateScreenReportsSeparateScrollableHistoryAndRestoresPrimary() {
        val engine = VtTerminalEngine(columns = 12, rows = 2)
        engine.accept("primary".bytes())

        val alternate = engine.accept("\u001B[?1049hfull\r\nscreen\r\nmore".bytes())
        val restored = engine.accept("\u001B[?1049l".bytes())

        assertTrue(alternate.alternateScreen)
        assertEquals(listOf("full"), alternate.completedScrollback.map { it.text })
        assertFalse(restored.alternateScreen)
        assertEquals("primary", restored.screen[0].text)
    }

    @Test
    fun alternateTopAnchoredScrollRegionReportsTmuxStyleHistory() {
        val engine = VtTerminalEngine(columns = 10, rows = 3)
        engine.accept("\u001B[?1049htop\r\nmiddle\r\nstatus".bytes())

        val update = engine.accept("\u001B[1;2r\u001B[2;1H\nnext".bytes())

        assertTrue(update.alternateScreen)
        assertEquals(listOf("top"), update.completedScrollback.map { it.text })
        assertEquals("middle", update.screen[0].text)
        assertEquals("next", update.screen[1].text)
        assertEquals("status", update.screen[2].text)
    }

    @Test
    fun primaryTopAnchoredScrollRegionRetainsActualCodexStyleOutput() {
        val engine = VtTerminalEngine(columns = 40, rows = 24)
        engine.accept("\u001B[20;1HCODEX_INPUT\u001B[24;1HCODEX_STATUS".bytes())
        val codexOutput = buildString {
            append("\u001B[1;19r\u001B[19;1H\r\n")
            (1..200).forEach { number ->
                append("CODEX_SCROLL_%03d".format(number))
                if (number < 200) append("\r\n")
            }
            append("\u001B[r")
        }

        val update = engine.accept(codexOutput.bytes())
        val retained = (update.completedScrollback + update.screen).map { it.text.trim() }

        assertEquals((1..200).map { "CODEX_SCROLL_%03d".format(it) }, retained.filter {
            it.startsWith("CODEX_SCROLL_")
        })
        assertTrue(update.completedScrollback.any { it.text.trim() == "CODEX_SCROLL_001" })
        assertTrue(update.screen.any { it.text.trim() == "CODEX_SCROLL_200" })
        assertEquals("CODEX_INPUT", update.screen[19].text)
        assertEquals("CODEX_STATUS", update.screen[23].text)
    }

    @Test
    fun primaryTopAnchoredCodexHistoryIsIndependentOfOneByteTransportChunks() {
        val engine = VtTerminalEngine(columns = 40, rows = 24)
        engine.accept("\u001B[20;1HCODEX_INPUT\u001B[24;1HCODEX_STATUS".bytes())
        val codexOutput = buildString {
            append("\u001B[1;19r\u001B[19;1H\r\n")
            (1..200).forEach { number ->
                append("CODEX_SCROLL_%03d".format(number))
                if (number < 200) append("\r\n")
            }
            append("\u001B[r")
        }.bytes()
        val retainedHistory =
            mutableListOf<com.yanjiyu.terminalspike.terminal.model.TerminalLine>()
        codexOutput.forEach { byte ->
            retainedHistory += engine.accept(byteArrayOf(byte)).completedScrollback
        }
        val finalFrame = engine.accept(byteArrayOf())
        val retained = (retainedHistory + finalFrame.screen).map { it.text.trim() }

        assertEquals((1..200).map { "CODEX_SCROLL_%03d".format(it) }, retained.filter {
            it.startsWith("CODEX_SCROLL_")
        })
        assertEquals("CODEX_INPUT", finalFrame.screen[19].text)
        assertEquals("CODEX_STATUS", finalFrame.screen[23].text)
    }

    @Test
    fun scrollRegionKeepsRowsOutsideMarginsStable() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        engine.accept("top\r\naaa\r\nbbb\r\nbottom".bytes())

        val update = engine.accept("\u001B[2;3r\u001B[3;1H\nnew".bytes())

        assertEquals("top", update.screen[0].text)
        assertEquals("bbb", update.screen[1].text)
        assertEquals("new", update.screen[2].text)
        assertEquals("bottom", update.screen[3].text)
        assertTrue(update.completedScrollback.isEmpty())
    }

    @Test
    fun insertDeleteLinesAndCharactersAreBounded() {
        val engine = VtTerminalEngine(columns = 6, rows = 3)
        engine.accept("abcdef\u001B[1;3H\u001B[2P".bytes())
        assertEquals("abef", engine.accept(byteArrayOf()).screen[0].text)

        engine.accept("\u001B[1;3H\u001B[2@XY".bytes())
        assertEquals("abXYef", engine.accept(byteArrayOf()).screen[0].text)

        engine.accept("\u001B[2;1Hrow2\u001B[2;1H\u001B[L".bytes())
        assertEquals("", engine.accept(byteArrayOf()).screen[1].text)
        assertEquals("row2", engine.accept(byteArrayOf()).screen[2].text)
    }

    @Test
    fun preservesStylesAcrossRunsAndResetsThem() {
        val engine = VtTerminalEngine(columns = 20, rows = 2)

        val update = engine.accept("plain\u001B[1;38;5;202;48;2;1;2;3mhot\u001B[0mend".bytes())

        assertEquals("plainhotend", update.screen[0].text)
        assertEquals(3, update.screen[0].runs.size)
        assertTrue(update.screen[0].runs[1].style.bold)
        assertEquals(TerminalColour.Indexed(202), update.screen[0].runs[1].style.foreground)
        assertEquals(
            TerminalColour.Rgb(TerminalPalette.rgb(1, 2, 3)),
            update.screen[0].runs[1].style.background,
        )
        assertFalse(update.screen[0].runs[2].style.bold)
    }

    @Test
    fun underlineResetsWithSgr24AndSgr0WithoutAffectingPlainOutput() {
        val sgr24 = VtTerminalEngine(columns = 24, rows = 2).accept(
            "\u001B[4mUNDERLINE\u001B[24m NORMAL\n".bytes(),
        )
        val sgr0 = VtTerminalEngine(columns = 24, rows = 2).accept(
            "\u001B[4mUNDERLINE\u001B[0m NORMAL\n".bytes(),
        )
        val plain = VtTerminalEngine(columns = 24, rows = 2).accept("NORMAL\n".bytes())

        listOf(sgr24, sgr0).forEach { update ->
            assertEquals(listOf("UNDERLINE", " NORMAL"), update.screen[0].runs.map { it.text })
            assertTrue(update.screen[0].runs[0].style.underline)
            assertFalse(update.screen[0].runs[1].style.underline)
        }
        assertEquals("NORMAL", plain.screen[0].text)
        assertTrue(plain.screen[0].runs.none { it.style.underline })
    }

    @Test
    fun explicitUnderlinedSpacesRemainUnderlinedAfterTheStyleReset() {
        val update = VtTerminalEngine(columns = 24, rows = 2).accept(
            "\u001B[4mUNDERLINE  \u001B[24mNORMAL".bytes(),
        )

        assertEquals(listOf("UNDERLINE  ", "NORMAL"), update.screen[0].runs.map { it.text })
        assertTrue(update.screen[0].runs[0].style.underline)
        assertFalse(update.screen[0].runs[1].style.underline)
    }

    @Test
    fun eraseToEndOfLineKeepsBackgroundButDropsTextDecorations() {
        listOf("\u001B[K", "\u001B[0K").forEach { erase ->
            val update = VtTerminalEngine(columns = 20, rows = 2).accept(
                "\u001B[44;4;9mUNDERLINE$erase".bytes(),
            )
            val content = update.screen[0].runs.single { it.text == "UNDERLINE" }
            val erased = update.screen[0].runs.single { it.text.all { character -> character == ' ' } }

            assertTrue(content.style.underline)
            assertTrue(content.style.strikethrough)
            assertEquals(9, erased.startColumn)
            assertEquals(11, erased.columnWidth)
            assertEquals(TerminalColour.Indexed(4), erased.style.background)
            assertFalse(erased.style.underline)
            assertFalse(erased.style.strikethrough)
        }

        val continued = VtTerminalEngine(columns = 8, rows = 2).accept(
            "\u001B[4mA\u001B[KB".bytes(),
        )
        assertEquals("AB", continued.screen[0].text)
        assertTrue(continued.screen[0].runs.single().style.underline)
    }

    @Test
    fun eraseLineModesAndEraseCharactersDoNotCopyUnderlineIntoErasedCells() {
        val eraseBeginning = VtTerminalEngine(columns = 10, rows = 2).accept(
            "12345\u001B[4m67890\u001B[5G\u001B[1K".bytes(),
        )
        val eraseWhole = VtTerminalEngine(columns = 10, rows = 2).accept(
            "\u001B[45;4mtext\u001B[2K".bytes(),
        )
        val eraseCharacters = VtTerminalEngine(columns = 6, rows = 2).accept(
            "\u001B[46;4mABC\u001B[1G\u001B[2X".bytes(),
        )

        assertEquals("     67890", eraseBeginning.screen[0].text)
        assertFalse(eraseBeginning.screen[0].runs.first().style.underline)
        assertTrue(eraseBeginning.screen[0].runs.last().style.underline)
        assertEquals(" ".repeat(10), eraseWhole.screen[0].text)
        assertEquals(TerminalColour.Indexed(5), eraseWhole.screen[0].runs.single().style.background)
        assertFalse(eraseWhole.screen[0].runs.single().style.underline)
        assertEquals("  C", eraseCharacters.screen[0].text)
        assertFalse(eraseCharacters.screen[0].runs.first().style.underline)
        assertEquals(TerminalColour.Indexed(6), eraseCharacters.screen[0].runs.first().style.background)
        assertTrue(eraseCharacters.screen[0].runs.last().style.underline)
    }

    @Test
    fun colouredFrameworkPromptCarriageReturnAndEraseDoNotCreateTrailingUnderlineRun() {
        val update = VtTerminalEngine(columns = 40, rows = 3).accept(
            ("old prompt\r" +
                "\u001B]133;A\u0007" +
                "\u001B[1;34m~\u001B[0m " +
                "\u001B[32mjiyu@thinkpad-amd\u001B[0m" +
                "\u001B[4m\u001B[K\u001B[0m\r\n" +
                "\u001B[33m>\u001B[0m").bytes(),
        )

        assertEquals("~ jiyu@thinkpad-amd", update.screen[0].text)
        assertTrue(update.screen[0].runs.none { it.style.underline })
        assertEquals(">", update.screen[1].text)
        assertTrue(update.screen[1].runs.none { it.style.underline })
    }

    @Test
    fun scrollingWhileUnderlineIsActiveDoesNotDecorateTheNewBlankRow() {
        val update = VtTerminalEngine(columns = 8, rows = 2).accept(
            "\u001B[44;4mone\r\ntwo\r\n".bytes(),
        )

        assertEquals("two", update.screen[0].text)
        assertTrue(update.screen[0].runs.single().style.underline)
        assertEquals(" ".repeat(8), update.screen[1].text)
        assertEquals(TerminalColour.Indexed(4), update.screen[1].runs.single().style.background)
        assertFalse(update.screen[1].runs.single().style.underline)
    }

    @Test
    fun dimConcealAndStrikethroughHaveIndependentResetsAndExactCellGeometry() {
        val engine = VtTerminalEngine(columns = 20, rows = 2)

        val update = engine.accept(
            ("\u001B[1;2mD\u001B[22mN" +
                "\u001B[8mH\u001B[28mV" +
                "\u001B[9mS\u001B[29mE").bytes(),
        )
        val runs = update.screen[0].runs.associateBy { it.text }

        assertTrue(requireNotNull(runs["D"]).style.bold)
        assertTrue(requireNotNull(runs["D"]).style.dim)
        assertFalse(requireNotNull(runs["N"]).style.bold)
        assertFalse(requireNotNull(runs["N"]).style.dim)
        assertTrue(requireNotNull(runs["H"]).style.conceal)
        assertFalse(requireNotNull(runs["V"]).style.conceal)
        assertTrue(requireNotNull(runs["S"]).style.strikethrough)
        assertFalse(requireNotNull(runs["E"]).style.strikethrough)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), update.screen[0].runs.map { it.startColumn })
        assertTrue(update.screen[0].runs.all { it.columnWidth == 1 })
    }

    @Test
    fun reportsApplicationCursorPasteFocusMouseAndCursorModes() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)

        val enabled = engine.accept("\u001B[?1h\u001B[?1004h\u001B[?1000h\u001B[?1006h\u001B[?2004h\u001B[?25l".bytes())
        val disabled = engine.accept("\u001B[?1l\u001B[?1004l\u001B[?1000l\u001B[?1006l\u001B[?2004l\u001B[?25h".bytes())

        assertTrue(enabled.modes.applicationCursorKeys)
        assertTrue(enabled.modes.bracketedPaste)
        assertTrue(enabled.modes.focusReporting)
        assertTrue(enabled.modes.mouseTracking)
        assertTrue(enabled.modes.sgrMouseEncoding)
        assertFalse(enabled.cursor.visible)
        assertFalse(disabled.modes.applicationCursorKeys)
        assertFalse(disabled.modes.bracketedPaste)
        assertFalse(disabled.modes.focusReporting)
        assertFalse(disabled.modes.mouseTracking)
        assertFalse(disabled.modes.sgrMouseEncoding)
        assertTrue(disabled.cursor.visible)
    }

    @Test
    fun reportsApplicationKeypadInsertAndDetailedMouseModes() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val keypad = engine.accept("\u001B=ab\u001B[1G\u001B[4hZ\u001B[?1002h".bytes())

        assertTrue(keypad.modes.applicationKeypad)
        assertTrue(keypad.modes.insertMode)
        assertEquals(TerminalMouseTrackingMode.BUTTON_EVENT, keypad.modes.mouseTrackingMode)
        assertEquals("Zab", keypad.screen[0].text)

        val reset = engine.accept("\u001B>\u001B[4l\u001B[?1002l".bytes())
        assertFalse(reset.modes.applicationKeypad)
        assertFalse(reset.modes.insertMode)
        assertEquals(TerminalMouseTrackingMode.OFF, reset.modes.mouseTrackingMode)
    }

    @Test
    fun insertModeShiftsByCellWidthAndReplaceModeOverwritesOnlyTheActiveRow() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val initial = engine.accept("abcd\r\nstay".bytes())

        val modeOnly = engine.accept("\u001B[1;2H\u001B[4h".bytes())
        assertTrue(modeOnly.modes.insertMode)
        assertTrue(modeOnly.dirtyRows.isEmpty())

        val inserted = engine.accept("界".bytes())
        assertEquals("a界bcd", inserted.screen[0].text)
        assertArrayEquals(intArrayOf(0), inserted.dirtyRows)
        assertSame(initial.screen[1], inserted.screen[1])

        val replaced = engine.accept("\u001B[4lZ".bytes())
        assertFalse(replaced.modes.insertMode)
        assertEquals("a界Zcd", replaced.screen[0].text)
        assertArrayEquals(intArrayOf(0), replaced.dirtyRows)
        assertSame(inserted.screen[1], replaced.screen[1])
    }

    @Test
    fun onlyUnprefixedCsiModeFourChangesInsertReplaceMode() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        engine.accept(byteArrayOf())

        val ignored = engine.accept(
            ("\u001B[>4h" +
                "\u001B[!4h" +
                "\u001B[?4h" +
                "\u001B[=4h" +
                "\u001B[\$4h" +
                "\u001B[4:0h" +
                "\u001B[h" +
                "\u001B[100001h").bytes(),
        )
        assertFalse(ignored.modes.insertMode)
        assertTrue(ignored.dirtyRows.isEmpty())

        val enabled = engine.accept("\u001B[4h\u001B[>4l".bytes())
        assertTrue(enabled.modes.insertMode)
        assertTrue(enabled.dirtyRows.isEmpty())

        val disabled = engine.accept("\u001B[4l".bytes())
        assertFalse(disabled.modes.insertMode)
        assertTrue(disabled.dirtyRows.isEmpty())
    }

    @Test
    fun risResetsInsertKeypadAndRemoteCursorPresentation() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)
        val enabled = engine.accept("\u001B=\u001B[4h\u001B[6 q".bytes())
        assertTrue(enabled.modes.applicationKeypad)
        assertTrue(enabled.modes.insertMode)
        assertEquals(CursorStyle.BEAM, enabled.cursor.styleOverride)

        val reset = engine.accept("\u001Bc".bytes())

        assertFalse(reset.modes.applicationKeypad)
        assertFalse(reset.modes.insertMode)
        assertEquals(null, reset.cursor.styleOverride)
        assertEquals(null, reset.cursor.blinkOverride)
        assertArrayEquals(intArrayOf(0, 1), reset.dirtyRows)
    }

    @Test
    fun answersStatusCursorAndDeviceAttributeQueries() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)

        val update = engine.accept("ab\u001B[5n\u001B[6n\u001B[c".bytes())

        assertEquals(3, update.responses.size)
        assertArrayEquals("\u001B[0n".bytes(), update.responses[0])
        assertArrayEquals("\u001B[1;3R".bytes(), update.responses[1])
        assertArrayEquals("\u001B[?1;2c".bytes(), update.responses[2])
    }

    @Test
    fun shrinkingHeightKeepsTheActivePromptAndMovesTopPrimaryRowsToScrollback() {
        val engine = VtTerminalEngine(columns = 10, rows = 3)
        engine.accept("first\r\nsecond\r\nprompt> ".bytes())

        val update = engine.resize(newColumns = 10, newRows = 2)

        assertEquals(listOf("first"), update.completedScrollback.map { it.text })
        assertEquals(listOf("second", "prompt>"), update.screen.map { it.text })
        assertEquals(1, update.cursor.row)
        assertEquals(8, update.cursor.column)
    }

    @Test
    fun primaryResizeScrollbackIsDeferredUntilLeavingTheAlternateScreen() {
        val engine = VtTerminalEngine(columns = 10, rows = 3)
        engine.accept("first\r\nsecond\r\nprompt> ".bytes())
        engine.accept("\u001B[?1049h".bytes())

        val alternateResize = engine.resize(newColumns = 10, newRows = 2)
        val restoredPrimary = engine.accept("\u001B[?1049l".bytes())

        assertTrue(alternateResize.alternateScreen)
        assertTrue(alternateResize.completedScrollback.isEmpty())
        assertFalse(restoredPrimary.alternateScreen)
        assertEquals(listOf("first"), restoredPrimary.completedScrollback.map { it.text })
        assertEquals(listOf("second", "prompt>"), restoredPrimary.screen.map { it.text })
        assertEquals(1, restoredPrimary.cursor.row)
        assertEquals(8, restoredPrimary.cursor.column)
    }

    @Test
    fun shrinkingWidthWrapsTheActivePromptWithoutDiscardingItsTail() {
        val engine = VtTerminalEngine(columns = 8, rows = 3)
        engine.accept("top\r\nbody\r\nprompt12".bytes())

        val update = engine.resize(newColumns = 4, newRows = 3)

        assertEquals(listOf("top"), update.completedScrollback.map { it.text })
        assertEquals(listOf("body", "prom", "pt12"), update.screen.map { it.text })
        assertTrue(update.screen[1].softWrappedToNext)
        assertFalse(update.screen[2].softWrappedToNext)
        assertEquals(2, update.cursor.row)
        assertEquals(3, update.cursor.column)

        val continued = engine.accept("X".bytes())
        assertEquals(listOf("body"), continued.completedScrollback.map { it.text })
        assertEquals(listOf("prom", "pt12", "X"), continued.screen.map { it.text })
    }

    @Test
    fun shrinkingWidthDoesNotSplitAWideCellAtTheNewRightEdge() {
        val engine = VtTerminalEngine(columns = 6, rows = 2)
        engine.accept("ab界z".bytes())

        val update = engine.resize(newColumns = 3, newRows = 2)

        assertEquals(listOf("ab", "界z"), update.screen.map { it.text })
        assertTrue(update.screen[0].softWrappedToNext)
        assertEquals(listOf(0, 0), update.screen.map { line -> line.runs.first().startColumn })
        assertEquals(listOf(2, 3), update.screen.map { line -> line.runs.sumOf { it.columnWidth } })
    }

    @Test
    fun oversizedControlSequenceIsDiscardedAndParserRecovers() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)
        val hostile = "\u001B[" + "1".repeat(5_000) + "mOK"

        val update = engine.accept(hostile.bytes())

        assertTrue(update.screen[0].text.endsWith("OK"))
        assertTrue(update.screen.sumOf { it.text.length } <= 20)
    }

    @Test
    fun oscZeroAndTwoPublishTitlesAfterBelAndStringTerminators() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)

        val bel = engine.accept("\u001B]0;build shell\u0007".bytes())
        val escapedSt = engine.accept("\u001B]2;editor\u001B\\".bytes())
        val c1St = engine.accept("\u009D2;monitor\u009C".bytes())

        assertEquals("build shell", bel.terminalTitle)
        assertEquals("editor", escapedSt.terminalTitle)
        assertEquals("monitor", c1St.terminalTitle)
    }

    @Test
    fun oscTitleParsingSurvivesNetworkAndUtf8ChunkBoundaries() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)
        val sequence = "\u001B]2;deploy 🚀\u001B\\".bytes()

        sequence.forEachIndexed { index, byte ->
            val update = engine.accept(byteArrayOf(byte))
            if (index < sequence.lastIndex) assertEquals(null, update.terminalTitle)
        }

        assertEquals("deploy 🚀", engine.accept(byteArrayOf()).terminalTitle)
    }

    @Test
    fun malformedUnsupportedAndControlBearingOscDoNotReplaceCurrentTitle() {
        val engine = VtTerminalEngine(columns = 12, rows = 2)
        engine.accept("\u001B]0;trusted\u0007".bytes())

        listOf(
            "\u001B]1;unsupported\u0007",
            "\u001B]2missing separator\u0007",
            "\u001B]02;invalid selector\u0007",
            "\u001B]2;line\nbreak\u0007",
            "\u001B]2;broken\u001Bxrest\u0007",
            "\u001B]2;bad \uFFFD title\u0007",
            "\u001B]2;hidden\u202Etitle\u0007",
        ).forEach { malformed ->
            assertEquals("trusted", engine.accept(malformed.bytes()).terminalTitle)
        }
        assertEquals("OK", engine.accept("OK".bytes()).screen[0].text)
    }

    @Test
    fun osc8AttachesBoundedHttpsMetadataAndClosingSequenceStopsTheLink() {
        val engine = VtTerminalEngine(columns = 20, rows = 2)

        val update = engine.accept(
            ("\u001B]8;id=docs;https://example.test/help\u0007link" +
                "\u001B]8;;\u0007 plain").bytes(),
        )

        assertEquals("link plain", update.screen[0].text)
        assertEquals(2, update.screen[0].runs.size)
        assertEquals("https://example.test/help", update.screen[0].runs[0].hyperlink?.uri)
        assertEquals("docs", update.screen[0].runs[0].hyperlink?.id)
        assertEquals(0, update.screen[0].runs[0].startColumn)
        assertEquals(4, update.screen[0].runs[0].columnWidth)
        assertEquals(null, update.screen[0].runs[1].hyperlink)
        assertEquals(4, update.screen[0].runs[1].startColumn)
    }

    @Test
    fun invalidOsc8SchemeClearsPriorLinkInsteadOfMislabelingFollowingText() {
        val engine = VtTerminalEngine(columns = 20, rows = 2)

        val update = engine.accept(
            ("\u001B]8;;https://example.test\u0007safe" +
                "\u001B]8;;javascript:alert(1)\u0007plain").bytes(),
        )

        assertEquals("https://example.test", update.screen[0].runs[0].hyperlink?.uri)
        assertEquals(null, update.screen[0].runs.last().hyperlink)
    }

    @Test
    fun autowrapAndRunCellGeometrySurviveWideCharacters() {
        val engine = VtTerminalEngine(columns = 4, rows = 2)

        val update = engine.accept("A🚀Bz".bytes())

        assertEquals("A🚀B", update.screen[0].text)
        assertTrue(update.screen[0].softWrappedToNext)
        assertEquals(0, update.screen[0].runs.single().startColumn)
        assertEquals(4, update.screen[0].runs.single().columnWidth)
        assertEquals("z", update.screen[1].text)
        assertFalse(update.screen[1].softWrappedToNext)
    }

    @Test
    fun joinedEmojiModifiersFlagsAndBoxDrawingRetainExactCellGeometry() {
        val engine = VtTerminalEngine(columns = 12, rows = 2)

        val update = engine.accept("A👩🏽‍💻🇬🇧┌─B".bytes())

        assertEquals("A👩🏽‍💻🇬🇧┌─B", update.screen[0].text)
        assertEquals(8, update.screen[0].runs.single().columnWidth)
        assertEquals(8, update.cursor.column)
    }

    @Test
    fun combiningMarkAtFinalColumnAttachesToTheFinalCellBeforeAutowrap() {
        val engine = VtTerminalEngine(columns = 4, rows = 2)

        val beforeWrap = engine.accept("abcZ\u0301".bytes())
        val afterWrap = engine.accept("x".bytes())

        assertEquals("abcZ\u0301", beforeWrap.screen[0].text)
        assertEquals(4, beforeWrap.screen[0].runs.single().columnWidth)
        assertTrue(afterWrap.screen[0].softWrappedToNext)
        assertEquals("x", afterWrap.screen[1].text)
    }

    @Test
    fun resetClearsScreenModesAndParserState() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)
        engine.accept("text\u001B[?1049h\u001B[?2004h\u001B]2;old title\u0007".bytes())

        val update = engine.reset()

        assertFalse(update.alternateScreen)
        assertFalse(update.modes.bracketedPaste)
        assertEquals(listOf("", ""), update.screen.map { it.text })
        assertEquals(0, update.cursor.row)
        assertEquals(0, update.cursor.column)
        assertEquals(null, update.terminalTitle)
    }

    @Test
    fun malformedAndRandomChunkedInputAlwaysRemainsBounded() {
        val engine = VtTerminalEngine(columns = 40, rows = 12)
        val random = Random(765380)

        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(1, 96)) { random.nextInt(0, 256).toByte() }
            val update = engine.accept(bytes)
            assertEquals(12, update.screen.size)
            assertTrue(update.screen.all { it.text.length <= 80 })
            assertTrue(update.completedScrollback.size <= 96)
            assertTrue(update.cursor.row in 0..11)
            assertTrue(update.cursor.column in 0..39)
        }
    }

    @Test
    fun oversizedOscPayloadIsDiscardedUntilTerminator() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)
        engine.accept("\u001B]2;retained\u0007".bytes())

        val update = engine.accept(("\u001B]0;" + "x".repeat(5_000) + "\u0007OK").bytes())

        assertEquals("OK", update.screen[0].text)
        assertEquals("retained", update.terminalTitle)
    }

    @Test
    fun projectAuthoredModeFixturesReplayAcrossEveryNetworkChunkBoundary() {
        val insertBytes = fixtureBytes("insert-keypad.trace")
        val insert = replayOneByteAtATime(VtTerminalEngine(columns = 8, rows = 2), insertBytes)
        assertEquals("Zab", insert.screen[0].text)
        assertFalse(insert.modes.insertMode)
        assertFalse(insert.modes.applicationKeypad)

        val osc = replayOneByteAtATime(
            VtTerminalEngine(columns = 4, rows = 2),
            fixtureBytes("osc8-wrap.trace"),
        )
        assertEquals("link", osc.screen[0].text)
        assertTrue(osc.screen[0].softWrappedToNext)
        assertEquals("https://example.test", osc.screen[0].runs.single().hyperlink?.uri)
        assertEquals("z", osc.screen[1].text)

        val mouse = replayOneByteAtATime(
            VtTerminalEngine(columns = 8, rows = 2),
            fixtureBytes("alternate-mouse.trace"),
        )
        assertFalse(mouse.alternateScreen)
        assertEquals(TerminalMouseTrackingMode.OFF, mouse.modes.mouseTrackingMode)

        val unicode = replayOneByteAtATime(
            VtTerminalEngine(columns = 12, rows = 2),
            fixtureBytes("unicode-grapheme.trace"),
        )
        assertEquals("A👩🏽‍💻🇬🇧┌─B", unicode.screen[0].text)
        assertEquals(8, unicode.screen[0].runs.single().columnWidth)
    }

    @Test
    fun inputModeAndCursorFixtureIsInvariantAcrossRandomNetworkChunking() {
        val bytes = fixtureBytes("input-modes-cursor.trace")
        val expectedEngine = VtTerminalEngine(columns = 8, rows = 2)
        expectedEngine.accept(byteArrayOf())
        val expected = expectedEngine.accept(bytes)

        assertEquals("aZYcd", expected.screen[0].text)
        assertTrue(expected.modes.applicationKeypad)
        assertFalse(expected.modes.insertMode)
        assertEquals(CursorStyle.BEAM, expected.cursor.styleOverride)
        assertEquals(true, expected.cursor.blinkOverride)
        assertArrayEquals(intArrayOf(0), expected.dirtyRows)

        repeat(64) { seed ->
            val random = Random(seed * 7919 + 17)
            val engine = VtTerminalEngine(columns = 8, rows = 2)
            engine.accept(byteArrayOf())
            val dirtyRows = sortedSetOf<Int>()
            var offset = 0
            var actual = engine.accept(byteArrayOf())
            while (offset < bytes.size) {
                val length = random.nextInt(1, minOf(9, bytes.size - offset) + 1)
                actual = engine.accept(bytes.copyOfRange(offset, offset + length))
                actual.dirtyRows.forEach { row -> dirtyRows.add(row) }
                offset += length
            }

            assertEquals(expected.screen.map { it.text }, actual.screen.map { it.text })
            assertEquals(expected.screen.map { it.runs }, actual.screen.map { it.runs })
            assertEquals(
                expected.screen.map { it.softWrappedToNext },
                actual.screen.map { it.softWrappedToNext },
            )
            assertEquals(expected.cursor, actual.cursor)
            assertEquals(expected.modes, actual.modes)
            assertEquals(listOf(0), dirtyRows.toList())
        }
    }

    private fun replayOneByteAtATime(
        engine: VtTerminalEngine,
        bytes: ByteArray,
    ): TerminalFrameUpdate {
        bytes.forEach { byte -> engine.accept(byteArrayOf(byte)) }
        return engine.accept(byteArrayOf())
    }

    private fun fixtureBytes(name: String): ByteArray {
        val stream = requireNotNull(javaClass.getResourceAsStream("/terminal-fixtures/$name"))
        val output = ByteArrayOutputStream()
        stream.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.map(String::trim)
                .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                .flatMap { line -> line.split(Regex("\\s+")).asSequence() }
                .forEach { token -> output.write(token.toInt(16)) }
        }
        return output.toByteArray()
    }

    private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)
}
