package com.yanjiyu.terminalspike.testing

/** Require every exact output row, in order; echoed commands cannot count as output. */
internal fun assertOrderedTerminalMarkers(lines: List<String>, prefix: String, count: Int) {
    require(count > 0)
    var after = 0
    for (number in 1..count) {
        val expected = prefix + number.toString().padStart(3, '0')
        val index = (after until lines.size).firstOrNull { lines[it].trimEnd() == expected }
            ?: throw AssertionError("Missing or out-of-order terminal marker $expected after row $after")
        after = index + 1
    }
}
