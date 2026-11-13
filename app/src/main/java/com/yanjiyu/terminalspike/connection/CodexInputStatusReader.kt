package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.CodexInputStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** No inference or account requests: bounded, read-only access to this exact thread's usage events. */
internal object CodexInputStatusReader {
    // Codex 0.166 protocol TokenUsage::percent_of_context_window_remaining excludes 12,000
    // baseline tokens. Use last_token_usage, never cumulative total_token_usage.
    val script = """
import json, math, os, re, sqlite3, sys, time
from pathlib import Path
from contextlib import closing
result = {'context': None, 'weekly': None}
def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
def read(thread):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', thread): return
    home = Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex'))).resolve()
    path = None
    for database in sorted(home.glob('state_*.sqlite'), reverse=True)[:3]:
        try:
            with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=0.1)) as db:
                row = db.execute('SELECT rollout_path FROM threads WHERE id = ?', (thread,)).fetchone()
            if row:
                path = Path(row[0]).resolve()
                break
        except (sqlite3.Error, OSError, ValueError): pass
    if path is None or not path.is_relative_to(home / 'sessions'): return
    with path.open('rb') as stream:
        offset = max(0, path.stat().st_size - 262144)
        stream.seek(offset)
        lines = stream.read(262144).splitlines()
    if offset: lines = lines[1:]
    context_seen = False
    limits_seen = False
    for line in reversed(lines):
        try: event = json.loads(line)
        except (ValueError, UnicodeDecodeError): continue
        if not isinstance(event, dict) or event.get('type') != 'event_msg': continue
        payload = event.get('payload')
        if not isinstance(payload, dict): continue
        if payload.get('type') == 'context_compacted': context_seen = True
        if payload.get('type') != 'token_count': continue
        info = payload.get('info')
        if not context_seen and isinstance(info, dict):
            context_seen = True
            window = info.get('model_context_window')
            usage = info.get('last_token_usage')
            used = usage.get('total_tokens') if isinstance(usage, dict) else None
            if number(window) and number(used) and window > 0 and used >= 0:
                remaining = 0 if window <= 12000 else 100 * max(0, window - 12000 - max(0, used - 12000)) / (window - 12000)
                result['context'] = min(100, math.floor(remaining + 0.5))
        limits = payload.get('rate_limits')
        if not limits_seen and isinstance(limits, dict) and limits.get('limit_id') in (None, 'codex'):
            limits_seen = True
            for name in ('primary', 'secondary'):
                value = limits.get(name)
                if not isinstance(value, dict): continue
                minutes, used, reset = value.get('window_minutes'), value.get('used_percent'), value.get('resets_at')
                key = 'weekly' if minutes == 10080 else None
                if key and number(used) and 0 <= used <= 100 and number(reset) and reset > time.time():
                    result[key] = math.floor(100 - used + 0.5)
        if context_seen and limits_seen: break
try: read(sys.argv[1])
except (OSError, ValueError, TypeError, OverflowError): pass
print(json.dumps(result))
    """.trimIndent()

    fun capture(runner: TmuxCommandRunner, thread: String): CodexInputStatus? = runCatching {
        require(thread.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val command = listOf("python3", "-c", script, thread).joinToString(" ", transform = ::quotePosixShellArgument)
        val output = runner.run(command)
        require(output.exitStatus == 0 && output.stdout.size <= 4096)
        val value = Json.parseToJsonElement(output.stdout.toString(Charsets.UTF_8)).jsonObject
        fun percent(key: String): Int? = value[key]?.jsonPrimitive?.intOrNull?.also { require(it in 0..100) }
        CodexInputStatus(percent("context"), percent("weekly"))
    }.getOrNull()
}
