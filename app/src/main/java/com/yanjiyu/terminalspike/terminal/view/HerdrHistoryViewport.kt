package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport

/** A reader pins its snapshot until returning live; new output cannot move its pixel anchor. */
internal class HerdrHistoryViewport {
    val viewport = TerminalViewport()
    var snapshot: HerdrPaneHistory? = null
        private set

    fun begin(source: HerdrPaneHistory, lineHeight: Float) {
        snapshot = source
        viewport.updateGeometry((source.rows * lineHeight).toInt(), lineHeight)
        viewport.updateContent(source.lines.size, null)
        viewport.scrollTo(viewport.maximumScrollY - source.offsetFromBottom * lineHeight)
    }

    fun retainSource(source: HerdrPaneHistory?): Boolean {
        val pinned = snapshot ?: return true
        if (source != null && pinned.samePane(source)) return true
        clear()
        return false
    }

    fun beginScroll(source: HerdrPaneHistory, lineHeight: Float, deltaPx: Float) {
        // A swipe past live bottom must not flash an older cached snapshot.
        if (snapshot == null && deltaPx < 0f) begin(source, lineHeight)
    }

    fun scrollBy(deltaPx: Float) {
        if (snapshot == null) return
        viewport.scrollBy(deltaPx)
        if (viewport.autoFollow) clear()
    }

    fun clear() {
        snapshot = null
        viewport.updateContent(0, null)
    }
}
