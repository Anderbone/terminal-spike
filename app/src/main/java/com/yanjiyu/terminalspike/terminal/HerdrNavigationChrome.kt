package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.HerdrPaneArea
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry

/** Client-owned navigation in Herdr 0.9.x; API pane coordinates exclude this chrome. */
internal object HerdrNavigationChrome {
    fun outputArea(screen: List<TerminalLine>, columns: Int, rows: Int): HerdrPaneArea? {
        if (screen.size != rows || rows < 3) return null
        val sidebarColumns = sidebarColumns(screen, columns)
        if (sidebarColumns != null) {
            val tabRows = tabRows(screen, sidebarColumns, rows)
            if (tabRows.size > 1) return null
            return HerdrPaneArea(sidebarColumns, if (tabRows.singleOrNull() == 0) 1 else 0,
                columns - sidebarColumns, rows - tabRows.size)
        }
        // The two-row mobile header remains present behind its switcher. Only the
        // live Switch affordance permits an overlay; close/× means it is covered.
        val header = screen[1].text
        val dividerOffset = header.lastIndexOf('│')
        if (dividerOffset < 0 || header.substring(dividerOffset + 1).trim() != "switch") return null
        val dividerColumn = TerminalLineGeometry.columnAtOffset(screen[1], dividerOffset)
        return if (dividerColumn in maxOf(0, columns - 11) until columns) HerdrPaneArea(0, 2, columns, rows - 2) else null
    }

    fun isContextMenuCell(
        screen: List<TerminalLine>,
        columns: Int,
        rows: Int,
        column: Int,
        row: Int,
    ): Boolean {
        if (screen.size != rows || column !in 0 until columns || row !in 0 until rows) return false
        val sidebarColumns = sidebarColumns(screen, columns) ?: return false
        if (column < sidebarColumns) return true
        if (row != 0 && row != rows - 1) return false
        return tabRows(screen, sidebarColumns, rows).singleOrNull() == row
    }

    /** Only an existing desktop tab, excluding the sidebar and the new-tab control. */
    fun isTabCell(screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int): Boolean {
        if (screen.size != rows || column !in 0 until columns || row !in 0 until rows) return false
        val sidebar = sidebarColumns(screen, columns) ?: return false
        if (column < sidebar || tabRows(screen, sidebar, rows).singleOrNull() != row) return false
        val addTab = screen[row].runs.firstOrNull {
            it.startColumn >= sidebar && it.columnWidth in 1..3 && it.text.trim() == "+"
        } ?: return false
        return column < addTab.startColumn
    }

    /** Expanded Spaces entries only; headers, footer controls and Agents stay untouched. */
    fun isSpaceCell(screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int): Boolean {
        if (screen.size != rows || row !in 1 until rows || column < 0) return false
        val sidebar = sidebarColumns(screen, columns) ?: return false
        if (column >= sidebar - 1 || screen[0].text.substringBefore('│').trim() != "spaces") return false
        fun label(index: Int) = screen[index].text.substringBefore('│').trim()
        val footer = (1 until rows).firstOrNull {
            val text = label(it)
            text.startsWith("new ") && text.endsWith("menu")
        } ?: return false
        return row < footer && label(row).any { it.isLetterOrDigit() }
    }

    private fun sidebarColumns(screen: List<TerminalLine>, columns: Int): Int? {
        val header = screen.firstOrNull() ?: return null
        val dividerOffset = header.text.indexOf('│')
        if (dividerOffset < 0 || header.text.take(dividerOffset).trim() !in listOf("spaces", "machines")) return null
        val dividerColumn = TerminalLineGeometry.columnAtOffset(header, dividerOffset)
        if (dividerColumn !in 3 until minOf(200, columns - 1)) return null
        // A header label alone is not evidence of chrome. Require its continuous
        // outer boundary in the live frame, including after a multi-client resize.
        if (screen.any { line ->
                val offset = TerminalLineGeometry.positionAtColumn(line, dividerColumn).textOffset
                line.text.getOrNull(offset) != '│'
            }
        ) return null
        return dividerColumn + 1
    }

    private fun tabRows(screen: List<TerminalLine>, sidebarColumns: Int, rows: Int): List<Int> {
        // The client renders the new-tab control as a separate three-cell run.
        // A tab label can be arbitrary; pane output containing '+' stays selectable.
        return listOf(0, rows - 1).filter { candidate ->
            screen[candidate].runs.any { run ->
                run.startColumn >= sidebarColumns && run.columnWidth in 1..3 && run.text.trim() == "+"
            }
        }
    }
}
