package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.ui.rememberTerminalKeyboardVisible
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalResizeHandoffTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun animatedImeHeightOnlyRecomposesVisibilityAtItsTwoEdges() {
        val bottom = mutableIntStateOf(0)
        val insets = object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getTop(density: Density) = 0
            override fun getBottom(density: Density) = bottom.intValue
        }
        val observations = mutableListOf<Boolean>()
        composeRule.setContent {
            val visible = rememberTerminalKeyboardVisible(insets)
            SideEffect { observations.add(visible) }
        }
        composeRule.runOnIdle { assertEquals(listOf(false), observations) }
        for (pixels in (1..60).map { it * 5 } + (1..59).reversed().map { it * 5 }) {
            composeRule.runOnIdle { bottom.intValue = pixels }
        }
        composeRule.runOnIdle { assertEquals(listOf(false, true), observations) }
        composeRule.runOnIdle { bottom.intValue = 0 }
        composeRule.runOnIdle { assertEquals(listOf(false, true, false), observations) }
    }

    @Test fun transcriptHasNoPixelJumpWhenReleasingSnapshotAtNonIntegralRowHeights() =
        verifyHandoff(terminalScreen = false, sparse = false)

    @Test fun shortShellPromptStaysVisibleAndStationaryThroughBothDirections() =
        verifyHandoff(terminalScreen = true, sparse = true)

    @Test fun filledShellKeepsItsCursorAnchorWhenKeyboardCloses() =
        verifyHandoff(terminalScreen = true, sparse = false)

    private fun verifyHandoff(terminalScreen: Boolean, sparse: Boolean) {
        val height = mutableIntStateOf(401)
        val controller = TerminalController().apply {
            updateRendererProfile(rendererProfile.copy(cursorBlinkEnabled = false))
        }
        val engine = VtTerminalEngine()
        var reports = 0
        lateinit var view: FastTerminalView
        controller.setInputSink(object : TerminalInputSink { override fun send(bytes: ByteArray) = Unit },
            onResize = { columns, rows ->
                reports += 1
                if (terminalScreen) controller.updateTerminalFrame(engine.resize(columns, rows), isResize = true)
            })
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AndroidView(factory = { context -> FastTerminalView(context).also {
                    view = it
                    it.attachController(controller)
                } }, modifier = Modifier.requiredSize(240.dp, height.intValue.dp))
            }
        }
        composeRule.waitUntil(5_000) { reports > 0 }
        composeRule.runOnIdle {
            if (terminalScreen) {
                val text = if (sparse) "prompt" else (1..100).joinToString("\r\n") { "LINE_$it" }
                controller.updateTerminalFrame(engine.accept(text.toByteArray()))
            } else controller.updateSessionOutput((1..100).map { TerminalLine.plain("LINE_$it") }, TerminalLine.plain("prompt"))
        }
        composeRule.waitUntil(3_000) {
            composeRule.runOnUiThread {
                if (sparse) controller.lineAt(0)?.text == "prompt" else controller.lineCount() > 50
            }
        }
        fun pixels() = Bitmap.createBitmap(240, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        for (destination in listOf(302, 401)) {
            val start = height.intValue
            val source = composeRule.runOnUiThread { pixels() }
            composeRule.runOnIdle {
                if (destination < start) view.onImeAnimationLayout(0, start - destination, 0)
                else view.onImeAnimationLayout(destination - start, 0, 0)
                height.intValue = destination
            }
            composeRule.runOnIdle { view.onImeAnimationLayout(0, 0, 0) }
            val beforeReveal = composeRule.runOnUiThread { pixels() }
            Thread.sleep(700) // instrumentation thread: allow bounded reveal of a silent session
            val afterReveal = composeRule.runOnUiThread { pixels() }
            assertTrue("Unchanged content must not jump at snapshot release ($start -> $destination)",
                beforeReveal.sameAs(afterReveal))
            if (sparse) {
                val commonHeight = minOf(source.height, afterReveal.height)
                val beforeTop = Bitmap.createBitmap(source, 0, 0, 240, commonHeight)
                val afterTop = Bitmap.createBitmap(afterReveal, 0, 0, 240, commonHeight)
                assertTrue("A fitting shell prompt must not be pushed away", beforeTop.sameAs(afterTop))
                beforeTop.recycle(); afterTop.recycle()
            }
            source.recycle(); beforeReveal.recycle(); afterReveal.recycle()
        }
    }
}
