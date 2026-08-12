package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkAction
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkActionRequest
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastTerminalSelectionInteractionTest {
    @Test
    fun attachingAPrepopulatedTabSynchronizesItsViewportImmediately() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstController = TerminalController()
                val duplicatedController = TerminalController().apply {
                    buffer.append(TerminalLine.plain("duplicate tab is ready"))
                }

                val view = attachTerminalView(activity, firstController)
                view.attachController(duplicatedController)
                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)

                assertEquals(1, duplicatedController.viewport.lineCount)
                assertTrue(duplicatedController.viewport.viewportHeightPx > 0)
                assertEquals(1, duplicatedController.viewport.visibleRows(0).count)
                assertTrue(info.text.toString().contains("duplicate tab is ready"))
                info.recycle()
            }
        }
    }

    @Test
    fun nativeViewExposesLongPressSelectAllAndCopyAccessibilityActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(TerminalLine.plain("alpha bravo charlie delta echo foxtrot golf hotel"))
                    buffer.append(TerminalLine.plain("second row"))
                }
                val view = attachTerminalView(activity, controller)

                assertTrue(view.performLongClick())
                assertNotNull(view.selectedTextForTesting())

                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertTrue(info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COPY })
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_select_all })

                assertTrue(view.performAccessibilityAction(R.id.terminal_action_select_all, null))
                assertEquals(
                    "alpha bravo charlie delta echo foxtrot golf hotel\nsecond row",
                    view.selectedTextForTesting(),
                )
                assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null))
                info.recycle()
            }
        }
    }

    @Test
    fun tappingOsc8LinkOnlyOffersActionsAndNeverOpensItDirectly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(
                        TerminalLine.styled(
                            listOf(
                                TerminalRun(
                                    text = "docs",
                                    hyperlink = TerminalHyperlink("https://example.test/docs"),
                                    startColumn = 0,
                                    columnWidth = 4,
                                ),
                            ),
                        ),
                    )
                }
                val requests = mutableListOf<TerminalLinkActionRequest>()
                val view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                }

                tapFirstCell(view)

                assertTrue(requests.isEmpty())
                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_open_link })
                assertTrue(view.performAccessibilityAction(R.id.terminal_action_open_link, null))
                assertEquals(listOf(TerminalLinkAction.OPEN), requests.map { it.action })
                info.recycle()
            }
        }
    }

    @Test
    fun unsafeOsc8LinkHasCopyActionButNoOpenAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(
                        TerminalLine.styled(
                            listOf(
                                TerminalRun(
                                    text = "file",
                                    hyperlink = TerminalHyperlink("file:///tmp/result"),
                                    startColumn = 0,
                                    columnWidth = 4,
                                ),
                            ),
                        ),
                    )
                }
                val requests = mutableListOf<TerminalLinkActionRequest>()
                val clipboardRequests = mutableListOf<TerminalClipboardRequest>()
                val view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                    setClipboardActionCallback { request ->
                        clipboardRequests += request
                        true
                    }
                }

                tapFirstCell(view)

                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertFalse(info.actionList.any { it.id == R.id.terminal_action_open_link })
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_copy_link })
                assertFalse(view.performAccessibilityAction(R.id.terminal_action_open_link, null))
                assertTrue(view.performAccessibilityAction(R.id.terminal_action_copy_link, null))
                assertTrue(requests.isEmpty())
                assertEquals(listOf(TerminalClipboardContentKind.LINK), clipboardRequests.map { it.kind })
                info.recycle()
            }
        }
    }

    @Test
    fun controllerHistoryAnchorsResolveAcrossAppendAndDisappearAfterTrim() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val controller = TerminalController(TerminalBuffer(capacity = 2))
                controller.buffer.append(TerminalLine.plain("first"))
                controller.buffer.append(TerminalLine.plain("second"))
                val first = requireNotNull(controller.selectionLineAt(0))

                controller.buffer.append(TerminalLine.plain("third"))

                assertEquals(TerminalLineSpace.PRIMARY_HISTORY, first.anchor.space)
                assertNull(controller.selectionIndexOf(first.anchor))
                assertEquals("second", controller.selectionLineAt(0)?.line?.text)
            }
        }
    }

    private fun attachTerminalView(
        activity: MainActivity,
        controller: TerminalController,
    ): FastTerminalView = FastTerminalView(activity).also { view ->
        activity.setContentView(view)
        view.attachController(controller)
        val width = 1_080
        val height = 600
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun tapFirstCell(view: FastTerminalView) {
        val now = SystemClock.uptimeMillis()
        val density = view.resources.displayMetrics.density
        val x = 8f * density + 2f
        val y = 5f * density + 4f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            view.onTouchEvent(down)
        } finally {
            down.recycle()
        }
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.onTouchEvent(up)
        } finally {
            up.recycle()
        }
    }
}
