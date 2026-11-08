"""The local browser helper sends URLs as data and never echoes OAuth material."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'app/src/main/assets/local-arch/spike-open.cjs'


class SpikeOpenTest(unittest.TestCase):
    def test_protocol_and_private_error_paths(self):
        program = r'''
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {EventEmitter} = require('node:events');
const file = process.argv[1];
const {parse} = require(file);
const url = 'https://example.com/authorize?state=one-use&literal=$(never-execute)';
assert.deepEqual(parse([url]), {api:1, method:'browser.open', params:{url, choose:false}});
assert.equal(parse(['--choose', 'http://127.0.0.1:1455/auth/callback']).params.choose, true);
for (const args of [[], [url, url], ['file:///secret'], ['javascript:alert(1)'],
  ['https://user:secret@example.com'], ['https://example.com/\nsecret']]) {
  assert.throws(() => parse(args));
}
for (const success of [true, false]) {
  let stderr = '', stdout = '', sent = '', destroyed = false;
  const socket = new EventEmitter();
  socket.setTimeout = () => {};
  socket.destroy = () => {destroyed = true;};
  socket.write = frame => {
    sent = frame;
    const response = JSON.stringify({api:1, ok:success}) + '\n';
    socket.emit('data', Buffer.from(response.slice(0, 9)));
    socket.emit('data', Buffer.from(response.slice(9)));
  };
  const fakeRequire = name => {
    if (name === 'node:fs') return {readFileSync: () => JSON.stringify({api:1, abstractSocket:'terminal-spike-123-abc-456'})};
    if (name === 'node:net') return {createConnection: config => {
      assert.equal(config.path, '\0terminal-spike-123-abc-456');
      return socket;
    }};
    throw new Error('Unexpected import');
  };
  const module = {};
  fakeRequire.main = module;
  const process = {argv:['node', file, url], stdout:{write:s => stdout += s}, stderr:{write:s => stderr += s}};
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {require:fakeRequire, module, process, Buffer, URL});
  socket.emit('connect');
  assert.equal(JSON.parse(sent).params.url, url);
  assert.equal(process.exitCode, success ? 0 : 1);
  assert.equal(destroyed, true);
  assert.equal(stdout, '');
  assert.equal(stderr.includes('one-use'), false);
  assert.equal(stderr.includes(url), false);
}
'''
        result = subprocess.run(['node', '-e', program, str(HELPER)], text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
