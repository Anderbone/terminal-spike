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
        val hasTopTabs = tabRows(screen, 0, rows).singleOrNull() == 0
        for (top in mobileBarTops(rows, hasTopTabs)) {
            val header = screen[top + 1]
            val firstDivider = header.text.indexOf('│')
            if (firstDivider < 0) continue
            val lastDivider = header.text.lastIndexOf('│')
            val rightButton = TerminalLineGeometry.columnAtOffset(header, lastDivider) in
                maxOf(0, columns - 11) until columns &&
                header.text.substring(lastDivider + 1).trim() == "switch"
            val leftButton = TerminalLineGeometry.columnAtOffset(header, firstDivider) in 1..10 &&
                header.text.substring(0, firstDivider).trim() == "switch"
            if (rightButton || leftButton) {
                val tabHeight = if (hasTopTabs) 1 else 0
                return HerdrPaneArea(0, if (top <= 1) top + 2 else tabHeight, columns, rows - 2 - tabHeight)
            }
        }
        return null
    }

    /** Visible switcher body, excluding its navigation bar and horizontal rule. */
    private fun mobileSwitcherBody(screen: List<TerminalLine>, columns: Int, rows: Int): IntRange? {
        if (screen.size != rows || rows < 4) return null
        val hasTopTabs = tabRows(screen, 0, rows).singleOrNull() == 0
        for (top in mobileBarTops(rows, hasTopTabs)) {
            val first = screen[top].text.split('│')
            val second = screen[top + 1].text.split('│')
            if (first.size != 2 || second.size != 2) continue
            val titleSide = first.indexOfFirst { it.trim() == "switch" }
            if (titleSide < 0) continue
            val buttonSide = 1 - titleSide
            if (first[buttonSide].trim() != "close" && second[buttonSide].trim() !in listOf("close", "×")) continue
            val divider = TerminalLineGeometry.columnAtOffset(screen[top], screen[top].text.indexOf('│'))
            if (divider !in 1..10 && divider !in maxOf(0, columns - 11) until columns) continue
            val rule = if (top <= 1) top + 2 else top - 1
            val ruleText = screen[rule].text.trim()
            if (ruleText.isEmpty() || ruleText.any { it != '─' }) continue
            return if (top <= 1) top + 3 until rows else (if (hasTopTabs) 1 else 0) until rule
        }
        return null
    }

    private fun mobileBarTops(rows: Int, hasTopTabs: Boolean): List<Int> =
        if (hasTopTabs) listOf(0, 1, rows - 2).distinct() else listOf(0, rows - 2).distinct()

    fun isContextMenuCell(
        screen: List<TerminalLine>,
        columns: Int,
        rows: Int,
        column: Int,
        row: Int,
    ): Boolean {
        if (screen.size != rows || column !in 0 until columns || row !in 0 until rows) return false
        mobileSwitcherBody(screen, columns, rows)?.let { return row in it }
        val sidebarColumns = sidebarColumns(screen, columns) ?: return isTabCell(screen, columns, rows, column, row)
        if (column < sidebarColumns) return true
        if (row != 0 && row != rows - 1) return false
        return tabRows(screen, sidebarColumns, rows).singleOrNull() == row
    }

    /** Existing tabs in verified desktop or mobile chrome, excluding the new-tab control. */
    fun isTabCell(screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int): Boolean {
        if (screen.size != rows || column !in 0 until columns || row !in 0 until rows) return false
        val sidebar = sidebarColumns(screen, columns) ?: run {
            if (row != 0 || outputArea(screen, columns, rows) == null) return false
            0
        }
        if (column < sidebar || tabRows(screen, sidebar, rows).singleOrNull() != row) return false
        val addTab = screen[row].runs.firstOrNull {
            it.startColumn >= sidebar && it.columnWidth in 1..3 && it.text.trim() == "+"
        } ?: return false
        return column < addTab.startColumn
    }

    /** The New footer only: menu, existing spaces and pane output retain their own actions. */
    fun isNewSpaceCell(screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int): Boolean {
        if (screen.size != rows || row !in 0 until rows || column < 0) return false
        val sidebar = sidebarColumns(screen, columns)
        if (sidebar == null) {
            // Herdr's mobile switcher is a full-screen list, not a desktop sidebar.
            // Require its own header and divider before recognizing the action;
            // identical words in terminal output must remain selectable.
            if (column >= columns || row !in (mobileSwitcherBody(screen, columns, rows) ?: return false)) return false
            // Column zero belongs to the switcher's optional left scrollbar.
            val label = screen[row].text.drop(1)
            val action = "+ new workspace"
            val start = label.indexOf(action) + 1
            return label.trim() == action && column in start until start + action.length
        }
        if (column >= sidebar - 1 || screen[0].text.substringBefore('│').trim() != "spaces") return false
        val label = screen[row].text.substringBefore('│')
        val start = label.indexOf("new")
        return label.trim().let { it.startsWith("new ") && it.endsWith("menu") } &&
            column in maxOf(0, start - 1) until minOf(start + 4, sidebar - 1)
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
