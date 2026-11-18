package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import java.util.Collections
import java.util.IdentityHashMap

/** Bounded, real-parser fixture shared by JVM and native tests; no phone heap exhaustion. */
internal object ResidentHyperlinkFixture {
    const val BUDGET = 1_048_576L
    private val uriPrefix = "https://example.test/" + "a".repeat(960)

    fun uri(index: Int): String = "$uriPrefix/$index"
    fun linked(index: Int, text: String = "x", uri: String = uri(index)): String =
        "\u001b]8;id=fixture;$uri\u001b\\$text\u001b]8;;\u001b\\"

    fun fill(engine: VtTerminalEngine, first: Int = 0, count: Int = 1_000): TerminalFrameUpdate =
        engine.accept(buildString { repeat(count) { append(linked(first + it)) } }.encodeToByteArray())

    /** Identity accounting observes cells directly: equal-but-distinct objects must all count. */
    fun residentLinks(engine: VtTerminalEngine): List<TerminalHyperlink> = buildList {
        for (name in listOf("primary", "alternate")) {
            val screen = engine.javaClass.getDeclaredField(name).run { isAccessible = true; get(engine) }
            val cells = screen.javaClass.getDeclaredField("cells").run {
                isAccessible = true
                @Suppress("UNCHECKED_CAST")
                get(screen) as Array<Array<Any>>
            }
            val hyperlink = cells[0][0].javaClass.getDeclaredField("hyperlink").apply { isAccessible = true }
            cells.forEach { row -> row.forEach { cell ->
                (hyperlink.get(cell) as? TerminalHyperlink)?.let(::add)
            } }
        }
    }

    fun identityCharge(engine: VtTerminalEngine): Long {
        val unique = Collections.newSetFromMap(IdentityHashMap<TerminalHyperlink, Boolean>())
        unique.addAll(residentLinks(engine))
        return unique.sumOf { 256L + 2L * (it.uri.length + (it.id?.length ?: 0)) }
    }
}
