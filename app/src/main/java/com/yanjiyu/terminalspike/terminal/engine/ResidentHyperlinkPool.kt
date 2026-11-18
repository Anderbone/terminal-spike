package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink

/** Cell-owned metadata only; controller history and returned immutable frames own their references. */
internal class ResidentHyperlinkPool {
    private class Entry(val canonical: TerminalHyperlink, var references: Int, val charge: Long)

    // Ordinary unlinked output never allocates or looks up a map.
    private var entries: HashMap<TerminalHyperlink, Entry>? = null
    private var chargedBytes = 0L

    fun retain(proposed: TerminalHyperlink): TerminalHyperlink? {
        entries?.get(proposed)?.let { existing ->
            existing.references += 1
            return existing.canonical
        }
        val charge = ENTRY_OVERHEAD_BYTES + 2L * (proposed.uri.length + (proposed.id?.length ?: 0))
        if (charge > MAX_CHARGED_BYTES - chargedBytes) return null
        val entry = Entry(proposed, references = 1, charge)
        (entries ?: HashMap<TerminalHyperlink, Entry>().also { entries = it })[proposed] = entry
        chargedBytes += charge
        return entry.canonical
    }

    fun release(canonical: TerminalHyperlink) {
        val current = checkNotNull(entries?.get(canonical)) { "Unowned resident hyperlink." }
        check(current.canonical === canonical) { "Cells must own canonical hyperlink objects." }
        check(current.references > 0)
        current.references -= 1
        if (current.references == 0) {
            entries?.remove(canonical)
            chargedBytes -= current.charge
            if (entries?.isEmpty() == true) entries = null
        }
    }

    companion object {
        // Conservative object/map/string overhead plus UTF-16 payload, not exact ART/JVM heap size.
        const val MAX_CHARGED_BYTES = 1_048_576L
        private const val ENTRY_OVERHEAD_BYTES = 256L
    }
}
