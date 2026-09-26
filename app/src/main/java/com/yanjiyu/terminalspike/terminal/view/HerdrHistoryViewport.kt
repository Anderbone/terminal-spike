package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport

/** A reader pins its snapshot until returning live; new output cannot move its pixel anchor. */
internal class HerdrHistoryViewport {
    val viewport = TerminalViewport()
    var snapshot: HerdrPaneHistory? = null
        private set

    private var remoteOffsetFromBottom = 0

    val holdingLatest: Boolean get() = snapshot != null && viewport.autoFollow

    fun begin(source: HerdrPaneHistory, lineHeight: Float) {
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
