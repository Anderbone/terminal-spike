package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGestureActionsTest {
    @Test
    fun mouseTapNavigatesWithoutKeyboardAndDoubleTapOpensItWithoutAnotherClick() {
        val events = mutableListOf<String>()
        val actions = actions(
            handleTap = { x, y -> events += "mouse:$x:$y"; true },
            canDoubleTapToType = { _, _ -> true },
            showKeyboard = { events += "keyboard" },
            performClick = { events += "accessibility" },
        )
        actions.onTouchDown(12f, 16f)
        assertTrue(actions.onTapConfirmed(12f, 16f))
        assertEquals(listOf("mouse:12.0:16.0", "accessibility"), events)
        assertTrue(actions.onDoubleTap(12f, 16f))
        assertFalse(actions.onTapConfirmed(12f, 16f))
        assertEquals(listOf("mouse:12.0:16.0", "accessibility", "keyboard", "accessibility"), events)
    }

    @Test
    fun doubleTapDoesNotForceTypingForLinksOrSelectionHandles() {
        var keyboards = 0
        val actions = actions(showKeyboard = { keyboards++ })
        actions.onTouchDown(1f, 1f)
        assertFalse(actions.onDoubleTap(1f, 1f))
        assertEquals(0, keyboards)
        val selecting = actions(
            selectionHandleAt = { _, _ -> TerminalSelectionEndpoint.START },
            canDoubleTapToType = { _, _ -> true },
            showKeyboard = { keyboards++ },
        )
        selecting.onTouchDown(1f, 1f)
        assertFalse(selecting.onDoubleTap(1f, 1f))
        assertEquals(0, keyboards)
    }

    @Test
    fun tapStopsFlingRequestsFocusAndOpensKeyboardOnce() {
        val events = mutableListOf<String>()
        val actions = actions(
            stopFling = { events += "stop" },
            requestFocus = { events += "focus" },
            showKeyboard = { events += "keyboard" },
            performClick = { events += "click" },
        )

        actions.onTouchDown(4f, 8f)
        assertTrue(actions.onTapConfirmed(4f, 8f))
        assertFalse(actions.onTapConfirmed(4f, 8f))

        assertEquals(listOf("stop", "focus", "keyboard", "click"), events)
    }

    @Test
    fun dragScrollsWithoutOpeningKeyboardOrClicking() {
        var scrollDistance = 0f
        var scrollPointerCount = 0
        var keyboardCalls = 0
        var clickCalls = 0
        val actions = actions(
            scrollBy = { distance, _, _, pointerCount ->
                scrollDistance += distance
                scrollPointerCount = pointerCount
            },
            showKeyboard = { keyboardCalls += 1 },
            performClick = { clickCalls += 1 },
        )

        actions.onTouchDown(0f, 0f)
        actions.onScroll(-42f, 10f, 20f, pointerCount = 2)

        assertEquals(-42f, scrollDistance)
        assertEquals(2, scrollPointerCount)
        assertFalse(actions.onTapConfirmed(10f, 20f))
        assertEquals(0, keyboardCalls)
        assertEquals(0, clickCalls)
    }

    @Test
    fun touchDownStopsFlingImmediately() {
        var stopCalls = 0
        val actions = actions(stopFling = { stopCalls += 1 })

        actions.onTouchDown(0f, 0f)

        assertEquals(1, stopCalls)
    }

    @Test
    fun flingDoesNotOpenKeyboardOrActAsClick() {
        var flingVelocity = 0f
        var keyboardCalls = 0
        var clickCalls = 0
        val actions = actions(
            fling = { velocity, _, _ -> flingVelocity = velocity },
            showKeyboard = { keyboardCalls += 1 },
            performClick = { clickCalls += 1 },
        )

        actions.onTouchDown(0f, 0f)
        actions.onFling(1_500f)

        assertEquals(1_500f, flingVelocity)
        assertFalse(actions.onTapConfirmed(0f, 0f))
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

        actions.onTouchDown(0f, 0f)
        actions.onCancel()

        assertFalse(actions.onTapConfirmed(0f, 0f))
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

        actions.onTouchDown(0f, 0f)
        actions.onMultiPointerGesture()

        assertFalse(actions.onTapConfirmed(0f, 0f))
        assertEquals(0, keyboardCalls)
        assertEquals(0, clickCalls)
    }

    @Test
    fun swipeAboveBottomKeepsAutoFollowDisabled() {
        val viewport = TerminalViewport().apply {
            updateGeometry(heightPx = 200, newLineHeightPx = 20f)
            updateContent(newLineCount = 100, newOldestLineId = 0L)
        }
        val actions = actions(scrollBy = { distance, _, _, _ -> viewport.scrollBy(distance) })

        actions.onTouchDown(0f, 0f)
        actions.onScroll(-100f, 0f, 0f)
        assertFalse(viewport.autoFollow)

        actions.onTouchDown(0f, 0f)
        actions.onScroll(-100f, 0f, 0f)

        assertFalse(viewport.autoFollow)
        assertEquals(viewport.maximumScrollY - 200f, viewport.scrollY)
    }

    @Test
    fun longPressStartsLocalSelectionAndRoutesDragWithoutRemoteScroll() {
        val events = mutableListOf<String>()
        val actions = actions(
            scrollBy = { _, _, _, _ -> events += "scroll" },
            startSelection = { x, y ->
                events += "start:$x:$y"
                true
            },
            dragSelection = { endpoint, x, y -> events += "drag:$endpoint:$x:$y" },
            finishSelectionDrag = { committed -> events += "finish:$committed" },
        )

        actions.onTouchDown(10f, 20f)
        actions.onLongPress(10f, 20f)
        actions.onScroll(40f, 30f, 50f)
        actions.onTouchUp()

        assertEquals(
            listOf(
                "start:10.0:20.0",
                "drag:END:30.0:50.0",
                "finish:true",
            ),
            events,
        )
    }

    @Test
    fun longPressOnLinkShowsLinkActionsWithoutStartingTextSelection() {
        val events = mutableListOf<String>()
        val actions = actions(
            handleLongPress = { x, y ->
                events += "link:$x:$y"
                true
            },
            startSelection = { _, _ -> events += "selection"; true },
            finishSelectionDrag = { events += "finish:$it" },
        )

        actions.onTouchDown(10f, 20f)
        actions.onLongPress(10f, 20f)
        actions.onTouchUp()

        assertEquals(listOf("link:10.0:20.0"), events)
    }

    @Test
    fun draggingExistingHandleDoesNotStartSelectionOrFling() {
        val events = mutableListOf<String>()
        val actions = actions(
            fling = { _, _, _ -> events += "fling" },
            selectionHandleAt = { _, _ -> TerminalSelectionEndpoint.START },
            startSelection = { _, _ -> events += "start"; true },
            dragSelection = { endpoint, _, _ -> events += "drag:$endpoint" },
            finishSelectionDrag = { committed -> events += "finish:$committed" },
        )

        actions.onTouchDown(1f, 2f)
        actions.onScroll(5f, 2f, 3f)
        actions.onFling(900f)
        actions.onTouchUp()

        assertEquals(listOf("drag:START", "finish:true"), events)
    }

    @Test
    fun cancelledSelectionIsFinishedWithoutCommittingCopyEligibleState() {
        val commits = mutableListOf<Boolean>()
        val actions = actions(
            startSelection = { _, _ -> true },
            finishSelectionDrag = { commits += it },
        )

        actions.onTouchDown(1f, 2f)
        actions.onLongPress(1f, 2f)
        actions.onCancel()

        assertEquals(listOf(false), commits)
    }

    @Test
    fun explicitLinkTapActionSuppressesKeyboardWithoutSkippingClickAccessibility() {
        val events = mutableListOf<String>()
        val actions = actions(
            showKeyboard = { events += "keyboard" },
            performClick = { events += "click" },
            handleTap = { x, y ->
                events += "link:$x:$y"
                true
            },
        )

        actions.onTouchDown(12f, 16f)
        assertTrue(actions.onTapConfirmed(12f, 16f))

        assertEquals(listOf("link:12.0:16.0", "click"), events)
    }

    private fun actions(
        stopFling: () -> Unit = {},
        requestFocus: () -> Unit = {},
        scrollBy: (Float, Float, Float, Int) -> Unit = { _, _, _, _ -> },
        fling: (Float, Float, Float) -> Unit = { _, _, _ -> },
        showKeyboard: () -> Unit = {},
        performClick: () -> Unit = {},
        handleTap: (Float, Float) -> Boolean = { _, _ -> false },
        handleLongPress: (Float, Float) -> Boolean = { _, _ -> false },
        selectionHandleAt: (Float, Float) -> TerminalSelectionEndpoint? = { _, _ -> null },
        startSelection: (Float, Float) -> Boolean = { _, _ -> false },
        dragSelection: (TerminalSelectionEndpoint, Float, Float) -> Unit = { _, _, _ -> },
        finishSelectionDrag: (Boolean) -> Unit = {},
        canDoubleTapToType: (Float, Float) -> Boolean = { _, _ -> false },
    ) = TerminalGestureActions(
        stopFling = stopFling,
        requestFocus = requestFocus,
        scrollBy = scrollBy,
        fling = fling,
        showKeyboard = showKeyboard,
        performClick = performClick,
        handleTap = handleTap,
        handleLongPress = handleLongPress,
        selectionHandleAt = selectionHandleAt,
        startSelection = startSelection,
        dragSelection = dragSelection,
        finishSelectionDrag = finishSelectionDrag,
        canDoubleTapToType = canDoubleTapToType,
    )
}
