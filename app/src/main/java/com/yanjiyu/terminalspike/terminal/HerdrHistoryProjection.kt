package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalLine

/** Share one cell rectangle between native history drawing and gesture hit testing. */
internal class HerdrHistoryProjection {
    private var source: HerdrPaneHistory? = null
    private var screen: List<TerminalLine>? = null
    private var columns = 0
    private var rows = 0
    private var retainingReader = false
    private var projected: HerdrPaneHistory? = null

    fun project(
        source: HerdrPaneHistory?, screen: List<TerminalLine>?, columns: Int, rows: Int,
        retainReader: Boolean = false,
    ): HerdrPaneHistory? {
        if (this.source !== source || this.screen !== screen || this.columns != columns || this.rows != rows ||
            retainingReader != retainReader
        ) {
            val previous = projected
            val next = projectHerdrHistory(source, screen, columns, rows)
            // Remote redraws can publish a frame with its navigation temporarily erased.
            // An established reader still owns the same pixels and captured rows. Only
            // reuse its verified placement for the same pane, source geometry and grid;
            // a successful different placement, resize or identity change remains authoritative.
            projected = next ?: previous?.takeIf {
                retainReader && source != null && this.source?.samePane(source) == true &&
                    this.columns == columns && this.rows == rows
            }?.let { requireNotNull(source).copy(x = it.x, y = it.y) }
            this.source = source
            this.screen = screen
            this.columns = columns
            this.rows = rows
            retainingReader = retainReader
        }
        return projected
    }

}

internal fun projectHerdrHistory(source: HerdrPaneHistory?, screen: List<TerminalLine>?, columns: Int, rows: Int): HerdrPaneHistory? {
    source ?: return null
    if (source.x < 0 || source.y < 0 || source.x + source.columns > columns || source.y + source.rows > rows) return null
    val surface = source.surface ?: return source // Legacy API/test adapters use outer coordinates.
    if (source.x < surface.x || source.y < surface.y || source.x + source.columns > surface.x + surface.columns ||
        source.y + source.rows > surface.y + surface.rows
    ) return null
    if (surface.x > 0 || surface.y > 0) return source.takeIf {
        surface.x + surface.columns <= columns && surface.y + surface.rows <= rows
    }
    val area = screen?.let { HerdrNavigationChrome.outputArea(it, columns, rows) }
        ?: return source.takeIf { surface.columns == columns && surface.rows == rows }
    // Other connected clients can own the server size. Never stretch their
    // history or guess a sidebar width from the difference between two grids.
    if (surface.columns != area.columns || surface.rows != area.rows) return null
    return source.copy(x = source.x + area.x, y = source.y + area.y)
}
