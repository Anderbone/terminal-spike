package com.yanjiyu.terminalspike.ui.terminal

import com.yanjiyu.terminalspike.terminal.TerminalTranscriptWriteResult
import com.yanjiyu.terminalspike.terminal.TerminalTranscriptWriter
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.io.OutputStream

internal const val TERMINAL_TRANSCRIPT_MIME_TYPE = "text/plain"
internal const val TERMINAL_TRANSCRIPT_FILE_NAME = "terminal-transcript.txt"

/** Provider-neutral streaming seam used by the SAF launcher and deterministic JVM tests. */
internal fun writeTerminalTranscriptDocument(
    lines: Iterable<TerminalLine>,
    openOutput: () -> OutputStream?,
    maxBytes: Long = TerminalTranscriptWriter.DEFAULT_MAX_BYTES,
): Result<TerminalTranscriptWriteResult> = runCatching {
    val output = requireNotNull(openOutput()) { "The selected document could not be opened." }
    output.use { stream -> TerminalTranscriptWriter.write(lines, stream, maxBytes) }
}
