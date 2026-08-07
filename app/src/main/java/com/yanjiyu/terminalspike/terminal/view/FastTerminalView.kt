package com.yanjiyu.terminalspike.terminal.view

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import com.yanjiyu.terminalspike.performance.FrameStatsCollector
import com.yanjiyu.terminalspike.terminal.TerminalContentListener
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import kotlin.math.ceil
import kotlin.math.sign

class FastTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : android.view.View(context, attrs), TerminalContentListener {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint()
    private val normalTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val boldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val italicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val boldItalicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private val scroller = OverScroller(context)
    private val horizontalPaddingPx = 8f * resources.displayMetrics.density
    private val verticalPaddingPx = 5f * resources.displayMetrics.density
    private var fontMetrics = textPaint.fontMetrics
    private var lineHeightPx = 1f
    private var cellWidthPx = 1f
    private var mouseWheelRemainderPx = 0f
    private var terminalController: TerminalController? = null
    private val gestureActions = TerminalGestureActions(
        stopFling = { scroller.forceFinished(true) },
        requestFocus = { requestFocus() },
        scrollBy = ::handleGestureScroll,
        fling = ::startFling,
        showKeyboard = ::showKeyboard,
        performClick = { performClick() },
    )
    private val gestureDetector = GestureDetector(
        context,
        TerminalGestureHandler(gestureActions),
    )
    private val frameStatsCollector = FrameStatsCollector { timing ->
        val controller = terminalController ?: return@FrameStatsCollector
        controller.reportFrameTiming(timing, controller.viewport.visibleRows(0).count)
    }
    private val publishIdleStats = Runnable { frameStatsCollector.publishIdle() }
    private val publishTerminalSize = Runnable { reportTerminalSizeNow() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Native terminal renderer"
        textPaint.typeface = normalTypeface
        setBackgroundColor(TerminalPalette.BACKGROUND)
    }

    fun attachController(controller: TerminalController) {
        if (terminalController === controller) return
        terminalController?.removeListener(this)
        terminalController = controller
        updateTextMetrics(controller.fontSizeSp)
        controller.addListener(this)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(publishIdleStats)
        removeCallbacks(publishTerminalSize)
        terminalController?.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onTerminalContentChanged() {
        val controller = terminalController ?: return
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        terminalController?.viewport?.updateGeometry(
            heightPx = (height - verticalPaddingPx * 2f).toInt().coerceAtLeast(0),
            newLineHeightPx = lineHeightPx,
        )
        scheduleTerminalSizeReport()
    }

    @SuppressLint("UseKtx")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val controller = terminalController ?: return
        val drawStartNanos = System.nanoTime()
        val viewport = controller.viewport
        val rows = viewport.visibleRows(OVERSCAN_ROWS)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        var rowIndex = rows.first
        while (rowIndex < rows.lastExclusive) {
            val line = controller.lineAt(rowIndex)
            if (line != null) {
                val rowTop = verticalPaddingPx + rowIndex * lineHeightPx - viewport.scrollY
                val baseline = rowTop - fontMetrics.ascent
                drawLine(canvas, line.runs, baseline, rowTop)
            }
            rowIndex += 1
        }

        drawCursor(canvas, controller, viewport.scrollY)
        if (!viewport.autoFollow) drawNewOutputBadge(canvas)
        canvas.restore()
        frameStatsCollector.recordDraw(drawStartNanos, System.nanoTime())
        removeCallbacks(publishIdleStats)
        postDelayed(publishIdleStats, FrameStatsCollector.IDLE_DELAY_MS)
    }

    private fun drawLine(canvas: Canvas, runs: List<TerminalRun>, baseline: Float, rowTop: Float) {
        var x = horizontalPaddingPx
        val rightEdge = width - horizontalPaddingPx
        for (run in runs) {
            if (x >= rightEdge) break
            applyStyle(run)
            val estimatedCharacters = ceil((rightEdge - x) / cellWidthPx).toInt() + 2
            var end = minOf(run.text.length, estimatedCharacters)
            if (end in 1 until run.text.length && Character.isHighSurrogate(run.text[end - 1])) end -= 1
            if (end <= 0) continue
            val runWidth = textPaint.measureText(run.text, 0, end)
            val foreground = if (run.style.inverse) run.style.background else run.style.foreground
            val background = if (run.style.inverse) run.style.foreground else run.style.background
            if (background != TerminalPalette.BACKGROUND) {
                fillPaint.color = background
                canvas.drawRect(x, rowTop, x + runWidth, rowTop + lineHeightPx, fillPaint)
            }
            textPaint.color = foreground
            canvas.drawText(run.text, 0, end, x, baseline, textPaint)
            x += runWidth
            if (end < run.text.length) break
        }
    }

    private fun applyStyle(run: TerminalRun) {
        textPaint.typeface = when {
            run.style.bold && run.style.italic -> boldItalicTypeface
            run.style.bold -> boldTypeface
            run.style.italic -> italicTypeface
            else -> normalTypeface
        }
        textPaint.isUnderlineText = run.style.underline
    }

    private fun drawCursor(canvas: Canvas, controller: TerminalController, scrollY: Float) {
        val cursor = controller.cursor
        if (!cursor.visible || cursor.row !in 0 until controller.lineCount()) return
        val left = horizontalPaddingPx + cursor.column * cellWidthPx
        val top = verticalPaddingPx + cursor.row * lineHeightPx - scrollY
        if (top + lineHeightPx < 0f || top > height) return
        fillPaint.color = TerminalPalette.CURSOR
        fillPaint.alpha = CURSOR_ALPHA
        canvas.drawRect(left, top, left + cellWidthPx, top + lineHeightPx, fillPaint)
        fillPaint.alpha = 255
    }

    private fun drawNewOutputBadge(canvas: Canvas) {
        val label = "NEW OUTPUT"
        textPaint.typeface = boldTypeface
        textPaint.isUnderlineText = false
        textPaint.textSize = spToPx(11f)
        val labelWidth = textPaint.measureText(label)
        val right = width - horizontalPaddingPx
        val top = verticalPaddingPx
        fillPaint.color = TerminalPalette.BLUE
        canvas.drawRoundRect(right - labelWidth - 16f, top, right, top + 26f, 7f, 7f, fillPaint)
        textPaint.color = TerminalPalette.WHITE
        canvas.drawText(label, right - labelWidth - 8f, top + 18f, textPaint)
        terminalController?.let { updateTextPaintSize(it.fontSizeSp) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mouseWheelRemainderPx = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> gestureActions.onMultiPointerGesture()
            MotionEvent.ACTION_CANCEL -> {
                gestureActions.onCancel()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_UP -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        terminalController?.reportFocus(gainFocus)
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val controller = terminalController ?: return
        val previous = controller.viewport.autoFollow
        controller.viewport.scrollTo(scroller.currY.toFloat())
        controller.reportViewportStateIfChanged(previous)
        postInvalidateOnAnimation()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val controller = terminalController ?: return null
        TerminalInputConnection.configureEditorInfo(outAttrs)
        return TerminalInputConnection(this, controller)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val controller = terminalController ?: return super.onKeyDown(keyCode, event)
        TerminalKeySequences.forKeyCode(keyCode)?.let { sequence ->
            controller.send(sequence)
            return true
        }
        if (event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            controller.send(byteArrayOf((keyCode - KeyEvent.KEYCODE_A + 1).toByte()))
            return true
        }
        val unicode = event.unicodeChar
        if (unicode > 0 && !Character.isISOControl(unicode)) {
            controller.send(String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun pageScroll(direction: Int) {
        scrollViewportBy(direction * terminalController.orEmptyViewportHeight())
    }

    private fun scrollViewportBy(distanceY: Float) {
        val controller = terminalController ?: return
        val previous = controller.viewport.autoFollow
        controller.viewport.scrollBy(distanceY)
        controller.reportViewportStateIfChanged(previous)
        postInvalidateOnAnimation()
    }

    private fun handleGestureScroll(distanceY: Float, x: Float, y: Float) {
        val controller = terminalController ?: return
        if (!controller.isMouseTrackingEnabled()) {
            mouseWheelRemainderPx = 0f
            scrollViewportBy(distanceY)
            return
        }

        mouseWheelRemainderPx += distanceY
        val stepPx = lineHeightPx * MOUSE_WHEEL_LINES_PER_STEP
        var steps = 0
        while (kotlin.math.abs(mouseWheelRemainderPx) >= stepPx && steps < MAX_MOUSE_WHEEL_STEPS_PER_EVENT) {
            val direction = mouseWheelRemainderPx.sign
            controller.sendMouseWheel(
                up = direction < 0f,
                column = ((x - horizontalPaddingPx) / cellWidthPx).toInt(),
                row = ((y - verticalPaddingPx) / lineHeightPx).toInt(),
            )
            mouseWheelRemainderPx -= direction * stepPx
            steps += 1
        }
    }

    private fun startFling(velocityY: Float) {
        val controller = terminalController ?: return
        if (controller.isMouseTrackingEnabled()) return
        scroller.fling(
            0,
            controller.viewport.scrollY.toInt(),
            0,
            -velocityY.toInt(),
            0,
            0,
            0,
            controller.viewport.maximumScrollY.toInt(),
        )
        postInvalidateOnAnimation()
    }

    private fun showKeyboard() {
        val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        inputMethodManager?.showSoftInput(this, 0)
    }

    private fun updateTextMetrics(fontSizeSp: Float) {
        updateTextPaintSize(fontSizeSp)
        fontMetrics = textPaint.fontMetrics
        lineHeightPx = ceil(fontMetrics.descent - fontMetrics.ascent + resources.displayMetrics.density).coerceAtLeast(1f)
        cellWidthPx = textPaint.measureText("M").coerceAtLeast(1f)
        terminalController?.viewport?.updateGeometry(
            heightPx = (height - verticalPaddingPx * 2f).toInt().coerceAtLeast(0),
            newLineHeightPx = lineHeightPx,
        )
        scheduleTerminalSizeReport()
    }

    private fun updateTextPaintSize(fontSizeSp: Float) {
        textPaint.textSize = spToPx(fontSizeSp)
    }

    private fun scheduleTerminalSizeReport() {
        removeCallbacks(publishTerminalSize)
        postDelayed(publishTerminalSize, TERMINAL_RESIZE_SETTLE_MS)
    }

    private fun reportTerminalSizeNow() {
        val columns = ((width - horizontalPaddingPx * 2f) / cellWidthPx).toInt().coerceAtLeast(1)
        val rows = ((height - verticalPaddingPx * 2f) / lineHeightPx).toInt().coerceAtLeast(1)
        terminalController?.reportTerminalSize(columns, rows)
    }

    private fun spToPx(sp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp,
        resources.displayMetrics,
    )

    private fun TerminalController?.orEmptyViewportHeight(): Float =
        this?.viewport?.viewportHeightPx?.toFloat() ?: 0f

    companion object {
        private const val OVERSCAN_ROWS = 2
        private const val CURSOR_ALPHA = 150
        private const val MOUSE_WHEEL_LINES_PER_STEP = 1.5f
        private const val MAX_MOUSE_WHEEL_STEPS_PER_EVENT = 6
        private const val TERMINAL_RESIZE_SETTLE_MS = 120L
    }
}
