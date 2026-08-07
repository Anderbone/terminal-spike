package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGestureActionsTest {
    @Test
    fun tapStopsFlingRequestsFocusAndOpensKeyboardOnce() {
        val events = mutableListOf<String>()
        val actions = actions(
            stopFling = { events += "stop" },
            requestFocus = { events += "focus" },
            showKeyboard = { events += "keyboard" },
            performClick = { events += "click" },
        )

        actions.onTouchDown()
        assertTrue(actions.onTapConfirmed())
        assertFalse(actions.onTapConfirmed())

        assertEquals(listOf("stop", "focus", "keyboard", "click"), events)
    }

    @Test
    fun dragScrollsWithoutOpeningKeyboardOrClicking() {
        var scrollDistance = 0f
        var keyboardCalls = 0
        var clickCalls = 0
        val actions = actions(
            scrollBy = { distance, _, _ -> scrollDistance += distance },
            showKeyboard = { keyboardCalls += 1 },
            performClick = { clickCalls += 1 },
        )

        actions.onTouchDown()
        actions.onScroll(-42f, 10f, 20f)

        assertEquals(-42f, scrollDistance)
        assertFalse(actions.onTapConfirmed())
        assertEquals(0, keyboardCalls)
        assertEquals(0, clickCalls)
    }

    @Test
    fun touchDownStopsFlingImmediately() {
        var stopCalls = 0
        val actions = actions(stopFling = { stopCalls += 1 })

        actions.onTouchDown()

        assertEquals(1, stopCalls)
    }

    @Test
    fun flingDoesNotOpenKeyboardOrActAsClick() {
        var flingVelocity = 0f
        var keyboardCalls = 0
        var clickCalls = 0
        val actions = actions(
            fling = { flingVelocity = it },
            showKeyboard = { keyboardCalls += 1 },
            performClick = { clickCalls += 1 },
        )

        actions.onTouchDown()
        actions.onFling(1_500f)

        assertEquals(1_500f, flingVelocity)
        assertFalse(actions.onTapConfirmed())
        assertEquals(0, keyboardCalls)
        assertEquals(0, clickCalls)
    }

    @Test
    fun cancelDoesNotOpenKeyboardOrActAsClick() {
        var keyboardCalls = 0
        var clickCalls = 0
        val actions = actions(
            showKeyboard = { keyboardCalls += 1 },
            performClick = { clickCalls += 1 },
        )

        actions.onTouchDown()
        actions.onCancel()

        assertFalse(actions.onTapConfirmed())
        assertEquals(0, keyboardCalls)
        assertEquals(0, clickCalls)
    }

    @Test
    fun multiPointerGestureDoesNotOpenKeyboardOrActAsClick() {
        var keyboardCalls = 0
        var clickCalls = 0
        val actions = actions(
            showKeyboard = { keyboardCalls += 1 },
            performClick = { clickCalls += 1 },
        )

        actions.onTouchDown()
        actions.onMultiPointerGesture()

        assertFalse(actions.onTapConfirmed())
        assertEquals(0, keyboardCalls)
        assertEquals(0, clickCalls)
    }

    @Test
    fun swipeAboveBottomKeepsAutoFollowDisabled() {
        val viewport = TerminalViewport().apply {
            updateGeometry(heightPx = 200, newLineHeightPx = 20f)
            updateContent(newLineCount = 100, newOldestLineId = 0L)
        }
        val actions = actions(scrollBy = { distance, _, _ -> viewport.scrollBy(distance) })

        actions.onTouchDown()
        actions.onScroll(-100f, 0f, 0f)
        assertFalse(viewport.autoFollow)

        actions.onTouchDown()
        actions.onScroll(-100f, 0f, 0f)

        assertFalse(viewport.autoFollow)
        assertEquals(viewport.maximumScrollY - 200f, viewport.scrollY)
    }

    private fun actions(
        stopFling: () -> Unit = {},
        requestFocus: () -> Unit = {},
        scrollBy: (Float, Float, Float) -> Unit = { _, _, _ -> },
        fling: (Float) -> Unit = {},
        showKeyboard: () -> Unit = {},
        performClick: () -> Unit = {},
    ) = TerminalGestureActions(
        stopFling = stopFling,
        requestFocus = requestFocus,
        scrollBy = scrollBy,
        fling = fling,
        showKeyboard = showKeyboard,
        performClick = performClick,
    )
}
