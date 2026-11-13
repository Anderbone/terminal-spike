package com.yanjiyu.terminalspike.terminal.view

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.ui.TerminalDetailInsetContainer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections

@OptIn(ExperimentalLayoutApi::class)
class TerminalImeTransitionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun hardwareDisplayListKeepsPixelsStableUntilAnimationEnds() {
        val height = mutableIntStateOf(400)
        val controller = TerminalController().apply {
            updateRendererProfile(rendererProfile.copy(cursorBlinkEnabled = false))
        }
        val engine = VtTerminalEngine()
        lateinit var view: FastTerminalView
        var sizeReports = 0
        controller.setInputSink(object : TerminalInputSink { override fun send(bytes: ByteArray) = Unit },
            onResize = { columns, rows ->
                sizeReports += 1
                controller.updateTerminalFrame(engine.resize(columns, rows), isResize = true)
            })
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AndroidView(factory = { context -> FastTerminalView(context).also {
                    view = it
                    it.attachController(controller)
                } }, modifier = Modifier.requiredSize(240.dp, height.intValue.dp).testTag("hardware_resize"))
            }
        }
        composeRule.waitUntil(3_000) { sizeReports > 0 }
        composeRule.runOnIdle {
            assertTrue(view.isHardwareAccelerated)
            controller.updateTerminalFrame(engine.accept((1..100).joinToString("\r\n") { "OLD_$it" }.toByteArray()))
        }
        composeRule.waitUntil(3_000) { controller.renderedOutputRevision > 0 }
        fun pixels() = composeRule.onNodeWithTag("hardware_resize").captureToImage().asAndroidBitmap()
        val contraction = composeRule.runOnUiThread {
            controller.viewport.lineHeightPx.toInt() * minOf(4, (controller.viewport.viewportHeightPx / controller.viewport.lineHeightPx).toInt() / 2).coerceAtLeast(1)
        }
        val middleHeight = 400 - contraction / 2
        val finalHeight = 400 - contraction
        val original = pixels()
        composeRule.runOnIdle {
            view.onImeAnimationLayout(0, contraction, 0)
            height.intValue = middleHeight
        }
        composeRule.runOnIdle { view.onImeAnimationLayout(contraction / 2, contraction, 0) }
        val intermediate = pixels()
        val expected = Bitmap.createBitmap(original, 0, contraction / 2, 240, middleHeight)
        assertTrue("Hardware display list must preserve glyph pixels", expected.sameAs(intermediate))
        val revision = controller.renderedOutputRevision
        composeRule.runOnIdle {
            controller.updateTerminalFrame(engine.accept("\u001b[?2026h\u001b[2J\u001b[HNEW FRAME\u001b[?2026l".toByteArray()))
        }
        composeRule.waitUntil(3_000) { controller.renderedOutputRevision > revision }
        val held = pixels()
        assertTrue("Early complete frame must wait for the animation", intermediate.sameAs(held))
        composeRule.runOnIdle { height.intValue = finalHeight }
        composeRule.runOnIdle { view.onImeAnimationLayout(contraction, contraction, 0) }
        val oldAtEnd = Bitmap.createBitmap(original, 0, contraction, 240, finalHeight)
        composeRule.waitUntil(3_000) {
            val current = pixels()
            val changed = !oldAtEnd.sameAs(current)
            current.recycle()
            changed
        }
        listOf(original, intermediate, expected, held, oldAtEnd).forEach { it.recycle() }
    }

    @Test fun targetIsSubmittedBeforeAnimationAndCompleteFrameWaitsUntilEndIncludingReversal() {
        val height = mutableIntStateOf(600)
        val controller = TerminalController()
        val engine = VtTerminalEngine()
        val sizes = mutableListOf<Int>()
        lateinit var view: FastTerminalView
        controller.setInputSink(object : TerminalInputSink { override fun send(bytes: ByteArray) = Unit },
            onResize = { columns, rows ->
                sizes.add(rows)
                controller.updateTerminalFrame(engine.resize(columns, rows), isResize = true)
            })
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AndroidView(factory = { context -> FastTerminalView(context).also {
                    view = it
                    it.attachController(controller)
                } }, modifier = Modifier.requiredSize(400.dp, height.intValue.dp))
            }
        }
        composeRule.waitUntil(3_000) { sizes.isNotEmpty() }
        composeRule.runOnIdle {
            controller.updateTerminalFrame(engine.accept(("\u001b[?2026h" + (1..100).joinToString("\r\n") { "OLD_$it" } + "\u001b[?2026l").toByteArray()))
        }
        composeRule.waitUntil(3_000) { controller.renderedOutputRevision > 0L }
        fun render() = Bitmap.createBitmap(400, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val contraction = composeRule.runOnUiThread {
            controller.viewport.lineHeightPx.toInt() * minOf(8, (controller.viewport.viewportHeightPx / controller.viewport.lineHeightPx).toInt() / 2).coerceAtLeast(1)
        }
        val middleHeight = 600 - contraction / 2
        val finalHeight = 600 - contraction
        var frozen: Bitmap? = null
        val before = sizes.size
        composeRule.runOnIdle {
            val old = render()
            frozen = Bitmap.createBitmap(old, 0, contraction / 2, 400, middleHeight)
            old.recycle()
            view.onImeAnimationLayout(0, contraction, 0)
            assertEquals((finalHeight / controller.viewport.lineHeightPx).toInt(), sizes.last())
            assertEquals(600, view.height) // remote target was sent before even the first size frame
            height.intValue = middleHeight
        }
        composeRule.runOnIdle {
            view.onImeAnimationLayout(contraction / 2, contraction, 0)
            controller.updateTerminalFrame(engine.accept("\u001b[?2026h\u001b[2J\u001b[HNEW\u001b[?2026l".toByteArray()))
        }
        composeRule.waitUntil(3_000) { controller.renderedOutputRevision > 1L }
        composeRule.runOnIdle {
            val actual = render()
            assertTrue("Even an early complete frame must not interrupt the IME animation", frozen!!.sameAs(actual))
            actual.recycle()
            height.intValue = finalHeight
        }
        composeRule.runOnIdle { view.onImeAnimationLayout(contraction, contraction, 0) }
        composeRule.waitUntil(3_000) {
            composeRule.runOnUiThread { controller.viewport.viewportHeightPx <= finalHeight && sizes.size == before + 1 }
        }
        val second = sizes.size
        composeRule.runOnIdle {
            view.onImeAnimationLayout(contraction, 0, 0)
            assertEquals((600 / controller.viewport.lineHeightPx).toInt(), sizes.last())
            height.intValue = middleHeight
        }
        composeRule.runOnIdle {
            view.onImeAnimationLayout(contraction / 2, 0, 0)
            assertEquals(second + 1, sizes.size)
            // Cancellation at an intermediate height must correct the target once, not keep it frozen.
            view.onImeAnimationLayout(contraction / 2, contraction / 2, 0)
            assertEquals((middleHeight / controller.viewport.lineHeightPx).toInt(), sizes.last())
            assertEquals(second + 2, sizes.size)
            frozen!!.recycle()
        }
    }

    private fun withImeAnimations(block: () -> Unit) {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val original = scales.associateWith { device.executeShellCommand("settings get global $it").trim() }
        try {
            scales.forEach { device.executeShellCommand("settings put global $it 1") }
            block()
        } finally {
            original.forEach { (setting, value) ->
                if (value == "null") device.executeShellCommand("settings delete global $setting")
                else device.executeShellCommand("settings put global $setting $value")
            }
        }
    }

    @Test fun actualKeyboardUsesProductionBridgeAndSubmitsTargetDuringBothAnimations() = withImeAnimations {
        val controller = TerminalController().apply { reportTerminalSize(1, 1) }
        val engine = VtTerminalEngine()
        lateinit var ime: WindowInsets
        lateinit var target: WindowInsets
        lateinit var density: Density
        lateinit var root: View
        lateinit var activityContext: android.content.Context
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        controller.setInputSink(object : TerminalInputSink { override fun send(bytes: ByteArray) = Unit },
            onResize = { columns, rows ->
                samples.add(Triple(ime.getBottom(density), target.getBottom(density), rows))
                controller.updateTerminalFrame(engine.resize(columns, rows), isResize = true)
                controller.updateTerminalFrame(engine.accept("\u001b[?2026h\u001b[2J\u001b[HRESIZED\u001b[?2026l".toByteArray()))
            })
        composeRule.setContent {
            ime = WindowInsets.ime
            target = WindowInsets.imeAnimationTarget
            density = LocalDensity.current
            root = LocalView.current.rootView
            activityContext = LocalView.current.context
            TerminalDetailInsetContainer {
                TerminalViewBridge(controller, rememberTerminalInputFocusRequester(), {})
            }
        }
        composeRule.waitUntil(5_000) { controller.terminalRows > 1 }
        lateinit var view: FastTerminalView
        lateinit var activity: Activity
        composeRule.runOnIdle {
            fun find(node: View): FastTerminalView? {
                if (node is FastTerminalView) return node
                if (node is ViewGroup) for (i in 0 until node.childCount) find(node.getChildAt(i))?.let { return it }
                return null
            }
            view = requireNotNull(find(root))
            var context = activityContext
            while (context !is Activity && context is ContextWrapper) context = context.baseContext
            activity = context as Activity
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val metrics = Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("IME test frame metrics").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            metrics.add(frame.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        composeRule.runOnIdle { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        try {
            for (cycle in 0..1) {
                val start = samples.size
                composeRule.runOnIdle { view.requestTerminalInputFocus(showKeyboard = true) }
                composeRule.waitUntil(8_000) {
                    composeRule.runOnUiThread {
                        ime.getBottom(density) > 0 && ime.getBottom(density) == target.getBottom(density) && samples.size > start
                    }
                }
                composeRule.runOnIdle {
                    assertTrue("Opening must report a target before IME completion", samples.drop(start).any { it.first != it.second })
                }
                val opened = samples.size
                composeRule.runOnIdle { ViewCompat.getWindowInsetsController(view)!!.hide(WindowInsetsCompat.Type.ime()) }
                composeRule.waitUntil(8_000) {
                    composeRule.runOnUiThread { ime.getBottom(density) == 0 && samples.size > opened }
                }
                composeRule.runOnIdle {
                    assertTrue("Closing must report a target before IME completion", samples.drop(opened).any { it.first != it.second })
                    assertEquals((controller.viewport.viewportHeightPx / controller.viewport.lineHeightPx).toInt(), controller.terminalRows)
                }
            }
        } finally {
            composeRule.runOnIdle { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            val result = JSONObject().put("frame_total_ns", JSONArray(synchronized(metrics) { metrics.toList() }))
                .put("resize_samples_current_target_rows", JSONArray(samples.map { JSONArray(listOf(it.first, it.second, it.third)) }))
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ime-frame-metrics.json").writeText(result.toString())
        }
    }
}
