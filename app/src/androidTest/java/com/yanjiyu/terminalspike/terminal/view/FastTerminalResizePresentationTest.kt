package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFrameScheduler
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import java.util.concurrent.atomic.AtomicInteger

class FastTerminalResizePresentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun attachedViewReleasesAfterRemoteRepaintAndTimesOutForASilentConnection() {
        val height = mutableIntStateOf(600)
        val resizes = AtomicInteger()
        val controller = TerminalController()
        val engine = VtTerminalEngine(40, 20)
        lateinit var view: FastTerminalView
        controller.setInputSink(
            sink = object : TerminalInputSink { override fun send(bytes: ByteArray) = Unit },
            onResize = { columns, rows ->
                // Make the local placeholder visibly different from the retained remote picture.
                val local = engine.resize(columns, rows)
                controller.updateTerminalFrame(local.copy(
                    screen = local.screen.map { TerminalLine.plain("LOCAL RESIZE") },
                ), isResize = true)
                resizes.incrementAndGet()
            },
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AndroidView(
                    factory = { context -> FastTerminalView(context).also {
                        view = it
                        it.attachController(controller)
                    } },
                    modifier = Modifier.requiredSize(400.dp, height.intValue.dp),
                )
            }
        }
        composeRule.waitUntil(3_000L) { resizes.get() > 0 }
        composeRule.runOnIdle {
            controller.updateTerminalFrame(engine.accept("\u001b[2J\u001b[HREMOTE BEFORE".toByteArray()))
        }
        composeRule.waitUntil(3_000L) { controller.renderedOutputRevision > 0L }
        composeRule.waitForIdle()
        fun render() = Bitmap.createBitmap(400, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
        var expected: Bitmap? = null
        var baseline = resizes.get()
        composeRule.runOnIdle {
            val full = render()
            expected = Bitmap.createBitmap(full, 0, 0, 400, 400)
            full.recycle()
            height.intValue = 400
        }
        composeRule.waitUntil(3_000L) { resizes.get() > baseline }
        composeRule.runOnIdle {
            val actual = render()
            assertTrue("Local resize must stay hidden", requireNotNull(expected).sameAs(actual))
            actual.recycle()
            controller.updateTerminalFrame(engine.accept("\u001b[2J\u001b[HREMOTE AFTER".toByteArray()))
        }
        fun changed(): Boolean {
            var different = false
            composeRule.runOnIdle {
                val actual = render()
                different = !requireNotNull(expected).sameAs(actual)
                actual.recycle()
            }
            return different
        }
        composeRule.waitUntil(3_000L) { changed() }
        baseline = resizes.get()
        composeRule.runOnIdle {
            requireNotNull(expected).recycle()
            val full = render()
            expected = Bitmap.createBitmap(full, 0, 0, 400, 300)
            full.recycle()
            height.intValue = 300
        }
        composeRule.waitUntil(3_000L) { resizes.get() > baseline }
        composeRule.runOnIdle {
            val actual = render()
            assertTrue("Silent local resize must initially stay hidden", requireNotNull(expected).sameAs(actual))
            actual.recycle()
        }
        composeRule.waitUntil(3_000L) { changed() }
        composeRule.runOnIdle { requireNotNull(expected).recycle() }
    }

    @Test fun keyboardHeightChangesKeepTheSamePixelsUntilRepaintAndInputRevealsLiveOutput() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val callbacks = ArrayDeque<Choreographer.FrameCallback>()
            val scheduler = object : TerminalFrameScheduler {
                override fun postFrame(callback: Choreographer.FrameCallback) { callbacks.addLast(callback) }
            }
            fun publish() { while (callbacks.isNotEmpty()) callbacks.removeFirst().doFrame(1L) }
            val controller = TerminalController(TerminalBuffer(), scheduler)
            controller.updateRendererProfile(controller.rendererProfile.copy(cursorBlinkEnabled = false))
            val view = FastTerminalView(InstrumentationRegistry.getInstrumentation().targetContext)
            fun layout(height: Int) {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 400, height)
            }
            fun render(): Bitmap = Bitmap.createBitmap(400, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
            layout(800)
            view.attachController(controller)
            controller.updateSessionOutput(
                (1..100).map { TerminalLine.plain("original row $it") }, TerminalLine.plain("prompt"),
            )
            publish()
            val original = render()
            // Exercise several animation frames, including a reversal, without recapturing.
            for (height in listOf(750, 650, 700, 790)) {
                layout(height)
                val expected = Bitmap.createBitmap(original, 0, 800 - height, 400, height)
                val actual = render()
                assertTrue("Stable bottom-anchored text at height $height", expected.sameAs(actual))
                expected.recycle()
                actual.recycle()
            }
            // A remote clear/repaint arriving during animation must not expose an intermediate grid.
            controller.updateTerminalFrame(VtTerminalEngine(40, 20).accept("\u001b[2JNEW OUTPUT".toByteArray()))
            publish()
            val frozen = render()
            val expected = Bitmap.createBitmap(original, 0, 10, 400, 790)
            assertTrue(expected.sameAs(frozen))
            // Interaction immediately restores current content; input is never held for the timeout.
            view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            val live = render()
            assertFalse(frozen.sameAs(live))
            original.recycle()
            frozen.recycle()
            expected.recycle()
            live.recycle()
        }
    }
}
