package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.CodexInputStatus
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CodexInputStatusReaderTest {
    private fun event(reset: Long = 4_000_000_000L, bucket: String = "codex") = """
        {"type":"event_msg","payload":{"type":"token_count","info":{"model_context_window":112000,"total_token_usage":{"total_tokens":9999999},"last_token_usage":{"total_tokens":62000}},"rate_limits":{"limit_id":"$bucket","primary":{"window_minutes":10080,"used_percent":30,"resets_at":$reset},"secondary":{"window_minutes":300,"used_percent":14.5,"resets_at":$reset}}}}
    """.trimIndent()

    private fun read(events: String, indexedThread: String = "thread-A", outside: Boolean = false): CodexInputStatus? {
        val home = Files.createTempDirectory("codex-input-status").toFile()
        try {
            val path = home.resolve(if (outside) "outside.jsonl" else "sessions/rollout.jsonl")
            path.parentFile.mkdirs()
            path.writeText(events)
            val setup = """
import sqlite3, os
from pathlib import Path
db = sqlite3.connect(os.path.join(os.environ['CODEX_HOME'], 'state_5.sqlite'))
db.execute('CREATE TABLE threads (id TEXT, rollout_path TEXT)')
db.execute('INSERT INTO threads VALUES (?, ?)', ('$indexedThread', str(Path(os.environ['CODEX_HOME']) / '${path.relativeTo(home).invariantSeparatorsPath}')))
db.commit()
db.close()
            """.trimIndent()
            val process = ProcessBuilder("python3", "-c", setup + "\n" + CodexInputStatusReader.script, "thread-A")
                .apply { environment()["CODEX_HOME"] = home.absolutePath }.start()
            assertTrue("Bounded usage reader must finish", process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.readBytes()
            assertEquals(process.errorStream.readBytes().toString(Charsets.UTF_8), 0, process.exitValue())
            return CodexInputStatusReader.capture({ TmuxExecOutput(output, 0) }, "thread-A")
        } finally { home.deleteRecursively() }
    }

    @Test fun readsLastContextUsageAndMatchesQuotaWindowsByDurationInsteadOfPosition() {
        assertEquals(CodexInputStatus(50, 70), read(event()))
    }

    @Test fun neverSubstitutesAnotherThreadOrReadsAPathOutsideSessions() {
        assertEquals(CodexInputStatus(), read(event(), indexedThread = "thread-B"))
        assertEquals(CodexInputStatus(), read(event(), outside = true))
        var invoked = false
        assertNull(CodexInputStatusReader.capture({ invoked = true; error("must not run") }, "../other"))
        assertFalse(invoked)
    }

    @Test fun compactionAndExpiredOrNonCodexLimitsDoNotDisplayStalePercentages() {
        assertEquals(CodexInputStatus(null, 70), read(event() + "\n" +
            """{"type":"event_msg","payload":{"type":"context_compacted"}}"""))
        assertEquals(CodexInputStatus(50), read(event(reset = 1)))
        assertEquals(CodexInputStatus(50), read(event(bucket = "other-model")))
    }

    @Test fun boundedTailDoesNotScanBackThroughLargeToolOutputForAnOldSnapshot() {
        assertEquals(CodexInputStatus(), read(event() + "\n" + "x".repeat(300_000)))
        assertEquals(CodexInputStatus(50, 70), read("x".repeat(300_000) + "\n" + event()))
    }

    @Test fun rejectsOutOfRangeTransportValuesAndShowsUnknownWithoutInventingOneHundredPercent() {
        assertNull(CodexInputStatusReader.capture({ TmuxExecOutput("""{"context":101}""".toByteArray(), 0) }, "thread-A"))
        assertEquals("Context — · Week —", CodexInputStatus().compactText())
        assertEquals("Context 50% · Week 70%", CodexInputStatus(50, 70).compactText())
    }
}
