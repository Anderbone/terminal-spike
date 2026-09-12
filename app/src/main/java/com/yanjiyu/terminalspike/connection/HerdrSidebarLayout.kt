package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Outer terminal area, not the focused pane (which may be the right-hand split). */
data class HerdrSidebarLayout(
    val sidebarColumns: Int,
    val terminalColumns: Int,
    val terminalTop: Int? = null,
    val terminalHeight: Int? = null,
) {
    /** Only desktop navigation chrome; never infer controls inside a pane or mobile overlay. */
    fun isContextMenuCell(column: Int, row: Int, columns: Int, rows: Int): Boolean {
        if (columns != terminalColumns || column !in 0 until columns || row !in 0 until rows) return false
        val top = terminalTop ?: return false
        val height = terminalHeight ?: return false
        val bottom = top + height
        // Desktop tabs occupy exactly one top or bottom row; hidden tabs occupy neither.
        // Herdr's separate mobile layout has a two-row header and its own menu.
        if (top !in 0..1 || bottom !in (rows - 1)..rows) return false
        return column < sidebarColumns || (top == 1 && row == 0) ||
            (top == 0 && bottom == rows - 1 && row == bottom)
    }
}

internal fun captureHerdrSidebarLayout(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
): HerdrSidebarLayout? = runCatching {
    val command = listOf(choice.executable, "--session", choice.sessionName, "pane", "layout")
        .joinToString(" ", transform = ::quotePosixShellArgument)
    val output = runner.run(command)
    require(output.exitStatus == 0 && output.stdout.size <= 64 * 1024)
    val root = Json.parseToJsonElement(output.stdout.toString(Charsets.UTF_8)) as JsonObject
    val layout = (root.getValue("result") as JsonObject).getValue("layout") as JsonObject
    val area = layout.getValue("area") as JsonObject
    fun integer(key: String) = requireNotNull((area.getValue(key) as JsonPrimitive).intOrNull)
    val left = integer("x")
    val width = integer("width")
    require(left in 0..200 && width in 1..500 && left + width <= 500)
    val top = integer("y")
    val height = integer("height")
    require(top in 0..500 && height in 1..500 && top + height <= 500)
    HerdrSidebarLayout(left, left + width, top, height)
}.getOrNull()
