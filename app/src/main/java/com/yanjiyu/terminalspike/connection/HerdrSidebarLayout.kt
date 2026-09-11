package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Outer terminal area, not the focused pane (which may be the right-hand split). */
data class HerdrSidebarLayout(val sidebarColumns: Int, val terminalColumns: Int)

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
    HerdrSidebarLayout(left, left + width)
}.getOrNull()
