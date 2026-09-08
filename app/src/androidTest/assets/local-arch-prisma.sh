set -eu
export MAKEFLAGS=-j2
export CHECKPOINT_DISABLE=1
export npm_config_progress=false
export NO_COLOR=1
export PRISMA_HIDE_UPDATE_MESSAGE=1
node --version
node -e 'const D=require("/root/projects/arch-development-gate/node_modules/better-sqlite3"); const db=new D("/root/projects/arch-development-gate/persistent.sqlite",{readonly:true}); if(db.prepare("SELECT name FROM projects WHERE id=1").get().name!=="persistent-native-project")process.exit(1);db.close();console.log("ARCH_DEV_RESTART_OK")'
mkdir -p /root/projects/arch-prisma-gate
cd /root/projects/arch-prisma-gate
cat > package.json <<'PACKAGE'
{"name":"local-arch-prisma-gate","version":"1.0.0","private":true,"dependencies":{"prisma":"7.10.0","@prisma/client":"7.10.0","@prisma/adapter-better-sqlite3":"7.10.0"}}
PACKAGE
npm install --no-audit --no-fund
# Reviewed native install entry: prebuild-install || node-gyp rebuild --release.
# npm 12 blocks it until this exact installed version has an explicit approval.
node -e 'if(require("better-sqlite3/package.json").version!=="12.11.1")throw Error("Review the changed SQLite install script first")'
npm install-scripts approve better-sqlite3
npm rebuild better-sqlite3 --foreground-scripts
node --version
openssl version
npx --no-install prisma --version
cat > prisma.config.ts <<'CONFIG'
import { defineConfig } from "prisma/config";
export default defineConfig({schema:"schema.prisma",migrations:{path:"migrations"},datasource:{url:"file:./probe.db"}});
CONFIG
cat > schema.prisma <<'SCHEMA'
generator client {
  provider = "prisma-client-js"
  output = "./generated"
}
datasource db {
  provider = "sqlite"
}
model Project {
  id Int @id
  name String
}
SCHEMA
npx --no-install prisma validate
npx --no-install prisma migrate dev --name init
npx --no-install prisma generate
cat > query.cjs <<'QUERY'
const assert = require('node:assert/strict');
const {PrismaClient} = require('./generated');
const {PrismaBetterSqlite3} = require('@prisma/adapter-better-sqlite3');
const prisma = new PrismaClient({adapter:new PrismaBetterSqlite3({url:'file:./probe.db'})});
(async () => {
  await prisma.project.upsert({where:{id:1},create:{id:1,name:'Arch on Android'},update:{name:'Arch on Android'}});
  assert.equal((await prisma.project.findUnique({where:{id:1}})).name,'Arch on Android');
  console.log('ARCH_PRISMA_SQLITE_OK');
})().catch(error=>{console.error(error);process.exitCode=1}).finally(()=>prisma.$disconnect());
QUERY
node query.cjs
npm ls --depth=0
