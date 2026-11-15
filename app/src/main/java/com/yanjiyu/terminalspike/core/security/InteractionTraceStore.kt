package com.yanjiyu.terminalspike.core.security

import java.io.File
import java.util.UUID

/** Called only by the diagnostics I/O worker. Keeps at most two bounded local files. */
internal class InteractionTraceStore(
    private val directory: File,
    private val maxFileBytes: Long = 256 * 1024,
) {
    private val current get() = File(directory, "interaction.log")
    private val previous get() = File(directory, "interaction.previous.log")

    init { require(maxFileBytes > 0) }

    fun append(lines: List<String>) {
        check(directory.isDirectory || directory.mkdirs())
        for (line in lines) {
            val bytes = (line + '\n').toByteArray(Charsets.UTF_8)
            require(bytes.size <= maxFileBytes)
            if (current.length() + bytes.size > maxFileBytes) {
                check(!previous.exists() || previous.delete())
                check(!current.exists() || current.renameTo(previous))
            }
            current.appendBytes(bytes)
        }
    }

    fun snapshot(): String = listOf(previous, current).joinToString("") {
        if (it.exists()) it.readText(Charsets.UTF_8) else ""
    }

    fun freezeIncident(header: String): String {
        check(directory.isDirectory || directory.mkdirs())
        val snapshot = header + snapshot()
        val temporary = File(directory, "last-incident.tmp")
        temporary.writeText(snapshot, Charsets.UTF_8)
        check(temporary.renameTo(File(directory, "last-incident.txt")))
        return snapshot
    }

    fun readIncident(): String = File(directory, "last-incident.txt").readText(Charsets.UTF_8)

    fun queueIncident(): String {
        val outbox = File(directory, "outbox")
        check(outbox.isDirectory || outbox.mkdirs())
        check(outbox.listFiles().orEmpty().count { it.extension == "txt" } < 4) { "Diagnostic outbox is full" }
        val id = UUID.randomUUID().toString()
        val temporary = File(outbox, "pending.tmp")
        val content = readIncident()
        check(content.toByteArray(Charsets.UTF_8).size <= 768 * 1024)
        temporary.writeText(content, Charsets.UTF_8)
        check(temporary.renameTo(File(outbox, "$id.txt")))
        File(directory, "delivery").listFiles().orEmpty()
            .sortedByDescending { it.lastModified() }.drop(16).forEach { it.delete() }
        return id
    }

    fun deliveryStatus(id: String): String? {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val status = File(File(directory, "delivery"), "$id.status")
        if (!status.exists() || status.length() > 256) return null
        val lines = status.readLines(Charsets.UTF_8)
        return lines.getOrNull(1)?.takeIf {
            lines.firstOrNull() == id && it in setOf("received", "analysis_started", "analyzed", "analysis_failed")
        }
    }
}
