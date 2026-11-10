package com.yanjiyu.terminalspike.terminal.view

import android.content.Context
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.yanjiyu.terminalspike.BuildConfig
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Gives the live terminal back the width occupied by Herdr's remote sidebar, then clips that
 * sidebar locally. Android dispatches input in the child's original coordinates. The renderer,
 * selection, mouse reporting and native history all keep the same unmodified terminal grid.
 */
internal class HerdrTerminalContainer(context: Context) : ViewGroup(context) {
    val terminal = FastTerminalView(context)
    var hiddenSidebarColumns: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    private var hiddenWidth = 0

    init {
        clipChildren = true
        addView(terminal)
    }

    private var swipeDirection = 0
    private var swipeOwned = false
    private var swipeTriggered = false
    private var swipeStartDirection = 0
    private var swipeAccepted = false
    private var swipeReason = "idle"
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeOwned = false
            swipeTriggered = false
            swipeDirection = terminal.mobileNavigationSwipeDirection()
            downX = event.x
            downY = event.y
            swipeStartDirection = swipeDirection
            swipeAccepted = false
            swipeReason = if (swipeDirection == 0) "no_mobile_target" else "candidate"
            if (BuildConfig.DEBUG) android.util.Log.d("HerdrNavigationSwipe",
                "start t=${event.eventTime} direction=$swipeDirection x=$downX y=$downY width=$width height=$height")
        }
        if (!swipeOwned && swipeDirection != 0) {
            if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                swipeDirection = 0
                swipeReason = "multiple_pointers"
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = event.x - downX
                val dy = event.y - downY
                if ((abs(dy) > touchSlop && abs(dy) >= abs(dx)) || dx * swipeDirection < -touchSlop) {
                    swipeDirection = 0
                    swipeReason = if (dx * swipeStartDirection < -touchSlop) "wrong_direction" else "vertical_first"
                } else if (dx * swipeDirection > touchSlop &&
                    abs(dx) > abs(dy) * 2f
                ) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    // Cancelling the child releases its interception lock. Keep the
                    // Compose AndroidView stream while this container owns the swipe.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    swipeOwned = true
                    swipeReason = "horizontal_owned"
                }
            }
        }
        if (swipeOwned) {
            if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) swipeDirection = 0
            if (!swipeTriggered && swipeDirection != 0 && event.actionMasked == MotionEvent.ACTION_MOVE &&
                (event.x - downX) * swipeDirection > maxOf(touchSlop * 3f, 48f * resources.displayMetrics.density)
            ) {
                swipeTriggered = true
                swipeAccepted = terminal.swipeMobileNavigation(swipeDirection)
                swipeReason = if (swipeAccepted) "click_accepted" else "click_rejected"
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                traceSwipeEnd(event)
                swipeOwned = false
                swipeDirection = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) traceSwipeEnd(event)
        return handled
    }

    private fun traceSwipeEnd(event: MotionEvent) {
        if (BuildConfig.DEBUG) android.util.Log.d("HerdrNavigationSwipe",
            "end t=${event.eventTime} action=${event.actionMasked} direction=$swipeStartDirection" +
                " dx=${event.x - downX} dy=${event.y - downY} reason=$swipeReason" +
                " owned=$swipeOwned triggered=$swipeTriggered accepted=$swipeAccepted")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        hiddenWidth = ceil(hiddenSidebarColumns.coerceIn(0, 200) * terminal.terminalCellWidthPx).toInt()
        terminal.measure(
            MeasureSpec.makeMeasureSpec(width + hiddenWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        terminal.layout(-hiddenWidth, 0, right - left, bottom - top)
    }
}
