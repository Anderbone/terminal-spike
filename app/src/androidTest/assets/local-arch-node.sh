set -eu
export MAKEFLAGS=-j2
export npm_config_jobs=2
mkdir -p /root/projects/arch-development-gate
cd /root/projects/arch-development-gate
if [ ! -d is-number/.git ]; then
    git clone --depth 1 --single-branch --branch 7.0.0 https://github.com/jonschlinkert/is-number.git
fi
git -C is-number rev-parse HEAD
test "$(git -C is-number rev-parse HEAD)" = 98e8ff1da1a89f93d1397a24d7413ed15421c139
node -e 'if (!require("./is-number")(42)) process.exit(1); console.log("ARCH_GIT_CLONE_OK")'
npm install --global typescript@7.0.2
tsc --version
cat > package.json <<'PACKAGE'
{
  "name": "local-arch-development-gate",
  "version": "1.0.0",
  "private": true,
  "scripts": { "dev": "node server.cjs" },
  "dependencies": {
    "typescript": "7.0.2",
    "esbuild": "0.28.2",
    "sharp": "0.35.4",
    "better-sqlite3": "13.0.3"
  }
}
PACKAGE
npm install --no-audit --no-fund
cat > sample.ts <<'TYPESCRIPT'
const value: number = 42;
console.log(`ARCH_TYPESCRIPT_${value}`);
TYPESCRIPT
npx --no-install tsc sample.ts --target es2022 --module commonjs --outDir compiled
node compiled/sample.js
cat > native.cjs <<'JAVASCRIPT'
const assert = require('node:assert/strict');
const esbuild = require('esbuild');
const sharp = require('sharp');
const Database = require('better-sqlite3');
(async () => {
  await esbuild.build({entryPoints: ['sample.ts'], bundle: true, outfile: 'bundle.cjs', platform: 'node'});
  console.log('ARCH_ESBUILD_OK', esbuild.version);
  await sharp({ create: { width: 32, height: 24, channels: 3, background: '#187ac8' } }).resize(16, 12).png().toFile('native.png');
  const image = await sharp('native.png').metadata();
  assert.equal(image.width, 16); assert.equal(image.height, 12);
  console.log('ARCH_SHARP_OK', sharp.versions);
  const db = new Database('persistent.sqlite');
  db.exec('CREATE TABLE IF NOT EXISTS projects (id INTEGER PRIMARY KEY, name TEXT NOT NULL)');
  db.prepare('INSERT OR REPLACE INTO projects VALUES (?, ?)').run(1, 'persistent-native-project');
  assert.equal(db.prepare('SELECT name FROM projects WHERE id = 1').get().name, 'persistent-native-project');
  db.close();
  console.log('ARCH_SQLITE_NATIVE_OK', require('better-sqlite3/package.json').version);
})().catch(error => { console.error(error); process.exitCode = 1; });
JAVASCRIPT
node native.cjs
node bundle.cjs
cat > server.cjs <<'JAVASCRIPT'
require('node:http').createServer((request, response) => response.end('ARCH_DEV_HTTP_OK')).listen(18763, '127.0.0.1');
JAVASCRIPT
npm run dev > dev-server.log 2>&1 &
dev_pid=$!
trap 'kill "$dev_pid" 2>/dev/null || true' EXIT
node -e 'const http=require("node:http"); let attempts=0; function probe(){http.get("http://127.0.0.1:18763",r=>{let body="";r.on("data",b=>body+=b);r.on("end",()=>{if(body!=="ARCH_DEV_HTTP_OK")process.exit(1);console.log(body)})}).on("error",()=>{if(++attempts>60)process.exit(1);setTimeout(probe,500)})} probe()'
kill "$dev_pid"
wait "$dev_pid" || true
trap - EXIT
npm ls --depth=0
printf '\nARCH_NODE_NATIVE_OK\n'
