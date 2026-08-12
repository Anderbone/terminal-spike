package com.yanjiyu.terminalspike

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalContentListener
import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.TerminalModes
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.terminal.view.TerminalInputConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasteModeDispatchTest {
    @Test
    fun controllerPropagatesPasteAcceptanceAndExactBytes() {
        val acceptedBytes = mutableListOf<ByteArray>()
        val acceptingController = TerminalController().apply {
            setInputSink(
                sink = capturingSink(acceptedBytes, accepted = true),
                onResize = { _, _ -> },
            )
        }
        val rejectedController = TerminalController().apply {
            setInputSink(
                sink = capturingSink(mutableListOf(), accepted = false),
                onResize = { _, _ -> },
            )
        }
        val text = "printf 'exact ✓'"

        assertTrue(acceptingController.sendPaste(text))
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), acceptedBytes.single())
        assertFalse(rejectedController.sendPaste(text))
    }

    @Test
    fun controllerReportsOnlyAcceptedDirectAndPasteInputAsOutboundActivity() {
        var acceptedActivityCount = 0
        var rejectedActivityCount = 0
        val acceptingController = TerminalController().apply {
            setInputSink(
                sink = capturingSink(mutableListOf(), accepted = true),
                onResize = { _, _ -> },
                onInputAccepted = { acceptedActivityCount += 1 },
            )
        }
        val rejectingController = TerminalController().apply {
            setInputSink(
                sink = capturingSink(mutableListOf(), accepted = false),
                onResize = { _, _ -> },
                onInputAccepted = { rejectedActivityCount += 1 },
            )
        }

        assertTrue(acceptingController.sendWithAcceptance("direct".toByteArray()))
        assertTrue(acceptingController.sendPaste("buffered"))
        assertFalse(rejectingController.sendWithAcceptance("rejected direct".toByteArray()))
        assertFalse(rejectingController.sendPaste("rejected paste"))

        assertEquals(2, acceptedActivityCount)
        assertEquals(0, rejectedActivityCount)
    }

    @Test
    fun mouseWheelUsesDirectAcceptancePolicyAndReportsOnlyAcceptedActivity() {
        var acceptedActivityCount = 0
        var rejectedActivityCount = 0
        val acceptingSink = DirectPolicySink(accepted = true)
        val rejectingSink = DirectPolicySink(accepted = false)
        val acceptingController = mouseTrackingController(
            sink = acceptingSink,
            onInputAccepted = { acceptedActivityCount += 1 },
        )
        val rejectingController = mouseTrackingController(
            sink = rejectingSink,
            onInputAccepted = { rejectedActivityCount += 1 },
        )

        assertTrue(acceptingController.sendMouseWheel(up = true, column = 2, row = 3))
        assertFalse(rejectingController.sendMouseWheel(up = false, column = 4, row = 5))

        assertEquals(1, acceptingSink.directAttempts)
        assertEquals(1, rejectingSink.directAttempts)
        assertEquals(0, acceptingSink.bufferedAttempts)
        assertEquals(0, rejectingSink.bufferedAttempts)
        assertArrayEquals(
            "\u001B[<64;3;4M".toByteArray(Charsets.US_ASCII),
            acceptingSink.acceptedBytes.single(),
        )
        assertTrue(rejectingSink.acceptedBytes.isEmpty())
        assertEquals(1, acceptedActivityCount)
        assertEquals(0, rejectedActivityCount)
    }

    @Test
    fun directImeDoesNotEmitCompositionAndCommitsUnicodeOnce() {
        val sentBytes = mutableListOf<ByteArray>()
        val targetView = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = TerminalInputConnection(
            targetView = targetView,
            sink = capturingSink(sentBytes, accepted = true),
        )

        connection.setComposingText("你", 1)
        assertTrue(sentBytes.isEmpty())

        connection.commitText("你好 😀", 1)

        assertArrayEquals("你好 😀".toByteArray(Charsets.UTF_8), sentBytes.single())
    }

    @Test
    fun directImeSendsCompositionOnceWhenFinishIsTheOnlyFinalization() {
        val sentBytes = mutableListOf<ByteArray>()
        val targetView = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = TerminalInputConnection(
            targetView = targetView,
            sink = capturingSink(sentBytes, accepted = true),
        )

        connection.setComposingText("に", 1)
        connection.setComposingText("日本", 1)
        assertTrue(sentBytes.isEmpty())

        connection.finishComposingText()

        assertArrayEquals("日本".toByteArray(Charsets.UTF_8), sentBytes.single())
    }

    @Test
    fun directImeStreamsLatinCompositionWithoutDuplicatingItsFinalCommit() {
        val sentBytes = mutableListOf<ByteArray>()
        val targetView = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = TerminalInputConnection(
            targetView = targetView,
            sink = capturingSink(sentBytes, accepted = true),
        )

        connection.setComposingText("h", 1)
        connection.setComposingText("hello", 1)
        connection.commitText("hello ", 1)

        assertArrayEquals("hello ".encodeToByteArray(), sentBytes.joined())
    }

    @Test
    fun externalControlChordCancelsAStagedImeCommitInsteadOfReplayingIt() {
        val sentBytes = mutableListOf<ByteArray>()
        val targetView = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val sink = capturingSink(sentBytes, accepted = true)
        val connection = TerminalInputConnection(targetView = targetView, sink = sink)

        connection.setComposingText("first", 1)
        connection.commitText("first ", 1)
        connection.setComposingText("second", 1)
        connection.resetComposingInput()
        sink.send(byteArrayOf(0x03))
        connection.commitText("second", 1)

        assertArrayEquals("first second".encodeToByteArray() + byteArrayOf(0x03), sentBytes.joined())
    }

    @Test
    fun deletingInsideCompositionNeverLeaksIntermediateText() {
        val sentBytes = mutableListOf<ByteArray>()
        val targetView = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = TerminalInputConnection(
            targetView = targetView,
            sink = capturingSink(sentBytes, accepted = true),
        )

        connection.setComposingText("日本", 1)
        connection.deleteSurroundingTextInCodePoints(1, 0)
        assertTrue(sentBytes.isEmpty())

        connection.finishComposingText()

        assertArrayEquals("日".toByteArray(Charsets.UTF_8), sentBytes.single())
    }

    @Test
    fun deletingEmojiInsideCompositionDoesNotCommitABrokenSurrogate() {
        val sentBytes = mutableListOf<ByteArray>()
        val targetView = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val connection = TerminalInputConnection(
            targetView = targetView,
            sink = capturingSink(sentBytes, accepted = true),
        )

        connection.setComposingText("A😀", 1)
        connection.deleteSurroundingTextInCodePoints(1, 0)
        assertTrue(sentBytes.isEmpty())

        connection.finishComposingText()

        assertArrayEquals("A".toByteArray(Charsets.UTF_8), sentBytes.single())
    }

    @Test
    fun alreadyIssuedDirectInputConnectionCannotSendAfterBufferedModeStarts() {
        val sentBytes = mutableListOf<ByteArray>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val terminalView = FastTerminalView(instrumentation.targetContext)
            val controller = TerminalController().apply {
                setInputSink(
                    sink = capturingSink(sentBytes, accepted = true),
                    onResize = { _, _ -> },
                )
            }
            terminalView.attachController(controller)
            val directConnection =
                terminalView.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            terminalView.setDirectInputEnabled(false)
            directConnection!!.commitText("must stay staged", 1)
        }

        assertTrue(sentBytes.isEmpty())
    }

    @Test
    fun alreadyIssuedDirectInputConnectionTracksTheSelectedController() {
        val firstSessionBytes = mutableListOf<ByteArray>()
        val secondSessionBytes = mutableListOf<ByteArray>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val terminalView = FastTerminalView(instrumentation.targetContext)
            val firstController = TerminalController().apply {
                setInputSink(
                    sink = capturingSink(firstSessionBytes, accepted = true),
                    onResize = { _, _ -> },
                )
            }
            val secondController = TerminalController().apply {
                setInputSink(
                    sink = capturingSink(secondSessionBytes, accepted = true),
                    onResize = { _, _ -> },
                )
            }
            terminalView.attachController(firstController)
            val directConnection =
                terminalView.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            terminalView.attachController(secondController)
            directConnection!!.commitText("second session", 1)
        }

        assertTrue(firstSessionBytes.isEmpty())
        assertArrayEquals(
            "second session".toByteArray(Charsets.UTF_8),
            secondSessionBytes.single(),
        )
    }

    private fun capturingSink(
        destination: MutableList<ByteArray>,
        accepted: Boolean,
    ): TerminalInputSink = object : TerminalInputSink {
        override fun send(bytes: ByteArray) {
            destination += bytes.copyOf()
        }

        override fun trySend(bytes: ByteArray): Boolean {
            if (accepted) destination += bytes.copyOf()
            return accepted
        }
    }

    private fun List<ByteArray>.joined(): ByteArray = fold(ByteArray(0), ByteArray::plus)

    private fun mouseTrackingController(
        sink: TerminalInputSink,
        onInputAccepted: () -> Unit,
    ): TerminalController {
        val contentChanges = CountDownLatch(2)
        val contentListener = TerminalContentListener { _ -> contentChanges.countDown() }
        val controller = TerminalController().apply {
            setInputSink(
                sink = sink,
                onResize = { _, _ -> },
                onInputAccepted = onInputAccepted,
            )
            addListener(contentListener)
            updateTerminalFrame(
                TerminalFrameUpdate(
                    completedScrollback = emptyList(),
                    screen = emptyList(),
                    cursor = TerminalCursor(),
                    alternateScreen = false,
                    modes = TerminalModes(
                        mouseTracking = true,
                        sgrMouseEncoding = true,
                    ),
                ),
            )
        }
        assertTrue(contentChanges.await(2, TimeUnit.SECONDS))
        controller.removeListener(contentListener)
        assertTrue(controller.isMouseTrackingEnabled())
        return controller
    }

    private class DirectPolicySink(
        private val accepted: Boolean,
    ) : TerminalInputSink {
        val acceptedBytes = mutableListOf<ByteArray>()
        var directAttempts = 0
        var bufferedAttempts = 0

        override fun send(bytes: ByteArray) = Unit

        override fun sendWithAcceptance(bytes: ByteArray): Boolean {
            directAttempts += 1
            if (accepted) acceptedBytes += bytes.copyOf()
            return accepted
        }

        override fun trySend(bytes: ByteArray): Boolean {
            bufferedAttempts += 1
            return false
        }
    }
}
