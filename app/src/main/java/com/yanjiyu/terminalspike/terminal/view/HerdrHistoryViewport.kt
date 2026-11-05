package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineAnchor
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectableLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionSource
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkResolver
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkTarget
import kotlin.math.floor

/** A reader pins its snapshot until returning live; new output cannot move its pixel anchor. */
internal class HerdrHistoryViewport : TerminalSelectionSource {
    val viewport = TerminalViewport()
    var snapshot: HerdrPaneHistory? = null
        private set

    private var remoteOffsetFromBottom = 0
    private var generation = 0L

    override val selectionLineCount: Int get() = snapshot?.lines?.size ?: 0

    override fun selectionLineAt(index: Int): TerminalSelectableLine? = snapshot?.lines?.getOrNull(index)?.let {
        TerminalSelectableLine(TerminalLineAnchor(TerminalLineSpace.VOLATILE_SCREEN, generation, index), it)
    }

    override fun selectionIndexOf(anchor: TerminalLineAnchor): Int? = anchor.volatileRow.takeIf {
        anchor.space == TerminalLineSpace.VOLATILE_SCREEN && anchor.id == generation && it in 0 until selectionLineCount
    }

    fun linkAt(
        x: Float, y: Float, cellWidth: Float, lineHeight: Float, paddingX: Float, paddingY: Float,
        osc8Enabled: Boolean, plainTextUrlsEnabled: Boolean,
    ): TerminalLinkTarget? {
        val pane = snapshot ?: return null
        if (!x.isFinite() || !y.isFinite() || cellWidth <= 0f || lineHeight <= 0f) return null
        val left = paddingX + pane.x * cellWidth
        val top = paddingY + pane.y * lineHeight
        if (x < left || x >= left + pane.columns * cellWidth || y < top || y >= top + pane.rows * lineHeight) return null
        val column = floor((x - left) / cellWidth).toInt()
        val row = floor((y - top + viewport.scrollY) / lineHeight).toInt()
        return TerminalLinkResolver.find(this, row, column, osc8Enabled, plainTextUrlsEnabled)
    }

    val holdingLatest: Boolean get() = snapshot != null && viewport.autoFollow

    fun begin(source: HerdrPaneHistory, lineHeight: Float) {
        generation++
        snapshot = source
        remoteOffsetFromBottom = source.offsetFromBottom
        viewport.updateGeometry((source.rows * lineHeight).toInt(), lineHeight)
        viewport.updateContent(source.lines.size, null)
        viewport.scrollTo(viewport.maximumScrollY - source.offsetFromBottom * lineHeight)
    }

    fun retainSource(source: HerdrPaneHistory?): Boolean {
        val pinned = snapshot ?: return true
        if (source != null && pinned.samePane(source)) {
            remoteOffsetFromBottom = source.offsetFromBottom
            if (viewport.autoFollow) {
                // At latest, refresh the overlay instead of freezing new output. The remote
                // viewport may still be older; only expose it once it too reaches bottom.
                snapshot = source
                generation++
                viewport.updateContent(source.lines.size, null)
                viewport.jumpToBottom()
                releaseAtLiveBottom()
            }
            return true
        }
        clear()
        return false
    }

    fun beginScroll(source: HerdrPaneHistory, lineHeight: Float, deltaPx: Float) {
        // A swipe past live bottom must not flash an older cached snapshot.
        if (snapshot == null && (deltaPx < 0f || source.offsetFromBottom > 0)) begin(source, lineHeight)
    }

    fun scrollBy(deltaPx: Float) {
        if (snapshot == null) return
        viewport.scrollBy(deltaPx)
        releaseAtLiveBottom()
    }

    fun scrollTo(positionPx: Float) {
        if (snapshot == null) return
        viewport.scrollTo(positionPx)
        releaseAtLiveBottom()
    }

    private fun releaseAtLiveBottom() {
        if (viewport.autoFollow && remoteOffsetFromBottom == 0) clear()
    }

    fun clear() {
        snapshot = null
        viewport.updateContent(0, null)
    }
}
