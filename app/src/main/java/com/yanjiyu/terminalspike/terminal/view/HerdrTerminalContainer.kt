package com.yanjiyu.terminalspike.terminal.view

import android.content.Context
import android.view.ViewGroup
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
