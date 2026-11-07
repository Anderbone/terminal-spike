package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi

/** Record once; hardware animation frames reuse a display list instead of replaying every glyph. */
internal class TerminalResizeSnapshot(private val picture: Picture, width: Int, height: Int) {
    private val hardware: HardwareFrame? = if (Build.VERSION.SDK_INT >= 29) {
        HardwareFrame(picture, width, height)
    } else null

    fun draw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated && hardware != null) {
            hardware.draw(canvas)
        } else canvas.drawPicture(picture)
    }

    @RequiresApi(29)
    private class HardwareFrame(picture: Picture, width: Int, height: Int) {
        private val node = RenderNode("Terminal IME transition").apply {
            setPosition(0, 0, width, height)
            val canvas = beginRecording(width, height)
            canvas.drawPicture(picture)
            endRecording()
        }

        fun draw(canvas: Canvas) = canvas.drawRenderNode(node)
    }
}
