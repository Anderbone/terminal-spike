# Cable Flow on Terminal Spike's Local Arch Linux

Updated: 2026-09-09. This is the phone-local handoff for the user and Codex.

## 1. Finish installing the tools

In Terminal Spike, close Local Arch tabs, then choose Settings → Local Arch Linux
→ Install starter tools. For a new environment choose Install Arch Linux instead.
Wait for Arch is ready, then Open local shell. Do not reset/reinstall an existing
environment to add tools: that deletes projects and credentials.

Verify:

```bash
git --version
gh --version
codex --version
node --version
npm --version
```

Starter version 2 includes GitHub CLI from signed Arch ARM packages and installs
stable Codex 0.153.4 from the official npm registry if Codex is absent. Existing
Node and Codex choices are preserved. pnpm and PostgreSQL are project setup below.

## 2. User: sign into GitHub

Choose **Settings → Local Arch Linux → Sign in to GitHub**. Tap **Copy code and
open browser**, paste the code, and authorize GitHub CLI with the account that
has access to Anderbone/cable-flow. Return to Settings for the completion result.
The app configures Git's HTTPS credential helper automatically. No terminal
login command or SSH key is needed.

## 3. User: sign into Codex

Choose **Settings → Local Arch Linux → Sign in to Codex**, then **Copy code and
open browser**. Sign into ChatGPT, paste the code, and return to Settings.
Enable device-code login in ChatGPT security settings or workspace permissions
first. The installed CLI is a stable release.

Both CLIs save their own credentials in the private Arch filesystem, shared by
all local shells and retained across app/phone restarts. If access expires or is
revoked, use Settings again. Resetting Arch deletes credentials and projects.
These logins do not sign into remote SSH servers. Never paste tokens into a
prompt or commit credentials.

## 4. User: clone Cable Flow and start Codex

```bash
mkdir -p ~/projects
cd ~/projects
gh repo clone Anderbone/cable-flow
cd cable-flow
codex
```

If that directory already exists, enter it and inspect `git status` rather than
cloning over it. Trust the repository only after checking it is your intended repo.
Paste this task into Codex:

> Read /usr/local/share/terminal-spike/cable-flow-phone.md and this repository's
> AGENTS.md. Set up Cable Flow locally on this phone using the verified PRoot
> recipe in the guide, a fresh phone-only PostgreSQL database, and the repository's
> pinned package manager. Run it at http://127.0.0.1:4174 and verify /login. Preserve
> existing files and credentials. Do not reset databases, publish, push, or use the
> CI license bypass for normal use. Ask me for the supported Cable Flow license or
> other required secrets when needed, without printing them. Create phone-local
> start/stop scripts and explain how to use them. Inspect first, then carry out
> the setup and checks; explain any permission or compatibility blocker.

Review Codex's command approvals as they appear. A successful sign-in/onboarding
screen does not prove its command sandbox works on Android: if a sandbox error
occurs, have Codex identify it and discuss the permission mode before proceeding.
PRoot does not itself isolate an agent from files available to the Android app.

## 5. Codex: verified setup recipe and boundaries

The USB SM-S911B ran Cable Flow commit
707411505830f257a1e40ea2d82fde938cb9e0ae with Node 24.20.0, pnpm 10.29.2,
Prisma 7.8.0, PostgreSQL 18.6 and pgvector 0.8.2. Frozen install, existing migrations,
managed SQL verification, seed, and HTTP /login succeeded. This was a fresh test
DB with the repository's CI license fixture. Normal licensed operation and an
authenticated Codex model request were not covered by that probe.

Read the current checkout's package.json, lockfile, AGENTS.md, env template and
license documentation first. Follow its packageManager pin (tested: pnpm@10.29.2).
Do not read or write the workstation Obsidian target of a dangling `doc` symlink;
write this phone's working plan outside that symlink, such as ~/cable-flow-plan.md.

- Keep projects in ~/projects. Use `pacman -Syu --needed postgresql` and install
  the pinned pnpm with npm if missing. Do not use Docker, systemctl or AUR helpers.
- Use a fresh cluster `/var/lib/postgres/cable-flow-phone` and databases
  `cable_flow_phone_seed` and `cable_flow_phone_seed_pgboss`. Inspect any preexisting cluster
  first; never remove or reinitialize it. PostgreSQL runs as guest user postgres,
  not root. The bundled PRoot has the System V IPC compatibility fix.
- Initialize a NEW EMPTY cluster using the following tested settings. PostgreSQL
  18 needs `io_method=sync` here; keep these settings in postgresql.conf for later
  starts. Limit the server to loopback; trust auth below is for this local-only
  development cluster (other processes on the phone can reach loopback).

```bash
install -d -o postgres -g postgres -m 700 /var/lib/postgres/cable-flow-phone
runuser -u postgres -- initdb -D /var/lib/postgres/cable-flow-phone \
  --auth-local=trust --auth-host=trust --encoding=UTF8 --locale=C.UTF-8 \
  -c shared_memory_type=mmap -c dynamic_shared_memory_type=mmap \
  -c shared_buffers=32MB -c max_connections=40 -c io_method=sync \
  -c max_parallel_workers=0 -c jit=off
runuser -u postgres -- pg_ctl -D /var/lib/postgres/cable-flow-phone \
  -l /var/lib/postgres/cable-flow-phone/server.log \
  -o '-h 127.0.0.1 -p 55432 -k /tmp' -w start
```

- Build pgvector stable v0.8.2 from https://github.com/pgvector/pgvector if its
  extension is missing: `make -j2 OPTFLAGS='' with_llvm=no`, then
  `make with_llvm=no install`. This avoids unavailable LLVM tooling on the phone.
- With `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=postgres`, create the TWO named fresh
  databases if absent; enable vector and pg_trgm in the Cable Flow database.
- Persist only phone-local configuration, using the repo's supported env loading
  mechanism and ensuring secrets are ignored by Git before writing them:
  DATABASE_URL=postgresql://postgres@127.0.0.1:55432/cable_flow_phone_seed?schema=public
  and PGBOSS_DATABASE_URL with database cable_flow_phone_seed_pgboss.
  BETTER_AUTH_URL=http://127.0.0.1:4174; generate and persist a fresh
  BETTER_AUTH_SECRET with `openssl rand -hex 32` without printing it. Set a distinct
  BETTER_AUTH_COOKIE_PREFIX. Do not copy workstation or production databases/env.
- Run `pnpm install --frozen-lockfile`, `pnpm exec prisma generate`,
  `pnpm exec prisma migrate deploy`, `pnpm exec prisma migrate status`,
  `pnpm run db:apply-functions`, and `pnpm run db:verify-managed-sql`.
  Do not generate migrations, run migrate reset, or use db push. The external
  cable_knowledge_chunk_embedding table, vector(1024) column and HNSW index must
  remain managed by the repository's SQL flow.
- Inspect the seed guard before running `pnpm exec tsx prisma/seed.ts`. The prior
  test used SEED_DATABASE_GUARD=I_UNDERSTAND_THIS_DATABASE_CAN_BE_RESET only against
  its newly created isolated database. Seed only the fresh phone DB after
  confirming that guard accepts the chosen target; never bypass a refusal.
- Use the supported Cable Flow license setup. Do not set CI=true or
  CABLE_FLOW_CI_E2E_LICENSE_BYPASS for the user's everyday app. If the license is
  missing, finish independent setup and explain the exact user action required.
- Start `pnpm exec react-router dev --host 127.0.0.1 --port 4174` with the saved env
  and verify `curl --fail http://127.0.0.1:4174/login` from another shell. Ask the
  user to open http://127.0.0.1:4174 in the phone browser and finish app sign-in.

## 6. Daily use and restarting

Have Codex save start/stop scripts that load the phone env, start this cluster only
if stopped, and run the app in the foreground. Keep its Local Arch tab open when
switching to the browser; a second tab can run Codex. Do not close the server tab
or force-stop the Android app while using Cable Flow. Files survive Android
process death, but services must be restarted. The installer staying alive in the
background does not mean arbitrary shell programs can survive Android termination.

After stopping the web server with Ctrl+C, stop this cluster with:

```bash
runuser -u postgres -- pg_ctl -D /var/lib/postgres/cable-flow-phone -m fast -w stop
```

## Official references

- GitHub login: https://cli.github.com/manual/gh_auth_login
- Git credential helper: https://cli.github.com/manual/gh_auth_setup-git
- Codex authentication: https://learn.chatgpt.com/docs/auth
- Codex CLI: https://learn.chatgpt.com/docs/codex/cli
- Arch ARM GitHub CLI: https://archlinuxarm.org/packages/aarch64/github-cli
- pgvector: https://github.com/pgvector/pgvector/tree/v0.8.2
