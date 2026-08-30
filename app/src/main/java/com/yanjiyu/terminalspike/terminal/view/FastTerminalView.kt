package com.yanjiyu.terminalspike.terminal.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.performance.FrameStatsCollector
import com.yanjiyu.terminalspike.terminal.TerminalContentChange
import com.yanjiyu.terminalspike.terminal.TerminalContentListener
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalBellEvent
import com.yanjiyu.terminalspike.terminal.TerminalFindResult
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import com.yanjiyu.terminalspike.terminal.model.TerminalCellWidth
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.model.TerminalTheme
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkAction
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkActionCallback
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkActionRequest
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkPolicy
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkResolver
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkTarget
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionEndpoint
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

class FastTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : android.view.View(context, attrs), TerminalContentListener {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint()
    private val drawClipBounds = Rect()
    private var normalTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private var boldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var italicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private var boldItalicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private var legacySymbolNormalTypeface: Typeface? = null
    private var legacySymbolBoldTypeface: Typeface? = null
    private var legacySymbolItalicTypeface: Typeface? = null
    private var legacySymbolBoldItalicTypeface: Typeface? = null
    private val scroller = OverScroller(context)
    private val horizontalPaddingPx = 8f * resources.displayMetrics.density
    private val verticalPaddingPx = 5f * resources.displayMetrics.density
    private var fontMetrics = textPaint.fontMetrics
    private var lineHeightPx = 1f
    private var cellWidthPx = 1f
    private var baselineOffsetPx = 1f
    private val scrollGestureRouter = TerminalScrollGestureRouter()
    private val mouseWheelAccumulator = TerminalMouseWheelAccumulator(MAX_MOUSE_WHEEL_STEPS_PER_EVENT)
    private var flingDestination = TerminalScrollDestination.NONE
    private val selection = TerminalSelectionModel()
    private var terminalController: TerminalController? = null
    private var terminalTheme: TerminalTheme = TerminalThemes.current
    private var appliedRendererProfile: TerminalRendererProfile? = null
    private var cursorStyle = CursorStyle.BLOCK
    private var cursorBlinkEnabled = true
    private var directInputEnabled = true
    private var activeLink: TerminalLinkTarget? = null
    private var findHighlight: NativeFindHighlight? = null
    private var selectionActionMode: ActionMode? = null
    private var lastVisualBellSequence = 0L
    private var visualBellUntilUptimeMillis = 0L
    private var pendingDeadAccent = 0
    private var pinchZoomTargetFontSizeSp = 14f
    private var pinchZoomConsumed = false
    private var activeInputConnection: TerminalInputConnection? = null
    private val defaultClipboardWriter = TerminalClipboardWriter(
        context,
        (context.applicationContext as? TerminalSpikeApplication)
            ?.container
            ?.terminalClipboardClearScheduler,
    )
    private var clipboardActionCallback = TerminalClipboardActionCallback { request ->
        defaultClipboardWriter.write(request) != null
    }
    private var imageContentCallback: TerminalImageContentCallback? = null
    private var linkActionCallback = TerminalLinkActionCallback {
        // Opening is intentionally inert until a surrounding policy callback is installed.
    }
    private var preImeBackCallback: (() -> Unit)? = null
    private val directInputSink = object : TerminalInputSink {
        override fun send(bytes: ByteArray) {
            if (directInputEnabled) terminalController?.send(bytes)
        }
    }
    private val gestureActions = TerminalGestureActions(
        stopFling = {
            scrollGestureRouter.onGestureStart()
            stopActiveFling()
        },
        requestFocus = { if (directInputEnabled) requestFocus() },
        scrollBy = ::handleGestureScroll,
        fling = ::startFling,
        showKeyboard = { if (directInputEnabled) showKeyboard() },
        performClick = { performClick() },
        handleTap = ::handleTerminalTap,
        handleLongPress = ::showLinkActionsAt,
        selectionHandleAt = ::selectionHandleAt,
        startSelection = ::startSelectionAt,
        dragSelection = ::dragSelection,
        finishSelectionDrag = ::finishSelectionDrag,
    )
    private val gestureDetector = GestureDetector(
        context,
        TerminalGestureHandler(gestureActions),
    )
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                val controller = terminalController ?: return false
                if (appliedRendererProfile?.pinchZoomEnabled != true) return false
                pinchZoomTargetFontSizeSp = controller.fontSizeSp
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val controller = terminalController ?: return false
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor <= 0f) return false
                val next = (pinchZoomTargetFontSizeSp * factor).coerceIn(
                    MIN_PINCH_FONT_SIZE_SP,
                    MAX_PINCH_FONT_SIZE_SP,
                )
                if (abs(next - pinchZoomTargetFontSizeSp) < MIN_PINCH_FONT_DELTA_SP) return false
                pinchZoomTargetFontSizeSp = next
                pinchZoomConsumed = true
                controller.fontSizeSp = next
                return true
            }
        },
    )
    private val frameStatsCollector = FrameStatsCollector { timing ->
        val controller = terminalController ?: return@FrameStatsCollector
        controller.reportFrameTiming(timing, controller.viewport.visibleRows(0).count)
    }
    private val publishIdleStats = Runnable { frameStatsCollector.publishIdle() }
    private val publishTerminalSize = Runnable { reportTerminalSizeNow() }
    private val publishCursorBlink = Runnable {
        val cursor = terminalController?.cursor
        val effectiveBlink = cursor?.let {
            TerminalCursorRendering.resolveBlink(it, cursorBlinkEnabled)
        }
        if (cursor?.visible == true && effectiveBlink == true && isAttachedToWindow) {
            invalidateTerminalRow(cursor.row)
            scheduleCursorBlink()
        }
    }
    private val finishVisualBell = Runnable {
        if (SystemClock.uptimeMillis() >= visualBellUntilUptimeMillis) {
            visualBellUntilUptimeMillis = 0L
            postInvalidateOnAnimation()
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = true
        contentDescription = context.getString(R.string.terminal_native_renderer_description)
        textPaint.typeface = normalTypeface
        setBackgroundColor(terminalTheme.background)
    }

    fun attachController(controller: TerminalController) {
        if (terminalController === controller) return
        resetComposingInput()
        val wasFocused = hasFocus()
        if (wasFocused) terminalController?.reportFocus(false)
        terminalController?.removeListener(this)
        clearSelectionAndLinkActions()
        clearFindResultsInternal()
        removeCallbacks(finishVisualBell)
        visualBellUntilUptimeMillis = 0L
        terminalController = controller
        lastVisualBellSequence = controller.latestBellSequence()
        applyRendererProfile(controller.rendererProfile)
        controller.viewport.updateGeometry(
            heightPx = (height - verticalPaddingPx * 2f).toInt().coerceAtLeast(0),
            newLineHeightPx = lineHeightPx,
        )
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())
        scheduleTerminalSizeReport()
        controller.addListener(this)
        postInvalidateOnAnimation()
        if (wasFocused) {
            controller.reportFocus(true)
            restartTerminalInput(showKeyboard = false)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Detach removes the listener to avoid retaining an off-window view. Re-register even when
        // the controller identity is unchanged so route/view reuse resumes renderer invalidation.
        terminalController?.addListener(this)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(publishIdleStats)
        removeCallbacks(publishTerminalSize)
        removeCallbacks(publishCursorBlink)
        removeCallbacks(finishVisualBell)
        visualBellUntilUptimeMillis = 0L
        selectionActionMode?.finish()
        selectionActionMode = null
        terminalController?.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onTerminalContentChanged(change: TerminalContentChange) {
        val controller = terminalController ?: return
        val previousProfile = appliedRendererProfile
        val previousScrollY = controller.viewport.scrollY
        applyRendererProfile(controller.rendererProfile)
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())
        var overlayChanged = false
        if (selection.hasSelection && !selection.validate(controller)) {
            selectionActionMode?.finish()
            selectionActionMode = null
            overlayChanged = true
        }
        findHighlight?.takeIf { it.result.revision != controller.contentRevision }?.let {
            clearFindResultsInternal()
            overlayChanged = true
        }
        activeLink?.let { link ->
            val row = controller.selectionIndexOf(link.line)
            val current = row?.let(controller::selectionLineAt)?.let { selectable ->
                TerminalLinkResolver.find(
                    selectable = selectable,
                    column = link.startColumn,
                    osc8Enabled = controller.rendererProfile.osc8HyperlinksEnabled,
                    plainTextUrlsEnabled = controller.rendererProfile.detectPlainTextUrls,
                )
            }
            if (current?.uri != link.uri) activeLink = null
        }
        selectionActionMode?.invalidate()
        if (
            change.fullRedraw || overlayChanged || previousProfile != appliedRendererProfile ||
            previousScrollY != controller.viewport.scrollY
        ) {
            postInvalidateOnAnimation()
        } else {
            invalidateTerminalRows(change.dirtyRows)
        }
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
        canvas.getClipBounds(drawClipBounds)
        val clipFirstRow = floor(
            (drawClipBounds.top + viewport.scrollY - verticalPaddingPx) / lineHeightPx,
        ).toInt() - 1
        val clipLastRowExclusive = ceil(
            (drawClipBounds.bottom + viewport.scrollY - verticalPaddingPx) / lineHeightPx,
        ).toInt() + 1
        val firstRow = maxOf(rows.first, clipFirstRow)
        val lastRowExclusive = minOf(rows.lastExclusive, clipLastRowExclusive)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        var rowIndex = firstRow
        while (rowIndex < lastRowExclusive) {
            val line = controller.lineAt(rowIndex)
            if (line != null) {
                val rowTop = verticalPaddingPx + rowIndex * lineHeightPx - viewport.scrollY
                drawLine(canvas, line.runs, rowTop + baselineOffsetPx, rowTop)
            }
            rowIndex += 1
        }

        drawFindHighlights(canvas, controller)
        drawSelection(canvas, controller)
        drawCursor(canvas, controller, viewport.scrollY)
        if (!viewport.autoFollow) drawNewOutputBadge(canvas)
        drawVisualBell(canvas)
        canvas.restore()
        frameStatsCollector.recordDraw(drawStartNanos, System.nanoTime())
        removeCallbacks(publishIdleStats)
        postDelayed(publishIdleStats, FrameStatsCollector.IDLE_DELAY_MS)
    }

    private fun drawLine(canvas: Canvas, runs: List<TerminalRun>, baseline: Float, rowTop: Float) {
        var fallbackX = horizontalPaddingPx
        val rightEdge = width - horizontalPaddingPx
        for (run in runs) {
            val runStartX = TerminalRunRenderGeometry.startX(
                run = run,
                terminalOriginX = horizontalPaddingPx,
                cellWidth = cellWidthPx,
                measuredFallbackX = fallbackX,
            )
            if (runStartX >= rightEdge) break
            applyStyle(run)
            val estimatedCharacters = ceil((rightEdge - runStartX) / cellWidthPx).toInt() + 2
            var end = minOf(run.text.length, estimatedCharacters)
            if (end in 1 until run.text.length && Character.isHighSurrogate(run.text[end - 1])) end -= 1
            val measuredTextWidth = if (end > 0) textPaint.measureText(run.text, 0, end) else 0f
            val runWidth = TerminalRunRenderGeometry.width(
                run = run,
                cellWidth = cellWidthPx,
                measuredTextWidth = measuredTextWidth,
            )
            val runRightX = runStartX + runWidth
            val foreground = terminalTheme.resolveDrawForeground(run.style)
            val background = terminalTheme.resolveDrawBackground(run.style)
            if (background != terminalTheme.background) {
                fillPaint.color = background
                canvas.drawRect(runStartX, rowTop, runRightX, rowTop + lineHeightPx, fillPaint)
            }
            textPaint.color = foreground
            textPaint.alpha = run.style.terminalTextAlpha()
            if (run.style.shouldDrawTerminalText() && end > 0 && runWidth > 0f) {
                if (run.columnWidth >= 0) {
                    canvas.save()
                    canvas.clipRect(runStartX, rowTop, runRightX, rowTop + lineHeightPx)
                    canvas.drawText(run.text, 0, end, runStartX, baseline, textPaint)
                    drawLegacySymbolFallback(
                        canvas = canvas,
                        run = run,
                        end = end,
                        runStartX = runStartX,
                        baseline = baseline,
                        rowTop = rowTop,
                        background = background,
                    )
                    canvas.restore()
                } else {
                    canvas.drawText(run.text, 0, end, runStartX, baseline, textPaint)
                    drawLegacySymbolFallback(
                        canvas = canvas,
                        run = run,
                        end = end,
                        runStartX = runStartX,
                        baseline = baseline,
                        rowTop = rowTop,
                        background = background,
                    )
                }
            }
            fallbackX = runRightX
            if (end < run.text.length) break
        }
        textPaint.alpha = OPAQUE_TEXT_ALPHA
    }

    private fun applyStyle(run: TerminalRun) {
        val renderBold = run.style.bold && appliedRendererProfile?.boldRenderingEnabled != false
        textPaint.typeface = when {
            renderBold && run.style.italic -> boldItalicTypeface
            renderBold -> boldTypeface
            run.style.italic -> italicTypeface
            else -> normalTypeface
        }
        textPaint.isUnderlineText = run.style.underline ||
            (appliedRendererProfile?.osc8HyperlinksEnabled != false && run.hyperlink != null)
        textPaint.isStrikeThruText = run.style.strikethrough
    }

    /** API 29+ uses a native custom fallback chain; this path keeps API 26-28 useful. */
    private fun drawLegacySymbolFallback(
        canvas: Canvas,
        run: TerminalRun,
        end: Int,
        runStartX: Float,
        baseline: Float,
        rowTop: Float,
        background: Int,
    ) {
        val renderBold = run.style.bold && appliedRendererProfile?.boldRenderingEnabled != false
        val symbolTypeface = when {
            renderBold && run.style.italic -> legacySymbolBoldItalicTypeface
            renderBold -> legacySymbolBoldTypeface
            run.style.italic -> legacySymbolItalicTypeface
            else -> legacySymbolNormalTypeface
        } ?: return
        val previousTypeface = textPaint.typeface
        textPaint.typeface = symbolTypeface
        var index = 0
        var cellOffset = 0
        while (index < end) {
            val codePoint = run.text.codePointAt(index)
            val characterCount = Character.charCount(codePoint)
            val cellCount = TerminalCellWidth.of(codePoint).coerceAtLeast(0)
            if (isNerdSymbolCodePoint(codePoint) && cellCount > 0) {
                val left = runStartX + cellOffset * cellWidthPx
                val right = left + cellCount * cellWidthPx
                fillPaint.color = background
                canvas.drawRect(left, rowTop, right, rowTop + lineHeightPx, fillPaint)
                canvas.save()
                canvas.clipRect(left, rowTop, right, rowTop + lineHeightPx)
                canvas.drawText(run.text, index, index + characterCount, left, baseline, textPaint)
                canvas.restore()
            }
            cellOffset += cellCount
            index += characterCount
        }
        textPaint.typeface = previousTypeface
    }

    private fun isNerdSymbolCodePoint(codePoint: Int): Boolean =
        codePoint in 0xE000..0xF8FF ||
            codePoint in 0xF0001..0xF1AF0 ||
            codePoint in 0x23FB..0x23FE ||
            codePoint == 0x2630 ||
            codePoint == 0x2665 ||
            codePoint == 0x26A1 ||
            codePoint in 0x276C..0x2771 ||
            codePoint == 0x2B58

    private fun drawSelection(canvas: Canvas, controller: TerminalController) {
        val segments = selection.segments(controller)
        if (segments.isEmpty()) return
        val scrollY = controller.viewport.scrollY
        fillPaint.color = terminalTheme.selection
        fillPaint.alpha = SELECTION_ALPHA
        segments.forEach { segment ->
            val top = verticalPaddingPx + segment.row * lineHeightPx - scrollY
            if (top + lineHeightPx < 0f || top > height) return@forEach
            val left = horizontalPaddingPx + segment.startColumn * cellWidthPx
            val right = horizontalPaddingPx + segment.endColumn * cellWidthPx
            canvas.drawRect(left, top, maxOf(left + MIN_SELECTION_WIDTH_PX, right), top + lineHeightPx, fillPaint)
        }
        fillPaint.alpha = 255

        selectionHandlePosition(controller, TerminalSelectionEndpoint.START)?.let { position ->
            drawSelectionHandle(canvas, position.first, position.second)
        }
        selectionHandlePosition(controller, TerminalSelectionEndpoint.END)?.let { position ->
            drawSelectionHandle(canvas, position.first, position.second)
        }
    }

    private fun drawFindHighlights(canvas: Canvas, controller: TerminalController) {
        val highlight = findHighlight ?: return
        if (highlight.result.revision != controller.contentRevision) return
        val scrollY = controller.viewport.scrollY
        highlight.result.matches.forEachIndexed { matchIndex, match ->
            fillPaint.color = if (matchIndex == highlight.activeMatchIndex) {
                terminalTheme.cursor
            } else {
                terminalTheme.selection
            }
            fillPaint.alpha = if (matchIndex == highlight.activeMatchIndex) {
                ACTIVE_FIND_ALPHA
            } else {
                FIND_ALPHA
            }
            match.segments.forEach segmentLoop@{ segment ->
                val row = controller.selectionIndexOf(segment.line) ?: return@segmentLoop
                val top = verticalPaddingPx + row * lineHeightPx - scrollY
                if (top + lineHeightPx < 0f || top > height) return@segmentLoop
                val left = horizontalPaddingPx + segment.startColumn * cellWidthPx
                val right = horizontalPaddingPx + segment.endColumnExclusive * cellWidthPx
                canvas.drawRect(
                    left,
                    top,
                    maxOf(left + MIN_SELECTION_WIDTH_PX, right),
                    top + lineHeightPx,
                    fillPaint,
                )
            }
        }
        fillPaint.alpha = 255
    }

    private fun drawVisualBell(canvas: Canvas) {
        if (SystemClock.uptimeMillis() >= visualBellUntilUptimeMillis) return
        fillPaint.color = terminalTheme.foreground
        fillPaint.alpha = VISUAL_BELL_ALPHA
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        fillPaint.alpha = 255
    }

    private fun drawSelectionHandle(canvas: Canvas, x: Float, y: Float) {
        val radius = SELECTION_HANDLE_RADIUS_DP * resources.displayMetrics.density
        fillPaint.color = terminalTheme.cursor
        val centerY = if (height >= radius * 2f) y.coerceIn(radius, height - radius) else height / 2f
        canvas.drawCircle(x, centerY, radius, fillPaint)
    }

    private fun applyTerminalTheme(theme: TerminalTheme) {
        if (terminalTheme == theme) return
        terminalTheme = theme
        setBackgroundColor(theme.background)
    }

    private fun applyRendererProfile(profile: TerminalRendererProfile) {
        applyTerminalTheme(profile.theme)
        if (appliedRendererProfile == profile) return
        val typefaces = TerminalTypefaceRegistry.resolve(
            context = context,
            fontId = profile.fontId,
            customFontPath = profile.customFontPath,
        ) ?: TerminalTypefaces(
            normal = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL),
            bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
            italic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
            boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC),
            legacySymbolFallback = null,
        )
        normalTypeface = typefaces.normal
        boldTypeface = typefaces.bold
        italicTypeface = typefaces.italic
        boldItalicTypeface = typefaces.boldItalic
        legacySymbolNormalTypeface = typefaces.legacySymbolFallback
        legacySymbolBoldTypeface = typefaces.legacySymbolFallback?.let {
            Typeface.create(it, Typeface.BOLD)
        }
        legacySymbolItalicTypeface = typefaces.legacySymbolFallback?.let {
            Typeface.create(it, Typeface.ITALIC)
        }
        legacySymbolBoldItalicTypeface = typefaces.legacySymbolFallback?.let {
            Typeface.create(it, Typeface.BOLD_ITALIC)
        }
        textPaint.typeface = normalTypeface
        textPaint.letterSpacing = profile.letterSpacingEm
        textPaint.fontFeatureSettings = if (profile.ligaturesEnabled) {
            ENABLE_LIGATURE_FEATURES
        } else {
            DISABLE_LIGATURE_FEATURES
        }
        cursorStyle = profile.cursorStyle
        cursorBlinkEnabled = profile.cursorBlinkEnabled
        if (!cursorBlinkEnabled) removeCallbacks(publishCursorBlink)
        setTouchScrollBehavior(
            touchMode = profile.touchScrollMode,
            twoFingerLocalScrollOverride = profile.twoFingerLocalScrollOverride,
        )
        updateTextMetrics(profile.fontSizeSp, profile.lineHeightMultiplier)
        appliedRendererProfile = profile
    }

    private fun drawCursor(canvas: Canvas, controller: TerminalController, scrollY: Float) {
        val cursor = controller.cursor
        if (!cursor.visible || cursor.row !in 0 until controller.lineCount()) return
        val left = horizontalPaddingPx + cursor.column * cellWidthPx
        val top = verticalPaddingPx + cursor.row * lineHeightPx - scrollY
        if (top + lineHeightPx < 0f || top > height) return
        val effectiveBlink = TerminalCursorRendering.resolveBlink(cursor, cursorBlinkEnabled)
        if (effectiveBlink) {
            scheduleCursorBlink()
            if ((SystemClock.uptimeMillis() / CURSOR_BLINK_INTERVAL_MS) % 2L != 0L) return
        }
        fillPaint.color = terminalTheme.cursor
        fillPaint.alpha = CURSOR_ALPHA
        val density = resources.displayMetrics.density
        when (TerminalCursorRendering.resolveStyle(cursor, cursorStyle)) {
            CursorStyle.BLOCK -> canvas.drawRect(
                left,
                top,
                left + cellWidthPx,
                top + lineHeightPx,
                fillPaint,
            )
            CursorStyle.UNDERLINE -> {
                val thickness = TerminalCursorRendering.underlineThickness(lineHeightPx, density)
                canvas.drawRect(
                    left,
                    top + lineHeightPx - thickness,
                    left + cellWidthPx,
                    top + lineHeightPx,
                    fillPaint,
                )
            }
            CursorStyle.BEAM -> {
                val thickness = TerminalCursorRendering.beamWidth(cellWidthPx, density)
                canvas.drawRect(left, top, left + thickness, top + lineHeightPx, fillPaint)
            }
        }
        fillPaint.alpha = 255
    }

    private fun scheduleCursorBlink() {
        removeCallbacks(publishCursorBlink)
        val elapsed = SystemClock.uptimeMillis() % CURSOR_BLINK_INTERVAL_MS
        postDelayed(publishCursorBlink, CURSOR_BLINK_INTERVAL_MS - elapsed)
    }

    private fun drawNewOutputBadge(canvas: Canvas) {
        val label = context.getString(R.string.terminal_new_output_badge)
        textPaint.typeface = boldTypeface
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false
        textPaint.alpha = OPAQUE_TEXT_ALPHA
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
                mouseWheelAccumulator.reset()
                pinchZoomConsumed = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                scrollGestureRouter.observePointerCount(event.pointerCount)
                gestureActions.onMultiPointerGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureActions.onCancel()
            }
        }
        if (event.pointerCount >= 2) scrollGestureRouter.observePointerCount(event.pointerCount)
        val scaleHandled = if (appliedRendererProfile?.pinchZoomEnabled == true) {
            scaleGestureDetector.onTouchEvent(event)
        } else {
            false
        }
        val gestureHandled = gestureDetector.onTouchEvent(event)
        val handled = scaleHandled || gestureHandled || super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.actionMasked == MotionEvent.ACTION_UP) gestureActions.onTouchUp()
            scrollGestureRouter.onGestureEnd()
            pinchZoomConsumed = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun performLongClick(): Boolean {
        super.performLongClick()
        val x = width / 2f
        val visible = terminalController?.viewport?.visibleRows(0) ?: return false
        val row = visible.first.coerceAtMost((visible.lastExclusive - 1).coerceAtLeast(0))
        val y = terminalRowCenterY(row)
        return showLinkActionsAt(x, y) || startSelectionAt(x, y)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val controller = terminalController ?: return
        info.className = android.widget.EditText::class.java.name
        info.isEditable = directInputEnabled
        info.isMultiLine = true
        info.isScrollable = controller.viewport.maximumScrollY > 0f
        info.text = selection.selectedText(controller) ?: accessibleVisibleText(controller)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                R.id.terminal_action_select_all,
                context.getString(R.string.terminal_action_select_all),
            ),
        )
        if (selection.hasSelection) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_SELECTION)
        }
        if (controller.viewport.scrollY > 0f) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        if (controller.viewport.scrollY < controller.viewport.maximumScrollY) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
        activeLink?.let { link ->
            if (TerminalLinkPolicy.canOpen(link.uri)) {
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        R.id.terminal_action_open_link,
                        context.getString(R.string.terminal_action_open_link),
                    ),
                )
            }
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.terminal_action_copy_link,
                    context.getString(R.string.terminal_action_copy_link),
                ),
            )
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return when (action) {
            AccessibilityNodeInfo.ACTION_COPY -> copySelectionToClipboard()
            R.id.terminal_action_select_all -> selectAllOutput()
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> {
                val cleared = selection.clear()
                if (cleared) {
                    activeLink = null
                    selectionActionMode?.finish()
                    selectionActionMode = null
                    invalidate()
                    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
                }
                cleared
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> performLongClick()
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                pageScroll(-1)
                true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                pageScroll(1)
                true
            }
            R.id.terminal_action_open_link -> requestActiveLinkAction(TerminalLinkAction.OPEN)
            R.id.terminal_action_copy_link -> requestActiveLinkAction(TerminalLinkAction.COPY)
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        terminalController?.reportFocus(gainFocus)
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) {
            flingDestination = TerminalScrollDestination.NONE
            return
        }
        val controller = terminalController ?: return
        when (flingDestination) {
            TerminalScrollDestination.LOCAL_SCROLLBACK -> {
                val previous = controller.viewport.autoFollow
                controller.viewport.scrollTo(scroller.currY.toFloat())
                controller.reportViewportStateIfChanged(previous)
            }
            TerminalScrollDestination.REMOTE_MOUSE -> {
                stopActiveFling()
                return
            }
            TerminalScrollDestination.NONE -> return
        }
        postInvalidateOnAnimation()
    }

    override fun onCheckIsTextEditor(): Boolean = directInputEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!directInputEnabled) return null
        if (terminalController == null) return null
        TerminalInputConnection.configureEditorInfo(outAttrs)
        return TerminalInputConnection(this, directInputSink) { request ->
            imageContentCallback?.onImageContent(request) ?: false
        }.also { activeInputConnection = it }
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyPreIme(keyCode, event)
        val callback = preImeBackCallback ?: return super.onKeyPreIme(keyCode, event)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) callback()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!directInputEnabled) return super.onKeyDown(keyCode, event)
        val controller = terminalController ?: return super.onKeyDown(keyCode, event)
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            resetComposingInput()
        }
        TerminalKeySequences.keypadForKeyCode(keyCode)?.let { sequence ->
            controller.sendKeypad(sequence.normal, sequence.application)
            return true
        }
        TerminalKeySequences.forModifiedKeyCode(
            keyCode = keyCode,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            ctrl = event.isCtrlPressed,
        )?.let { sequence ->
            pendingDeadAccent = 0
            controller.send(sequence)
            return true
        }
        if (event.isCtrlPressed) {
            TerminalKeySequences.controlByteForKeyCode(keyCode, event.isShiftPressed)?.let { control ->
                pendingDeadAccent = 0
                val bytes = byteArrayOf(control)
                controller.send(if (event.isAltPressed) TerminalKeySequences.ESCAPE + bytes else bytes)
                return true
            }
        }
        val rawUnicode = event.unicodeChar
        if (rawUnicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            pendingDeadAccent = rawUnicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        val accent = pendingDeadAccent
        pendingDeadAccent = 0
        val committedText = when {
            rawUnicode <= 0 || Character.isISOControl(rawUnicode) -> null
            accent == 0 -> String(Character.toChars(rawUnicode))
            else -> {
                val combined = KeyCharacterMap.getDeadChar(accent, rawUnicode)
                if (combined != 0) {
                    String(Character.toChars(combined))
                } else {
                    String(Character.toChars(accent)) + String(Character.toChars(rawUnicode))
                }
            }
        }
        if (committedText != null) {
            val bytes = committedText.toByteArray(Charsets.UTF_8)
            controller.send(if (event.isAltPressed) TerminalKeySequences.ESCAPE + bytes else bytes)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun pageScroll(direction: Int) {
        scrollViewportBy(direction * terminalController.orEmptyViewportHeight())
    }

    fun setLinkActionCallback(callback: TerminalLinkActionCallback?) {
        linkActionCallback = callback ?: TerminalLinkActionCallback { }
    }

    fun setClipboardActionCallback(callback: TerminalClipboardActionCallback?) {
        clipboardActionCallback = callback ?: TerminalClipboardActionCallback { request ->
            defaultClipboardWriter.write(request) != null
        }
    }

    fun setImageContentCallback(callback: TerminalImageContentCallback?) {
        imageContentCallback = callback
    }

    fun setPreImeBackCallback(callback: (() -> Unit)?) {
        preImeBackCallback = callback
    }

    /**
     * Installs only bounded stable references and reveals the active match in the native viewport.
     * Returns false for a stale result rather than retargeting it to newer terminal content.
     */
    fun showFindResult(result: TerminalFindResult, activeMatchIndex: Int): Boolean {
        val controller = terminalController ?: return false
        if (result.sourceId != controller.contentSourceId || result.revision != controller.contentRevision) {
            clearFindResults()
            return false
        }
        val boundedActiveIndex = if (result.matches.isEmpty()) {
            -1
        } else {
            activeMatchIndex.coerceIn(result.matches.indices)
        }
        if (boundedActiveIndex >= 0) {
            val active = result.matches[boundedActiveIndex]
            val rows = active.segments.mapNotNull { controller.selectionIndexOf(it.line) }
            if (rows.size != active.segments.size || rows.isEmpty()) {
                clearFindResults()
                return false
            }
            val previousAutoFollow = controller.viewport.autoFollow
            val targetRow = rows.first()
            controller.viewport.scrollTo(
                targetRow * lineHeightPx - controller.viewport.viewportHeightPx / 2f + lineHeightPx / 2f,
            )
            controller.reportViewportStateIfChanged(previousAutoFollow)
        }
        findHighlight = NativeFindHighlight(result, boundedActiveIndex)
        postInvalidateOnAnimation()
        return true
    }

    fun clearFindResults(): Boolean {
        val cleared = clearFindResultsInternal()
        if (cleared) postInvalidateOnAnimation()
        return cleared
    }

    /** Starts one short Canvas flash. Repeated/in-flight sequences are coalesced, never extended. */
    fun presentVisualBell(event: TerminalBellEvent): Boolean {
        if (event.sequence == lastVisualBellSequence) return false
        lastVisualBellSequence = event.sequence
        if (!isAttachedToWindow) return false
        val now = SystemClock.uptimeMillis()
        if (now < visualBellUntilUptimeMillis) return false
        visualBellUntilUptimeMillis = now + VISUAL_BELL_DURATION_MS
        removeCallbacks(finishVisualBell)
        postDelayed(finishVisualBell, VISUAL_BELL_DURATION_MS)
        postInvalidateOnAnimation()
        return true
    }

    internal fun selectedTextForTesting(): String? =
        terminalController?.let(selection::selectedText)

    private fun handleTerminalTap(x: Float, y: Float): Boolean {
        val link = linkAt(x, y)
        if (link != null) {
            activeLink = link
            if (TerminalLinkPolicy.canOpen(link.uri)) {
                linkActionCallback.onLinkAction(
                    TerminalLinkActionRequest(TerminalLinkAction.OPEN, link),
                )
            } else {
                showSelectionActionMode()
                invalidate()
            }
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
        if (selection.clear()) {
            activeLink = null
            selectionActionMode?.finish()
            selectionActionMode = null
            invalidate()
        }
        return false
    }

    /** Long-pressing a detected URL opens a focused Open/Copy menu instead of word selection. */
    private fun showLinkActionsAt(x: Float, y: Float): Boolean {
        val link = linkAt(x, y) ?: return false
        selection.clear()
        selectionActionMode?.finish()
        selectionActionMode = null
        activeLink = link
        hideSoftwareKeyboard()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showSelectionActionMode()
        invalidate()
        return true
    }

    private fun startSelectionAt(x: Float, y: Float): Boolean {
        val controller = terminalController ?: return false
        val row = terminalRowAt(y) ?: return false
        val column = terminalColumnAt(x)
        if (!selection.beginWord(controller, row, column)) return false
        // Selection is a local terminal-output action, not text entry. Hiding the IME leaves the
        // floating Copy/Select all toolbar and both drag handles unobstructed on a phone.
        hideSoftwareKeyboard()
        activeLink = controller.selectionLineAt(row)?.let { selectable ->
            TerminalLinkResolver.find(
                selectable = selectable,
                column = column,
                osc8Enabled = controller.rendererProfile.osc8HyperlinksEnabled,
                plainTextUrlsEnabled = controller.rendererProfile.detectPlainTextUrls,
            )
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showSelectionActionMode()
        invalidate()
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        return true
    }

    private fun dragSelection(endpoint: TerminalSelectionEndpoint, x: Float, y: Float) {
        val controller = terminalController ?: return
        val edge = SELECTION_AUTOSCROLL_EDGE_DP * resources.displayMetrics.density
        when {
            y < edge -> scrollViewportBy(-lineHeightPx)
            y > height - edge -> scrollViewportBy(lineHeightPx)
        }
        val row = terminalRowAt(y.coerceIn(0f, height.toFloat())) ?: return
        if (selection.updateEndpoint(controller, endpoint, row, terminalColumnAt(x))) {
            activeLink = null
            selectionActionMode?.invalidate()
            invalidate()
        }
    }

    private fun finishSelectionDrag(committed: Boolean) {
        selectionActionMode?.invalidate()
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        if (committed && appliedRendererProfile?.copyOnSelection == true) copySelectionToClipboard()
    }

    private fun selectionHandleAt(x: Float, y: Float): TerminalSelectionEndpoint? {
        val controller = terminalController ?: return null
        val hitRadius = SELECTION_HANDLE_HIT_RADIUS_DP * resources.displayMetrics.density
        return TerminalSelectionEndpoint.entries.firstOrNull { endpoint ->
            val position = selectionHandlePosition(controller, endpoint) ?: return@firstOrNull false
            hypot((x - position.first).toDouble(), (y - position.second).toDouble()) <= hitRadius
        }
    }

    private fun selectionHandlePosition(
        controller: TerminalController,
        endpoint: TerminalSelectionEndpoint,
    ): Pair<Float, Float>? {
        val range = selection.normalizedRange(controller) ?: return null
        val position = if (endpoint == TerminalSelectionEndpoint.START) range.start else range.end
        val row = controller.selectionIndexOf(position.line) ?: return null
        val x = horizontalPaddingPx + position.column * cellWidthPx
        val y = verticalPaddingPx + (row + 1) * lineHeightPx - controller.viewport.scrollY
        val radius = SELECTION_HANDLE_HIT_RADIUS_DP * resources.displayMetrics.density
        if (x < -radius || x > width + radius || y < -radius || y > height + radius) return null
        return x to y
    }

    private fun linkAt(x: Float, y: Float): TerminalLinkTarget? {
        val controller = terminalController ?: return null
        val row = terminalRowAt(y) ?: return null
        val selectable = controller.selectionLineAt(row) ?: return null
        return TerminalLinkResolver.find(
            selectable = selectable,
            column = terminalColumnAt(x),
            osc8Enabled = controller.rendererProfile.osc8HyperlinksEnabled,
            plainTextUrlsEnabled = controller.rendererProfile.detectPlainTextUrls,
        )
    }

    private fun terminalRowAt(y: Float): Int? {
        val controller = terminalController ?: return null
        if (controller.lineCount() <= 0 || !y.isFinite()) return null
        val contentY = y - verticalPaddingPx + controller.viewport.scrollY
        return floor(contentY / lineHeightPx).toInt().coerceIn(0, controller.lineCount() - 1)
    }

    private fun terminalColumnAt(x: Float): Int =
        floor((x - horizontalPaddingPx) / cellWidthPx).toInt().coerceAtLeast(0)

    private fun terminalRowCenterY(row: Int): Float {
        val scrollY = terminalController?.viewport?.scrollY ?: 0f
        return verticalPaddingPx + (row + 0.5f) * lineHeightPx - scrollY
    }

    private fun showSelectionActionMode() {
        selectionActionMode?.invalidate()
        if (selectionActionMode != null) return
        selectionActionMode = startActionMode(selectionActionModeCallback, ActionMode.TYPE_FLOATING)
    }

    private val selectionActionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            populateSelectionMenu(menu)
            return menu.size() > 0
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            populateSelectionMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            android.R.id.copy -> copySelectionToClipboard().also { if (it) mode.finish() }
            android.R.id.selectAll -> selectAllOutput()
            MENU_OPEN_LINK -> requestActiveLinkAction(TerminalLinkAction.OPEN).also { if (it) mode.finish() }
            MENU_COPY_LINK -> requestActiveLinkAction(TerminalLinkAction.COPY).also { if (it) mode.finish() }
            else -> false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (selectionActionMode === mode) selectionActionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: Rect) {
            val controller = terminalController
            val range = controller?.let(selection::normalizedRange)
            val startRow = if (controller == null || range == null) {
                null
            } else {
                controller.selectionIndexOf(range.start.line)
            }
            val endRow = if (controller == null || range == null) {
                null
            } else {
                controller.selectionIndexOf(range.end.line)
            }
            if (controller == null || range == null || startRow == null || endRow == null) {
                outRect.set(0, 0, width, minOf(height, lineHeightPx.toInt().coerceAtLeast(1)))
                return
            }
            val top = (verticalPaddingPx + startRow * lineHeightPx - controller.viewport.scrollY)
                .toInt().coerceIn(0, height)
            val bottom = (verticalPaddingPx + (endRow + 1) * lineHeightPx - controller.viewport.scrollY)
                .toInt().coerceIn(top, height)
            outRect.set(0, top, width, bottom)
        }
    }

    private fun populateSelectionMenu(menu: Menu) {
        if (selection.hasSelection) {
            menu.add(Menu.NONE, android.R.id.copy, 0, R.string.terminal_action_copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.selectAll, 1, R.string.terminal_action_select_all)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        activeLink?.let { link ->
            if (TerminalLinkPolicy.canOpen(link.uri)) {
                menu.add(Menu.NONE, MENU_OPEN_LINK, 2, R.string.terminal_action_open_link)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            menu.add(Menu.NONE, MENU_COPY_LINK, 3, R.string.terminal_action_copy_link)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    private fun copySelectionToClipboard(): Boolean {
        val controller = terminalController ?: return false
        val text = selection.selectedText(controller) ?: return false
        if (text.isEmpty()) return false
        if (
            !clipboardActionCallback.onCopyRequested(
                TerminalClipboardRequest(
                    label = context.getString(R.string.terminal_clipboard_selection_label),
                    text = text,
                    kind = TerminalClipboardContentKind.SELECTION,
                ),
            )
        ) {
            return false
        }
        announceTerminalAccessibility(context.getString(R.string.terminal_selection_copied))
        return true
    }

    private fun selectAllOutput(): Boolean {
        val controller = terminalController ?: return false
        if (!selection.selectAll(controller)) return false
        activeLink = null
        selectionActionMode?.invalidate()
        invalidate()
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        return true
    }

    private fun requestActiveLinkAction(action: TerminalLinkAction): Boolean {
        val link = activeLink ?: return false
        if (action == TerminalLinkAction.OPEN && !TerminalLinkPolicy.canOpen(link.uri)) return false
        if (action == TerminalLinkAction.OPEN) {
            linkActionCallback.onLinkAction(TerminalLinkActionRequest(action, link))
            return true
        }
        val copied = clipboardActionCallback.onCopyRequested(
            TerminalClipboardRequest(
                label = context.getString(R.string.terminal_clipboard_link_label),
                text = link.uri,
                kind = TerminalClipboardContentKind.LINK,
            ),
        )
        if (copied) announceTerminalAccessibility(context.getString(R.string.terminal_link_copied))
        return copied
    }

    private fun announceTerminalAccessibility(message: CharSequence) {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        dispatchTerminalAccessibilityAnnouncement(accessibilityManager?.isEnabled == true) {
            sendAccessibilityEventUnchecked(
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                    className = FastTerminalView::class.java.name
                    packageName = context.packageName
                    text.add(message)
                },
            )
        }
    }

    private fun clearSelectionAndLinkActions() {
        selection.clear()
        activeLink = null
        selectionActionMode?.finish()
        selectionActionMode = null
    }

    private fun clearFindResultsInternal(): Boolean {
        if (findHighlight == null) return false
        findHighlight = null
        return true
    }

    private fun accessibleVisibleText(controller: TerminalController): String {
        val visible = controller.viewport.visibleRows(0)
        val result = StringBuilder()
        for (row in visible.first until visible.lastExclusive) {
            val line = controller.lineAt(row) ?: continue
            val remaining = MAX_ACCESSIBLE_CHARACTERS - result.length
            if (remaining <= 0) break
            val end = minOf(line.text.length, remaining)
            result.append(line.text, 0, end)
            if (result.length >= MAX_ACCESSIBLE_CHARACTERS) break
            if (row + 1 < visible.lastExclusive && !line.softWrappedToNext) result.append('\n')
        }
        return result.toString()
    }

    /** Applies the active terminal profile's touch routing without recreating the native view. */
    fun setTouchScrollBehavior(
        touchMode: TouchScrollMode,
        twoFingerLocalScrollOverride: Boolean = true,
    ) {
        if (!scrollGestureRouter.updateConfiguration(touchMode, twoFingerLocalScrollOverride)) return
        mouseWheelAccumulator.reset()
        stopActiveFling()
    }

    private fun scrollViewportBy(distanceY: Float) {
        val controller = terminalController ?: return
        val previous = controller.viewport.autoFollow
        controller.viewport.scrollBy(distanceY)
        controller.reportViewportStateIfChanged(previous)
        postInvalidateOnAnimation()
    }

    private fun invalidateTerminalRows(rows: IntArray) {
        if (rows.isEmpty()) return
        if (width <= 0 || height <= 0 || lineHeightPx <= 0f) {
            postInvalidateOnAnimation()
            return
        }
        var firstRow = rows[0]
        var lastRow = firstRow
        for (index in 1 until rows.size) {
            val row = rows[index]
            if (row <= lastRow + 1) {
                lastRow = maxOf(lastRow, row)
            } else {
                invalidateTerminalRowRange(firstRow, lastRow)
                firstRow = row
                lastRow = row
            }
        }
        invalidateTerminalRowRange(firstRow, lastRow)
    }

    private fun invalidateTerminalRow(row: Int) {
        if (width <= 0 || height <= 0 || lineHeightPx <= 0f) {
            postInvalidateOnAnimation()
        } else {
            invalidateTerminalRowRange(row, row)
        }
    }

    private fun invalidateTerminalRowRange(firstRow: Int, lastRow: Int) {
        val scrollY = terminalController?.viewport?.scrollY ?: return
        val top = floor(verticalPaddingPx + firstRow * lineHeightPx - scrollY).toInt() - 1
        val bottom = ceil(verticalPaddingPx + (lastRow + 1) * lineHeightPx - scrollY).toInt() + 1
        if (bottom <= 0 || top >= height) return
        postInvalidateOnAnimation(
            0,
            top.coerceAtLeast(0),
            width,
            bottom.coerceAtMost(height),
        )
    }

    private fun handleGestureScroll(distanceY: Float, x: Float, y: Float, pointerCount: Int) {
        val controller = terminalController ?: return
        if (pinchZoomConsumed) return
        scrollGestureRouter.observePointerCount(pointerCount)
        when (scrollGestureRouter.destination(controller.isMouseTrackingEnabled())) {
            TerminalScrollDestination.LOCAL_SCROLLBACK -> {
                mouseWheelAccumulator.reset()
                scrollViewportBy(distanceY)
            }
            TerminalScrollDestination.REMOTE_MOUSE -> {
                sendRemoteMouseWheel(distanceY, x, y)
            }
            TerminalScrollDestination.NONE -> mouseWheelAccumulator.reset()
        }
    }

    private fun startFling(velocityY: Float, x: Float, y: Float) {
        val controller = terminalController ?: return
        if (pinchZoomConsumed) return
        flingDestination = scrollGestureRouter.destination(controller.isMouseTrackingEnabled())
        when (flingDestination) {
            TerminalScrollDestination.LOCAL_SCROLLBACK -> scroller.fling(
                0,
                controller.viewport.scrollY.toInt(),
                0,
                -velocityY.toInt(),
                0,
                0,
                0,
                controller.viewport.maximumScrollY.toInt(),
            )
            TerminalScrollDestination.REMOTE_MOUSE -> {
                mouseWheelAccumulator.reset()
                // Remote mouse applications such as tmux already translate each wheel report into
                // line scrolling. A synthetic pixel fling floods the PTY with reports and can make
                // tmux discard intermediate copy-mode redraws, leaving the client screen corrupted.
                flingDestination = TerminalScrollDestination.NONE
                return
            }
            TerminalScrollDestination.NONE -> return
        }
        postInvalidateOnAnimation()
    }

    private fun sendRemoteMouseWheel(distanceY: Float, x: Float, y: Float) {
        val controller = terminalController ?: return
        val wheelSteps = mouseWheelAccumulator.consume(
            distanceY = distanceY,
            stepPx = lineHeightPx * MOUSE_WHEEL_LINES_PER_STEP,
        )
        repeat(abs(wheelSteps)) {
            controller.sendMouseWheel(
                up = wheelSteps < 0,
                column = ((x - horizontalPaddingPx) / cellWidthPx).toInt(),
                row = ((y - verticalPaddingPx) / lineHeightPx).toInt(),
            )
        }
    }

    private fun stopActiveFling() {
        scroller.forceFinished(true)
        flingDestination = TerminalScrollDestination.NONE
        mouseWheelAccumulator.reset()
    }

    private fun showKeyboard() {
        val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        inputMethodManager?.showSoftInput(this, 0)
    }

    fun requestTerminalInputFocus() {
        if (!directInputEnabled || !isAttachedToWindow || !requestFocus()) return
        restartTerminalInput(showKeyboard = true)
    }

    fun toggleSoftwareKeyboard() {
        if (ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) != true) {
            requestTerminalInputFocus()
            return
        }
        hideSoftwareKeyboard()
    }

    private fun hideSoftwareKeyboard() {
        val targetWindowToken = windowToken ?: rootView.windowToken ?: return
        val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        inputMethodManager?.hideSoftInputFromWindow(targetWindowToken, 0)
    }

    fun setDirectInputEnabled(enabled: Boolean) {
        if (directInputEnabled == enabled) return
        if (!enabled) resetComposingInput()
        directInputEnabled = enabled
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        if (!enabled && hasFocus()) clearFocus()
    }

    private fun restartTerminalInput(showKeyboard: Boolean) {
        post {
            if (!hasFocus()) return@post
            val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
            inputMethodManager?.restartInput(this)
            if (showKeyboard) inputMethodManager?.showSoftInput(this, 0)
        }
    }

    fun resetComposingInput() {
        activeInputConnection?.resetComposingInput()
    }

    private fun updateTextMetrics(fontSizeSp: Float, lineHeightMultiplier: Float) {
        updateTextPaintSize(fontSizeSp)
        fontMetrics = textPaint.fontMetrics
        val naturalLineHeight = fontMetrics.descent - fontMetrics.ascent +
            resources.displayMetrics.density
        lineHeightPx = ceil(naturalLineHeight * lineHeightMultiplier).coerceAtLeast(1f)
        baselineOffsetPx = -fontMetrics.ascent + (lineHeightPx - naturalLineHeight) / 2f
        cellWidthPx = TerminalRunRenderGeometry.cellAdvance(
            singleGlyphWidth = textPaint.measureText(CELL_METRIC_GLYPH),
            repeatedGlyphWidth = textPaint.measureText(CELL_METRIC_GLYPH.repeat(2)),
        )
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
        private const val CELL_METRIC_GLYPH = "M"
        private const val CURSOR_ALPHA = 150
        private const val SELECTION_ALPHA = 142
        private const val FIND_ALPHA = 92
        private const val ACTIVE_FIND_ALPHA = 154
        private const val VISUAL_BELL_ALPHA = 38
        private const val VISUAL_BELL_DURATION_MS = 120L
        private const val MIN_SELECTION_WIDTH_PX = 1f
        private const val SELECTION_HANDLE_RADIUS_DP = 6f
        private const val SELECTION_HANDLE_HIT_RADIUS_DP = 24f
        private const val SELECTION_AUTOSCROLL_EDGE_DP = 28f
        private const val MAX_ACCESSIBLE_CHARACTERS = 16_384
        private const val MOUSE_WHEEL_LINES_PER_STEP = 1.5f
        // Remote applications perform their own line acceleration. Never burst several protocol
        // reports from one touch event: tmux may coalesce or discard the resulting copy-mode frames.
        private const val MAX_MOUSE_WHEEL_STEPS_PER_EVENT = 1
        private const val MIN_PINCH_FONT_SIZE_SP = 8f
        private const val MAX_PINCH_FONT_SIZE_SP = 72f
        private const val MIN_PINCH_FONT_DELTA_SP = 0.05f
        private const val TERMINAL_RESIZE_SETTLE_MS = 120L
        private const val CURSOR_BLINK_INTERVAL_MS = 500L
        private const val MENU_OPEN_LINK = 3
        private const val MENU_COPY_LINK = 4
        private const val ENABLE_LIGATURE_FEATURES = "'liga' 1, 'calt' 1"
        private const val DISABLE_LIGATURE_FEATURES = "'liga' 0, 'calt' 0"
    }

    private data class NativeFindHighlight(
        val result: TerminalFindResult,
        val activeMatchIndex: Int,
    )
}

internal inline fun dispatchTerminalAccessibilityAnnouncement(
    accessibilityEnabled: Boolean,
    announce: () -> Unit,
): Boolean {
    if (!accessibilityEnabled) return false
    announce()
    return true
}
