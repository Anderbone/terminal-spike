package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.yanjiyu.terminalspike.core.model.ModelLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

data class TmuxSession(
    val id: String,
    val name: String,
    val windowCount: Int,
    val attachedClientCount: Int,
    val createdAtEpochSeconds: Long,
    val previewLines: List<String> = emptyList(),
)

enum class TmuxAvailability {
    AVAILABLE,
    NOT_INSTALLED,
    CHECK_FAILED,
}

data class TmuxSessionCatalog(
    val sessions: List<TmuxSession> = emptyList(),
    val availability: TmuxAvailability = TmuxAvailability.CHECK_FAILED,
    val deleteFailed: Boolean = false,
    val activeSessionId: String? = null,
)

internal data class TmuxSessionPrompt(
    val promptToken: Long,
    val sessions: List<TmuxSession>,
    val availability: TmuxAvailability = TmuxAvailability.AVAILABLE,
    val deleteFailed: Boolean = false,
) : ConnectionPrompt {
    init {
        require(promptToken > 0L)
        require(sessions.size <= MAX_TMUX_SESSIONS)
    }
}

internal sealed interface TmuxStartupChoice {
    val executable: String

    data class NewSession(
        override val executable: String,
        val existingSessionIds: Set<String> = emptySet(),
    ) : TmuxStartupChoice {
        init {
            require(executable.isTmuxExecutablePath())
            require(existingSessionIds.size <= MAX_TMUX_SESSIONS)
            require(existingSessionIds.all(String::isTmuxSessionId))
        }
    }

    data class Attach(
        override val executable: String,
        val sessionId: String,
    ) : TmuxStartupChoice {
        init {
            require(executable.isTmuxExecutablePath())
            require(sessionId.isTmuxSessionId())
        }
    }
}

/** Owns one authenticated, pre-shell tmux selection interaction. */
internal class TmuxSessionSelector(
    private val commandRunner: TmuxCommandRunner,
    private val onPrompt: (TmuxSessionPrompt) -> Unit,
) {
    constructor(session: Session, onPrompt: (TmuxSessionPrompt) -> Unit) : this(
        JschTmuxCommandRunner(session),
        onPrompt,
    )

    private val token = nextPromptToken.getAndIncrement()
    private val actions = LinkedBlockingQueue<TmuxSelectorAction>()

    fun awaitChoice(): TmuxStartupChoice? {
        var inspection = inspectTmuxSessions(commandRunner)
        var deleteFailed = false
        while (true) {
            onPrompt(
                TmuxSessionPrompt(
                    promptToken = token,
                    sessions = inspection.sessions,
                    availability = inspection.availability,
                    deleteFailed = deleteFailed,
                ),
            )
            when (val action = actions.take()) {
                TmuxSelectorAction.Cancel -> return null
                is TmuxSelectorAction.Select -> {
                    if (action.promptToken != token) continue
                    val target = action.sessionId ?: return null
                    if (
                        target == TMUX_NEW_SESSION_SELECTION &&
                        inspection.availability == TmuxAvailability.AVAILABLE
                    ) {
                        return TmuxStartupChoice.NewSession(
                            executable = requireNotNull(inspection.executable),
                            existingSessionIds = inspection.sessions.mapTo(linkedSetOf(), TmuxSession::id),
                        )
                    }
                    if (inspection.sessions.none { it.id == target }) {
                        deleteFailed = true
                        continue
                    }
                    return TmuxStartupChoice.Attach(
                        executable = requireNotNull(inspection.executable),
                        sessionId = target,
                    )
                }
                is TmuxSelectorAction.Delete -> {
                    if (
                        action.promptToken != token ||
                        inspection.sessions.none { it.id == action.sessionId }
                    ) {
                        continue
                    }
                    val deleted = deleteTmuxSession(
                        commandRunner = commandRunner,
                        executable = requireNotNull(inspection.executable),
                        sessionId = action.sessionId,
                    )
                    val refreshed = inspectTmuxSessions(commandRunner)
                    deleteFailed = !deleted || refreshed.availability != TmuxAvailability.AVAILABLE
                    inspection = refreshed
                }
            }
        }
    }

    fun answer(promptToken: Long, sessionId: String?) {
        if (
            promptToken == token &&
            (
                sessionId == null ||
                    sessionId == TMUX_NEW_SESSION_SELECTION ||
                    sessionId.isTmuxSessionId()
                )
        ) {
            actions.offer(TmuxSelectorAction.Select(promptToken, sessionId))
        }
    }

    fun delete(promptToken: Long, sessionId: String) {
        if (promptToken == token && sessionId.isTmuxSessionId()) {
            actions.offer(TmuxSelectorAction.Delete(promptToken, sessionId))
        }
    }

    fun cancel() {
        actions.offer(TmuxSelectorAction.Cancel)
    }

    private companion object {
        val nextPromptToken = AtomicLong(1L)
    }
}

internal fun interface TmuxCommandRunner {
    fun run(command: String): TmuxExecOutput
}

internal data class TmuxExecOutput(val stdout: ByteArray, val exitStatus: Int)

private data class TmuxInspection(
    val availability: TmuxAvailability,
    val executable: String? = null,
    val sessions: List<TmuxSession> = emptyList(),
)

internal class JschTmuxCommandRunner(
    private val session: Session,
    private val limits: TmuxExecLimits = DEFAULT_TMUX_EXEC_LIMITS,
) : TmuxCommandRunner {
    override fun run(command: String): TmuxExecOutput = runBoundedTmuxExec(session, command, limits)
}

internal data class TmuxExecLimits(
    val channelConnectTimeoutMillis: Int,
    val totalTimeoutMillis: Long,
    val maximumOutputBytes: Int,
) {
    init {
        require(channelConnectTimeoutMillis in 1..60_000)
        require(totalTimeoutMillis in 1..60_000)
        require(maximumOutputBytes in 1..TmuxPaneCapture.MAX_CAPTURE_BYTES)
    }
}

private sealed interface TmuxSelectorAction {
    data class Select(val promptToken: Long, val sessionId: String?) : TmuxSelectorAction
    data class Delete(val promptToken: Long, val sessionId: String) : TmuxSelectorAction
    data object Cancel : TmuxSelectorAction
}

private fun inspectTmuxSessions(commandRunner: TmuxCommandRunner): TmuxInspection = runCatching {
    val output = commandRunner.run(TMUX_LIST_COMMAND)
    val lines = output.stdout.toString(Charsets.UTF_8).lineSequence().toList()
    val markerIndex = lines.indexOf(TMUX_AVAILABLE_MARKER)
    if (markerIndex < 0) return TmuxInspection(TmuxAvailability.NOT_INSTALLED)
    val executable = lines.getOrNull(markerIndex + 1)
        ?.takeIf(String::isTmuxExecutablePath)
        ?: return TmuxInspection(TmuxAvailability.CHECK_FAILED)
    TmuxInspection(
        availability = TmuxAvailability.AVAILABLE,
        executable = executable,
        sessions = lines.asSequence()
            .drop(markerIndex + 2)
            .mapNotNull(::parseTmuxSessionLine)
            .distinctBy(TmuxSession::id)
            .take(MAX_TMUX_SESSIONS)
            .toList(),
    )
}.getOrElse { TmuxInspection(TmuxAvailability.CHECK_FAILED) }

internal fun queryTmuxSessions(commandRunner: TmuxCommandRunner): List<TmuxSession>? =
    inspectTmuxSessions(commandRunner).takeIf {
        it.availability == TmuxAvailability.AVAILABLE
    }?.sessions

internal fun queryTmuxExecutable(commandRunner: TmuxCommandRunner): String? =
    inspectTmuxSessions(commandRunner).takeIf {
        it.availability == TmuxAvailability.AVAILABLE
    }?.executable

internal fun queryTmuxSessionCatalog(
    commandRunner: TmuxCommandRunner,
    includePreviews: Boolean = false,
): TmuxSessionCatalog =
    inspectTmuxSessions(commandRunner).let { inspection ->
        TmuxSessionCatalog(
            sessions = if (includePreviews) {
                inspection.sessions.withTmuxPreviews(
                    commandRunner = commandRunner,
                    executable = inspection.executable,
                )
            } else {
                inspection.sessions
            },
            availability = inspection.availability,
        )
    }

internal fun terminateTmuxSession(
    commandRunner: TmuxCommandRunner,
    sessionId: String,
): TmuxSessionCatalog {
    if (!sessionId.isTmuxSessionId()) return TmuxSessionCatalog(deleteFailed = true)
    val inspection = inspectTmuxSessions(commandRunner)
    if (
        inspection.availability != TmuxAvailability.AVAILABLE ||
        inspection.sessions.none { it.id == sessionId }
    ) {
        return TmuxSessionCatalog(
            sessions = inspection.sessions,
            availability = inspection.availability,
            deleteFailed = true,
        )
    }
    val deleted = deleteTmuxSession(
        commandRunner = commandRunner,
        executable = requireNotNull(inspection.executable),
        sessionId = sessionId,
    )
    val refreshed = inspectTmuxSessions(commandRunner)
    return TmuxSessionCatalog(
        sessions = refreshed.sessions,
        availability = refreshed.availability,
        deleteFailed = !deleted || refreshed.availability != TmuxAvailability.AVAILABLE,
    )
}

internal fun switchTmuxSession(
    commandRunner: TmuxCommandRunner,
    targetSessionId: String,
    sourceSessionId: String?,
): Boolean = switchOrAttachTmuxSession(commandRunner, targetSessionId, sourceSessionId) ==
    TmuxSessionSwitchResult.Switched

internal sealed interface TmuxSessionSwitchResult {
    data object Switched : TmuxSessionSwitchResult

    data class Attach(val command: String) : TmuxSessionSwitchResult

    data object Failed : TmuxSessionSwitchResult
}

/**
 * Switches an existing client when this terminal is attached to tmux. A plain shell has no tmux
 * client for the authenticated side channel to switch, so it receives a validated attach command
 * for dispatch through the interactive terminal instead.
 */
internal fun switchOrAttachTmuxSession(
    commandRunner: TmuxCommandRunner,
    targetSessionId: String,
    sourceSessionId: String?,
): TmuxSessionSwitchResult {
    if (
        !targetSessionId.isTmuxSessionId() ||
        (sourceSessionId != null && !sourceSessionId.isTmuxSessionId())
    ) {
        return TmuxSessionSwitchResult.Failed
    }
    val inspection = inspectTmuxSessions(commandRunner)
    if (
        inspection.availability != TmuxAvailability.AVAILABLE ||
        inspection.sessions.none { it.id == targetSessionId }
    ) {
        return TmuxSessionSwitchResult.Failed
    }
    val executable = requireNotNull(inspection.executable)
    if (sourceSessionId == null || inspection.sessions.none { it.id == sourceSessionId }) {
        return TmuxSessionSwitchResult.Attach(tmuxAttachCommand(executable, targetSessionId))
    }
    val clients = runCatching {
        commandRunner.run(
            "${quotePosixShellArgument(executable)} list-clients -F " +
                quotePosixShellArgument("#{client_tty}\t#{session_id}\t#{client_activity}"),
        ).takeIf { it.exitStatus == 0 }?.stdout
            ?.toString(Charsets.UTF_8)
            ?.lineSequence()
            ?.mapNotNull(::parseTmuxClientLine)
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())
    val client = clients
        .filter { it.sessionId == sourceSessionId }
        .maxByOrNull(TmuxClient::activityEpochSeconds)
        ?: return TmuxSessionSwitchResult.Attach(tmuxAttachCommand(executable, targetSessionId))
    return if (runCatching {
        commandRunner.run(
            "${quotePosixShellArgument(executable)} switch-client -c " +
                "${quotePosixShellArgument(client.tty)} -t " +
                quotePosixShellArgument(targetSessionId),
        ).exitStatus == 0
    }.getOrDefault(false)) {
        TmuxSessionSwitchResult.Switched
    } else {
        TmuxSessionSwitchResult.Failed
    }
}

private fun List<TmuxSession>.withTmuxPreviews(
    commandRunner: TmuxCommandRunner,
    executable: String?,
): List<TmuxSession> {
    if (executable?.isTmuxExecutablePath() != true) return this
    return mapIndexed { index, session ->
        if (index >= MAX_TMUX_PREVIEW_SESSIONS) {
            session
        } else {
            session.copy(
                previewLines = captureTmuxPreview(commandRunner, executable, session.id),
            )
        }
    }
}

private fun captureTmuxPreview(
    commandRunner: TmuxCommandRunner,
    executable: String,
    sessionId: String,
): List<String> = runCatching {
    commandRunner.run(
        "${quotePosixShellArgument(executable)} capture-pane -p -J -t " +
            "${quotePosixShellArgument(sessionId)} -S -$MAX_TMUX_PREVIEW_LINES",
    ).takeIf { it.exitStatus == 0 }
        ?.stdout
        ?.toString(Charsets.UTF_8)
        ?.lineSequence()
        ?.map { line ->
            line.filter { character -> character == '\t' || !character.isISOControl() }
                .take(MAX_TMUX_PREVIEW_COLUMNS)
                .trimEnd()
        }
        ?.toList()
        ?.takeLast(MAX_TMUX_PREVIEW_LINES)
        ?.dropWhile(String::isBlank)
        ?.dropLastWhile(String::isBlank)
        .orEmpty()
}.getOrDefault(emptyList())

/**
 * Captures only physical history rows for one stable pane target. Metadata and history use separate
 * execs so a session target is resolved to a pane ID once; animation never invokes either runner.
 */
internal fun captureTmuxPane(
    metadataRunner: TmuxCommandRunner,
    historyRunner: TmuxCommandRunner,
    executable: String,
    sessionId: String,
    authoritative: Boolean = false,
    includeHistory: Boolean = true,
): TmuxPaneCapture? {
    if (!executable.isTmuxExecutablePath() || !sessionId.isTmuxSessionId()) return null
    val metadata = runCatching {
        metadataRunner.run(
            "${quotePosixShellArgument(executable)} display-message -p -t " +
                "${quotePosixShellArgument(sessionId)} " +
                quotePosixShellArgument(TMUX_PANE_CAPTURE_FORMAT),
        ).takeIf { it.exitStatus == 0 }
            ?.stdout
            ?.toString(Charsets.UTF_8)
            ?.lineSequence()
            ?.filter(String::isNotEmpty)
            ?.singleOrNull()
            ?.let(::parseTmuxPaneCaptureMetadata)
    }.getOrNull() ?: return null
    if (metadata.sessionId != sessionId) return null

    val savedPrimaryRows = if (metadata.alternateScreenActive) metadata.rows else 0
    val capturedHistoryRows = minOf(
        metadata.historyRows,
        ModelLimits.MAX_SCROLLBACK_LINES - savedPrimaryRows,
    )
    val capturedRows = capturedHistoryRows + savedPrimaryRows
    val content = if (!includeHistory || capturedRows == 0) {
        byteArrayOf()
    } else {
        val physicalHistory = if (capturedHistoryRows == 0) {
            byteArrayOf()
        } else {
            runCatching {
                historyRunner.run(
                    "${quotePosixShellArgument(executable)} capture-pane -p -e -J -t " +
                        "${quotePosixShellArgument(metadata.paneId)} -S " +
                        "${quotePosixShellArgument("-$capturedHistoryRows")} -E -1",
                ).takeIf { it.exitStatus == 0 }?.stdout
            }.getOrNull() ?: return null
        }
        val savedPrimaryScreen = if (savedPrimaryRows == 0) {
            byteArrayOf()
        } else {
            runCatching {
                historyRunner.run(
                    "${quotePosixShellArgument(executable)} capture-pane -p -e -J -a -t " +
                        quotePosixShellArgument(metadata.paneId),
                ).takeIf { it.exitStatus == 0 }?.stdout
            }.getOrNull() ?: return null
        }
        if (physicalHistory.size > TmuxPaneCapture.MAX_CAPTURE_BYTES - savedPrimaryScreen.size) {
            return null
        }
        physicalHistory + savedPrimaryScreen
    }
    if (content.size > TmuxPaneCapture.MAX_CAPTURE_BYTES) return null

    return TmuxPaneCapture(
        sessionId = metadata.sessionId,
        paneId = metadata.paneId,
        columns = metadata.columns,
        rows = metadata.rows,
        historyRows = capturedRows,
        alternateScreenActive = metadata.alternateScreenActive,
        mouseTrackingActive = metadata.mouseTrackingActive,
        paneInMode = metadata.paneInMode,
        historyIncluded = includeHistory,
        truncatedBefore = metadata.historyRows > capturedHistoryRows,
        authoritative = authoritative,
        content = content,
    )
}

/**
 * Resolves the app-created session only after one unique new ID exists.
 *
 * Capture can race the interactive `new-session` startup command. Existing attached sessions are
 * not evidence of the app's target and must never be guessed or latched while creation is pending.
 */
internal fun resolveNewTmuxSessionId(
    commandRunner: TmuxCommandRunner,
    existingSessionIds: Set<String>,
): String? {
    val sessions = queryTmuxSessions(commandRunner) ?: return null
    val newlyCreated = sessions.filterNot { it.id in existingSessionIds }
    return newlyCreated.singleOrNull()?.id
}

private data class TmuxPaneCaptureMetadata(
    val sessionId: String,
    val paneId: String,
    val columns: Int,
    val rows: Int,
    val historyRows: Int,
    val alternateScreenActive: Boolean,
    val mouseTrackingActive: Boolean,
    val paneInMode: Boolean,
)

private fun parseTmuxPaneCaptureMetadata(line: String): TmuxPaneCaptureMetadata? {
    val fields = line.split('\t', limit = 8)
    if (fields.size != 8) return null
    val sessionId = fields[0].takeIf(String::isTmuxSessionId) ?: return null
    val paneId = fields[1].takeIf { TMUX_PANE_ID.matches(it) } ?: return null
    val columns = fields[2].toIntOrNull()?.takeIf { it in 1..MAX_TMUX_PANE_COLUMNS } ?: return null
    val rows = fields[3].toIntOrNull()?.takeIf { it in 1..MAX_TMUX_PANE_ROWS } ?: return null
    val historyRows = fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val alternateScreenActive = fields[5].parseTmuxBoolean() ?: return null
    val mouseTrackingActive = fields[6].parseTmuxBoolean() ?: return null
    val paneInMode = fields[7].parseTmuxBoolean() ?: return null
    return TmuxPaneCaptureMetadata(
        sessionId = sessionId,
        paneId = paneId,
        columns = columns,
        rows = rows,
        historyRows = historyRows,
        alternateScreenActive = alternateScreenActive,
        mouseTrackingActive = mouseTrackingActive,
        paneInMode = paneInMode,
    )
}

private fun String.parseTmuxBoolean(): Boolean? = when (this) {
    "0" -> false
    "1" -> true
    else -> null
}

private data class TmuxClient(
    val tty: String,
    val sessionId: String,
    val activityEpochSeconds: Long,
)

private fun parseTmuxClientLine(line: String): TmuxClient? {
    val fields = line.split('\t', limit = 3)
    if (fields.size != 3) return null
    val tty = fields[0]
    if (tty.length !in 2..MAX_TMUX_CLIENT_TTY_CHARS || !tty.startsWith('/') || tty.any(Char::isISOControl)) {
        return null
    }
    val sessionId = fields[1].takeIf(String::isTmuxSessionId) ?: return null
    val activity = fields[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return TmuxClient(tty, sessionId, activity)
}

internal fun parseTmuxSessionLine(line: String): TmuxSession? {
    val fields = line.split('\t', limit = 5)
    if (fields.size != 5 || !fields[0].isTmuxSessionId()) return null
    val name = fields[1].replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim().take(MAX_TMUX_NAME_CHARS)
    if (name.isEmpty()) return null
    val windows = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val attached = fields[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val created = fields[4].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return TmuxSession(fields[0], name, windows, attached, created)
}

internal fun tmuxAttachCommand(sessionId: String): String {
    require(sessionId.isTmuxSessionId())
    return "tmux attach-session -t '$sessionId'"
}

private fun tmuxAttachCommand(executable: String, sessionId: String): String {
    require(executable.isTmuxExecutablePath())
    require(sessionId.isTmuxSessionId())
    return "${quotePosixShellArgument(executable)} attach-session -t " +
        quotePosixShellArgument(sessionId)
}

internal fun tmuxStartupCommand(choice: TmuxStartupChoice): String = when (choice) {
    is TmuxStartupChoice.NewSession ->
        "${quotePosixShellArgument(choice.executable)} new-session"
    is TmuxStartupChoice.Attach ->
        "${quotePosixShellArgument(choice.executable)} attach-session -t " +
            quotePosixShellArgument(choice.sessionId)
}

/** An explicit tmux choice owns startup; saved shell input applies only to the ordinary shell. */
internal fun resolveSshStartupCommand(userCommand: String?, tmuxCommand: String?): String? =
    tmuxCommand ?: userCommand?.takeIf(String::isNotBlank)

private fun deleteTmuxSession(
    commandRunner: TmuxCommandRunner,
    executable: String,
    sessionId: String,
): Boolean {
    if (!executable.isTmuxExecutablePath() || !sessionId.isTmuxSessionId()) return false
    return runCatching {
        commandRunner.run(
            "${quotePosixShellArgument(executable)} kill-session -t " +
                quotePosixShellArgument(sessionId),
        ).exitStatus == 0
    }.getOrDefault(false)
}

private fun runBoundedTmuxExec(
    session: Session,
    command: String,
    limits: TmuxExecLimits,
): TmuxExecOutput {
    val channel = session.openChannel("exec") as ChannelExec
    channel.setPty(false)
    channel.setInputStream(null)
    channel.setCommand(command)
    val stdout = channel.inputStream
    val stderr = channel.extInputStream
    val output = ByteArrayOutputStream()
    val scratch = ByteArray(TMUX_READ_BUFFER_BYTES)
    val deadline = System.nanoTime() + limits.totalTimeoutMillis * 1_000_000L
    try {
        channel.connect(limits.channelConnectTimeoutMillis)
        while (true) {
            drainTmuxInput(
                stdout,
                output,
                scratch,
                retain = true,
                maximumOutputBytes = limits.maximumOutputBytes,
            )
            drainTmuxInput(
                stderr,
                output,
                scratch,
                retain = false,
                maximumOutputBytes = limits.maximumOutputBytes,
            )
            check(output.size() <= limits.maximumOutputBytes) { "tmux output exceeded its limit." }
            if (channel.isClosed) {
                drainTmuxInput(
                    stdout,
                    output,
                    scratch,
                    retain = true,
                    maximumOutputBytes = limits.maximumOutputBytes,
                )
                check(output.size() <= limits.maximumOutputBytes) { "tmux output exceeded its limit." }
                return TmuxExecOutput(output.toByteArray(), channel.exitStatus)
            }
            check(System.nanoTime() < deadline) { "tmux command timed out." }
            Thread.sleep(TMUX_POLL_MILLIS)
        }
    } finally {
        scratch.fill(0)
        runCatching { stdout.close() }
        runCatching { stderr.close() }
        channel.disconnect()
    }
}

private fun drainTmuxInput(
    input: InputStream,
    output: ByteArrayOutputStream,
    scratch: ByteArray,
    retain: Boolean,
    maximumOutputBytes: Int,
) {
    while (input.available() > 0) {
        val count = input.read(scratch, 0, minOf(input.available(), scratch.size))
        if (count <= 0) return
        if (retain) {
            check(output.size() + count <= maximumOutputBytes) {
                "tmux output exceeded its limit."
            }
            output.write(scratch, 0, count)
        }
    }
}

internal fun String.isTmuxSessionId(): Boolean = TMUX_SESSION_ID.matches(this)

private fun String.isTmuxExecutablePath(): Boolean =
    length in 2..MAX_TMUX_EXECUTABLE_PATH_CHARS &&
        startsWith('/') &&
        none(Char::isISOControl)

private const val TMUX_AVAILABLE_MARKER = "__TERMINAL_SPIKE_TMUX__"
private const val MAX_TMUX_CLIENT_TTY_CHARS = 512
private const val MAX_TMUX_PREVIEW_SESSIONS = 24
private const val MAX_TMUX_PREVIEW_LINES = 10
private const val MAX_TMUX_PREVIEW_COLUMNS = 120
private const val MAX_TMUX_PANE_COLUMNS = 500
private const val MAX_TMUX_PANE_ROWS = 16_384
private const val TMUX_PANE_CAPTURE_FORMAT =
    "#{session_id}\t#{pane_id}\t#{pane_width}\t#{pane_height}\t" +
        "#{history_size}\t#{alternate_on}\t#{mouse_any_flag}\t#{pane_in_mode}"
internal const val TMUX_NEW_SESSION_SELECTION = "__terminal_spike_new_tmux_session__"
private const val TMUX_PROBE_SCRIPT =
    "TMUX_BIN=\$(command -v tmux 2>/dev/null || true); " +
        "if [ ! -x \"\$TMUX_BIN\" ]; then for candidate in " +
        "\"\$HOME/.local/bin/tmux\" \"\$HOME/bin/tmux\" \"\$HOME/.nix-profile/bin/tmux\" " +
        "/usr/local/bin/tmux /usr/bin/tmux /bin/tmux /opt/homebrew/bin/tmux " +
        "/home/linuxbrew/.linuxbrew/bin/tmux /run/current-system/sw/bin/tmux; do " +
        "if [ -x \"\$candidate\" ]; then TMUX_BIN=\$candidate; break; fi; done; fi; " +
        "ACCOUNT_SHELL=\$SHELL; if [ ! -x \"\$ACCOUNT_SHELL\" ] && " +
        "command -v getent >/dev/null 2>&1; then ACCOUNT_SHELL=\$(getent passwd \"\$(id -un)\" | " +
        "cut -d: -f7); fi; " +
        "if [ ! -x \"\$TMUX_BIN\" ] && [ -x \"\$ACCOUNT_SHELL\" ]; then " +
        "TMUX_BIN=\$(\"\$ACCOUNT_SHELL\" -ic \"env\" 2>/dev/null | " +
        "while IFS= read -r environment_line; do case \"\$environment_line\" in PATH=*) " +
        "interactive_path=\${environment_line#PATH=}; " +
        "resolved=\$(PATH=\"\$interactive_path\" command -v tmux 2>/dev/null || true); " +
        "[ -x \"\$resolved\" ] && printf \"%s\\n\" \"\$resolved\";; esac; done | tail -n 1); fi; " +
        "if [ ! -x \"\$TMUX_BIN\" ] && [ -x \"\$ACCOUNT_SHELL\" ]; then " +
        "TMUX_BIN=\$(\"\$ACCOUNT_SHELL\" -lic \"command -v tmux\" 2>/dev/null | " +
        "while IFS= read -r candidate; do [ -x \"\$candidate\" ] && " +
        "printf \"%s\\n\" \"\$candidate\"; done | tail -n 1); fi; " +
        "if [ -n \"\$TMUX_BIN\" ] && [ -x \"\$TMUX_BIN\" ]; then " +
        "printf \"$TMUX_AVAILABLE_MARKER\\n%s\\n\" \"\$TMUX_BIN\"; " +
        "\"\$TMUX_BIN\" list-sessions -F \"#{session_id}\t#{session_name}\t#{session_windows}\t" +
        "#{session_attached}\t#{session_created}\" 2>/dev/null || true; fi"
internal val TMUX_LIST_COMMAND = "/bin/sh -c ${quotePosixShellArgument(TMUX_PROBE_SCRIPT)}"
internal const val MAX_TMUX_SESSIONS = 128
private const val MAX_TMUX_NAME_CHARS = 256
private const val MAX_TMUX_EXECUTABLE_PATH_CHARS = 1_024
private const val TMUX_READ_BUFFER_BYTES = 2 * 1024
private const val TMUX_POLL_MILLIS = 10L
private val TMUX_SESSION_ID = Regex("\\$[0-9]+")
private val TMUX_PANE_ID = Regex("%[0-9]+")
private val DEFAULT_TMUX_EXEC_LIMITS = TmuxExecLimits(
    channelConnectTimeoutMillis = 5_000,
    totalTimeoutMillis = 5_000L,
    maximumOutputBytes = 64 * 1024,
)
internal val TMUX_HISTORY_EXEC_LIMITS = TmuxExecLimits(
    channelConnectTimeoutMillis = 10_000,
    totalTimeoutMillis = 30_000L,
    maximumOutputBytes = TmuxPaneCapture.MAX_CAPTURE_BYTES,
)
