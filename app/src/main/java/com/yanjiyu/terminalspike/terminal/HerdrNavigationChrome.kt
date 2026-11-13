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

    /** Resolve only a visible mobile navigation button, never text inside the pane. */
    fun switcherOpen(screen: List<TerminalLine>, columns: Int, rows: Int): Pair<Int, Int>? {
        val area = outputArea(screen, columns, rows) ?: return null
        if (area.x != 0 || area.rows >= rows - 1) return null
        val hasTopTabs = tabRows(screen, 0, rows).singleOrNull() == 0
        for (top in mobileBarTops(rows, hasTopTabs)) {
            val header = screen[top + 1]
            val divider = header.text.indexOf('│')
            if (divider < 0) continue
            val lastDivider = header.text.lastIndexOf('│')
            val right = TerminalLineGeometry.columnAtOffset(header, lastDivider)
            if (right in maxOf(0, columns - 11) until columns &&
                header.text.substring(lastDivider + 1).trim() == "switch"
            ) return (right + 1).coerceAtMost(columns - 1) to (top + 1)
            val left = TerminalLineGeometry.columnAtOffset(header, divider)
            if (left in 1..10 && header.text.substring(0, divider).trim() == "switch") {
                return 1 to (top + 1)
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

    /** Navigate mode consumes tab clicks until its visible close button is pressed. */
    fun switcherCloseForTabClick(
        screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int,
    ): Pair<Int, Int>? {
        if (column !in 0 until columns || row != 0 ||
            mobileSwitcherBody(screen, columns, rows) == null ||
            tabRows(screen, 0, rows).singleOrNull() != 0
        ) return null
        val addTab = screen[0].runs.firstOrNull {
            it.columnWidth in 1..3 && it.text.trim() == "+"
        } ?: return null
        if (column >= addTab.startColumn + addTab.columnWidth) return null
        return switcherClose(screen, columns, rows)
    }

    /** Close only a verified mobile Switch panel; desktop sidebars stay expanded. */
    fun switcherClose(screen: List<TerminalLine>, columns: Int, rows: Int): Pair<Int, Int>? {
        if (mobileSwitcherBody(screen, columns, rows) == null) return null
        val hasTopTabs = tabRows(screen, 0, rows).singleOrNull() == 0
        for (top in mobileBarTops(rows, hasTopTabs)) {
            val header = screen[top]
            val divider = header.text.indexOf('│')
            if (divider < 0) continue
            val sides = header.text.split('│')
            if (sides.size != 2 || sides.none { it.trim() == "switch" }) continue
            val dividerColumn = TerminalLineGeometry.columnAtOffset(header, divider)
            return (if (sides[0].trim() == "switch") dividerColumn + 1 else 1) to top
        }
        return null
    }

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

    /** New workspace actions only: menu, existing spaces and pane output retain their own actions. */
    fun isNewSpaceCell(screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int): Boolean {
        if (screen.size != rows || row !in 0 until rows || column < 0) return false
        val sidebar = sidebarColumns(screen, columns)
        if (sidebar == null) {
            // Herdr's mobile switcher is a full-screen list, not a desktop sidebar.
            // Require its own header and divider before recognizing the action;
            // identical words in terminal output must remain selectable.
            if (column >= columns || row !in (mobileSwitcherBody(screen, columns, rows) ?: return false)) return false
            return isNewWorkspaceActionCell(screen[row], column, columns)
        }
        if (column >= sidebar - 1) return false
        // The grouped desktop sidebar shares the mobile list, without a Switch
        // header or the legacy Spaces footer. sidebarColumns verified its
        // collapse control and tab strip; crop away the independent pane text.
        if (screen[0].runs.any { it.startColumn == sidebar - 1 && it.columnWidth == 1 && it.text == "«" }) {
            return isNewWorkspaceActionCell(screen[row], column, sidebar - 1)
        }
        if (screen[0].text.substringBefore('│').trim() != "spaces") return false
        val label = screen[row].text.substringBefore('│')
        val start = label.indexOf("new")
        return label.trim().let { it.startsWith("new ") && it.endsWith("menu") } &&
            column in maxOf(0, start - 1) until minOf(start + 4, sidebar - 1)
    }

    private fun isNewWorkspaceActionCell(line: TerminalLine, column: Int, endColumn: Int): Boolean {
        // Match within verified workspace chrome, independent of row or indentation.
        // A scrollbar can occupy column zero; it is never part of the action.
        val endOffset = TerminalLineGeometry.positionAtColumn(line, endColumn).textOffset
        val visible = line.text.take(endOffset)
        val label = if (visible.firstOrNull() in listOf('┃', '│', '▌')) visible.drop(1) else visible
        val action = label.trim()
        if (action != "+ new workspace" && action != "+") return false
        val start = TerminalLineGeometry.columnAtOffset(line, visible.indexOf(action))
        return column in start until start + action.length
    }

    /** Workspace headings only; task rows, section headings and actions stay untouched. */
    fun isSpaceCell(screen: List<TerminalLine>, columns: Int, rows: Int, column: Int, row: Int): Boolean {
        if (screen.size != rows || row !in 0 until rows || column < 0) return false
        val sidebar = sidebarColumns(screen, columns)
        if (sidebar == null) {
            if (row !in (mobileSwitcherBody(screen, columns, rows) ?: return false)) return false
            return isGroupedSpaceHeading(screen[row], column, columns)
        }
        if (column >= sidebar - 1) return false
        if (screen[0].runs.any { it.startColumn == sidebar - 1 && it.columnWidth == 1 && it.text == "«" }) {
            return isGroupedSpaceHeading(screen[row], column, sidebar - 1)
        }
        if (row == 0 || screen[0].text.substringBefore('│').trim() != "spaces") return false
        fun label(index: Int) = screen[index].text.substringBefore('│').trim()
        val footer = (1 until rows).firstOrNull {
            val text = label(it)
            text.startsWith("new ") && text.endsWith("menu")
        } ?: return false
        return row < footer && label(row).any { it.isLetterOrDigit() }
    }

    private fun isGroupedSpaceHeading(line: TerminalLine, column: Int, endColumn: Int): Boolean {
        if (column !in 1 until endColumn) return false
        // Herdr's shared mobile/grouped renderer reserves column zero for the
        // scrollbar. Workspace names (including worktree connectors) start in
        // bold; sections are also underlined, tasks start with a plain status
        // marker, and New actions start with '+'. Do not infer targets by name.
        val content = TerminalLineGeometry.sliceColumns(line, 1, endColumn) ?: return false
        val first = content.runs.firstOrNull { run -> run.text.any { !it.isWhitespace() } } ?: return false
        return first.style.bold && !first.style.underline && !first.style.conceal &&
            !content.text.trimStart().startsWith('+')
    }

    private fun sidebarColumns(screen: List<TerminalLine>, columns: Int): Int? {
        val header = screen.firstOrNull() ?: return null
        // Herdr's grouped sidebar has a single collapse control and no vertical
        // rule. Verify both that control and the independent tab strip before
        // assigning a pane rectangle; API/client width differences alone are not evidence.
        header.runs.firstOrNull { run ->
            run.startColumn in 3 until minOf(200, columns - 1) && run.columnWidth == 1 &&
                run.text == "«" && tabRows(screen, run.startColumn + 1, screen.size).size == 1
        }?.let { return it.startColumn + 1 }
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
