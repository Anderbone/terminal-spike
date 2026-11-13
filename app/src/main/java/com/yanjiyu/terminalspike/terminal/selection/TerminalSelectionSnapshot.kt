package com.yanjiyu.terminalspike.terminal.selection

/** A bounded reader used only while a local selection is active, never on the transport hot path. */
internal class TerminalSelectionSnapshot private constructor(
    val firstSourceRow: Int,
    private val lines: List<TerminalSelectableLine>,
    override val selectionColumnCount: Int,
) : TerminalSelectionSource {
    private val indices = lines.mapIndexed { index, line -> line.anchor to index }.toMap()
    override val selectionLineCount get() = lines.size
    override fun selectionLineAt(index: Int) = lines.getOrNull(index)
    override fun selectionIndexOf(anchor: TerminalLineAnchor) = indices[anchor]

    companion object {
        fun capture(source: TerminalSelectionSource, hitRow: Int): TerminalSelectionSnapshot? {
            val first = (hitRow - 2048).coerceIn(0, (source.selectionLineCount - 4096).coerceAtLeast(0))
            val end = minOf(source.selectionLineCount, first + 4096)
            val lines = (first until end).map { source.selectionLineAt(it) ?: return null }
            return TerminalSelectionSnapshot(first, lines, source.selectionColumnCount)
        }
    }
}
