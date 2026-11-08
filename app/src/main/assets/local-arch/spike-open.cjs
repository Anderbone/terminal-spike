#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const net = require('node:net');

function parse(args) {
  const choose = args[0] === '--choose';
  if (choose) args = args.slice(1);
  if (args.length !== 1) throw new Error('Usage: spike-open [--choose] URL');
  const value = args[0];
  const url = new URL(value);
  if (value.length > 16384 || /[\s\x00-\x1f\x7f]/u.test(value) ||
      !['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) {
    throw new Error('Expected one HTTP or HTTPS URL.');
  }
  return { api: 1, method: 'browser.open', params: { url: value, choose } };
}

function main(args) {
  try {
    const request = parse(args);
    const config = JSON.parse(fs.readFileSync('/etc/terminal-spike/browser.json', 'utf8'));
    if (config.api !== 1 || typeof config.abstractSocket !== 'string' ||
        !/^terminal-spike-[0-9]+-[a-f0-9-]+$/.test(config.abstractSocket)) throw new Error('Invalid endpoint.');
    const socket = net.createConnection({ path: '\0' + config.abstractSocket });
    let pending = Buffer.alloc(0);
    let complete = false;
    const finish = success => {
      if (complete) return;
      complete = true;
      if (!success) process.stderr.write('Could not open browser. Keep Terminal Spike unlocked and visible; open a new Local Arch tab after restarting the app.\n');
      process.exitCode = success ? 0 : 1;
      socket.destroy();
    };
    socket.setTimeout(12000, () => finish(false));
    socket.on('error', () => finish(false));
    socket.on('connect', () => socket.write(JSON.stringify(request) + '\n'));
    socket.on('data', chunk => {
      if (complete) return;
      if (pending.length + chunk.length > 4096) { finish(false); return; }
      pending = Buffer.concat([pending, chunk]);
      const newline = pending.indexOf(10);
      if (newline < 0) return;
      try {
        const response = JSON.parse(pending.subarray(0, newline).toString('utf8'));
        finish(response.api === 1 && response.ok === true);
      } catch (_) { finish(false); }
    });
    socket.on('end', () => finish(false));
  } catch (_) {
    // Never echo an authorization URL, state, code or tokens to logs/output.
    process.stderr.write('Usage: spike-open [--choose] HTTP_OR_HTTPS_URL. Open a new Local Arch tab if the bridge is unavailable.\n');
    process.exitCode = 2;
  }
}

if (require.main === module) main(process.argv.slice(2));
module.exports = { parse };
