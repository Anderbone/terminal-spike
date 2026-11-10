#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const net = require('node:net');

const help = `spikectl — manage Terminal Spike from this phone's Local Arch Linux

Enable agent access in the app: Settings > Local Arch Linux.
Authorization lasts until the app process exits. App lock blocks settings access.
All responses are JSON. A failed request exits nonzero. No command executes snippets.

  spikectl status
  spikectl capabilities
  Button colours: appearance update --json '{"revision":NUMBER,"buttonColors":{"ai_agents":"#6750A4"}}'
  Uses keyboard action codes; preserves active modifier indicators. Map replaces all overrides.
  spikectl appearance get
  spikectl appearance update --revision NUMBER
      [--theme-mode system|light|dark] [--dynamic-color-enabled true|false]
      [--accent-preset mint|blue|violet|amber|coral]
  spikectl terminal list
  spikectl terminal get --id UUID
  spikectl terminal update --id UUID --revision NUMBER
      [--theme-id ID] [--font-id ID] [--font-size-sp NUMBER]
      [--line-height-multiplier NUMBER] [--letter-spacing-em NUMBER]
      [--bold-rendering-enabled true|false] [--ligatures-enabled true|false]
      [--pinch-zoom-enabled true|false] [--cursor-style block|underline|beam]
      [--cursor-blink-enabled true|false]
  spikectl themes list
  spikectl themes get --id ID
  spikectl themes add --name NAME [--base ID] [palette fields below]
  spikectl themes update --id UUID --revision NUMBER [--name NAME]
      [--foreground '#RRGGBB'] [--background '#RRGGBB']
      [--cursor '#RRGGBB'] [--selection '#RRGGBB']
      [--ansi16 '#RRGGBB,...'] [--bold-uses-bright-colours true|false]
  spikectl fonts list

Built-in palettes are read-only. themes add clones base (default: current),
preserving unspecified colours. Select the returned ID with terminal update.
Fonts must already be bundled or imported through the app; arbitrary paths and
font downloads are not supported. App theme mode and terminal palette are separate.

  spikectl snippets list
  spikectl snippets get --id UUID
  spikectl snippets add --name NAME --command TEXT [--group TEXT]
                       [--append-enter true|false] [--tap-action insert|send_immediately]
                       [--is-favorite true|false]
  spikectl snippets update --id UUID --revision NUMBER [same fields as add]
  spikectl snippets delete --id UUID --revision NUMBER
  spikectl keyboard list
  spikectl keyboard get --id UUID
  spikectl keyboard update --id UUID --revision NUMBER
      [--actions escape,tab,control,arrow_up,arrow_down,enter]
      [--layout CODE] [--modifier-behavior CODE] [--input-mode raw|text]
      [--haptic-feedback-enabled true|false] [--key-repeat-enabled true|false]
      [--tmux-prefix C-b]
  spikectl credentials list
  spikectl connections list
  spikectl connections get --id UUID
  spikectl connections add --name NAME --hostname HOST --username USER
      [--port NUMBER] [--protocol ssh|mosh] [--credential-id UUID]
      [--terminal-profile-id UUID] [--keyboard-profile-id UUID]
      [--is-favorite true|false] [--group TEXT] [--tag TEXT]
  spikectl connections update --id UUID --revision NUMBER [same fields as add]

New connections default to SSH port 22 and prompt for a password when connecting.
Use credentials list to select existing authentication by ID (no secrets exposed).
An empty credential-id selects a new password prompt; empty profile IDs inherit
the defaults. Empty group/tag clears them. Connection edits apply on next connect.

  spikectl forwards list --connection UUID
  spikectl forwards add --connection UUID --revision NUMBER
      --listen-port NUMBER --destination-port NUMBER
      [--direction local|remote] [--bind-address 127.0.0.1|::1]
      [--destination-host HOST] [--enabled true|false]
  spikectl forwards remove --connection UUID --revision NUMBER --index NUMBER

Updates/deletes require the revision returned by the latest read. CONFLICT means
read again and reconsider the edit. Forward indexes start at 1; rules take effect
on the next connection, and this tool never reconnects a running terminal.
Use capabilities for valid keyboard enum codes. actions replaces the whole deck;
preserve existing keys unless asked to remove them. For complex/multiline values,
replace flags with --json '{...}' or --json-file PATH (camelCase property names).
--json-file - reads JSON from stdin. Never edit the app database/settings files.
`;

function parse(args) {
  if (!args.length || args.includes('--help') || args[0] === 'help') return null;
  let method = args.shift();
  if (['appearance', 'terminal', 'themes', 'fonts', 'snippets', 'keyboard', 'connections', 'credentials', 'forwards'].includes(method)) {
    if (!args.length || args[0].startsWith('--')) throw new Error('Missing subcommand.');
    method += '.' + args.shift();
  }
  let params = {};
  const integers = new Set(['revision', 'port', 'listenPort', 'destinationPort', 'index']);
  const numbers = new Set(['fontSizeSp', 'lineHeightMultiplier', 'letterSpacingEm']);
  const booleans = new Set(['appendEnter', 'isFavorite', 'hapticFeedbackEnabled', 'keyRepeatEnabled', 'enabled',
    'inputButtonsOnRight', 'voiceInputEnabled', 'dynamicColorEnabled', 'boldRenderingEnabled', 'ligaturesEnabled', 'pinchZoomEnabled', 'cursorBlinkEnabled', 'boldUsesBrightColours']);
  const seen = new Set();
  while (args.length) {
    const flag = args.shift();
    if (!/^--[a-z][a-z0-9-]*$/.test(flag) || !args.length) throw new Error('Expected --field VALUE.');
    if (seen.has(flag)) throw new Error('Duplicate flag.');
    seen.add(flag);
    const value = args.shift();
    if (flag === '--json' || flag === '--json-file') {
      if (seen.size !== 1 || args.length) throw new Error('JSON input cannot be mixed with field flags.');
      params = JSON.parse(flag === '--json' ? value : fs.readFileSync(value === '-' ? 0 : value, 'utf8'));
      if (!params || Array.isArray(params) || typeof params !== 'object') throw new Error('Expected a JSON object.');
      break;
    }
    const key = flag.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    if (integers.has(key)) {
      if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value))) throw new Error('Expected a safe nonnegative integer.');
      params[key] = Number(value);
    } else if (numbers.has(key)) {
      if (!/^-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value) || !Number.isFinite(Number(value))) {
        throw new Error('Expected a finite number.');
      }
      params[key] = Number(value);
    } else if (booleans.has(key)) {
      if (!['true', 'false'].includes(value)) throw new Error('Expected true or false.');
      params[key] = value === 'true';
    } else if (key === 'actions' || key === 'ansi16') params[key] = value.split(',');
    else params[key] = value;
  }
  return { api: 1, method, params };
}

function main(args) {
  let request;
  try {
    request = parse(args);
    if (!request) { process.stdout.write(help); return; }
    const bytes = Buffer.from(JSON.stringify(request) + '\n');
    if (bytes.length - 1 > 64 * 1024) throw new Error('Request exceeds 64 KiB.');
    const config = JSON.parse(fs.readFileSync('/etc/terminal-spike/control.json', 'utf8'));
    if (config.api !== 1 || typeof config.abstractSocket !== 'string' || !/^terminal-spike-[0-9]+-[a-f0-9-]+$/.test(config.abstractSocket)) {
      throw new Error('Invalid local endpoint. Open a new Local Arch tab to refresh the tools.');
    }
    const socket = net.createConnection({ path: '\0' + config.abstractSocket });
    let pending = Buffer.alloc(0);
    let complete = false;
    const fail = () => {
      if (complete) return;
      complete = true;
      process.stderr.write('Cannot reach the app. Open a new Local Arch tab and check agent access in Settings.\n');
      process.exitCode = 1;
      socket.destroy();
    };
    socket.setTimeout(18000, fail);
    socket.on('error', fail);
    socket.on('connect', () => socket.write(bytes));
    socket.on('data', chunk => {
      if (complete) return;
      if (pending.length + chunk.length > 1024 * 1024 + 1) { fail(); return; }
      pending = Buffer.concat([pending, chunk]);
      const newline = pending.indexOf(10);
      if (newline < 0) return;
      try {
        const response = JSON.parse(pending.subarray(0, newline).toString('utf8'));
        if (response.api !== 1 || typeof response.ok !== 'boolean') throw new Error('Invalid response');
        complete = true;
        process.stdout.write(JSON.stringify(response, null, 2) + '\n');
        process.exitCode = response.ok ? 0 : 1;
        socket.destroy();
      } catch (_) { fail(); }
    });
    socket.on('end', () => { if (!complete) fail(); });
  } catch (error) {
    process.stderr.write('spikectl: ' + error.message + '\nUse spikectl --help.\n');
    process.exitCode = 2;
  }
}

if (require.main === module) main(process.argv.slice(2));
module.exports = { parse };
