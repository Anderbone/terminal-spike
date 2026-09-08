#!/bin/bash
# Opt-in isolated-phone probe. Never import host env files, accounts or databases.
set -euo pipefail
exec > >(tee /root/cable-flow-probe.log) 2>&1
export SCARF_ANALYTICS=false DO_NOT_TRACK=1 HUSKY=0
printf 'ARCH_CABLE_FLOW_PROBE_START\n'
test "$(node -p 'process.versions.node.split(".")[0]')" = 24
npm install --global pnpm@10.29.2 @openai/codex@0.153.4
codex --version
codex --help >/root/codex-help.txt
codex login --help >/root/codex-login-help.txt
printf 'ARCH_CODEX_CLI_OK\n'
pacman -Syu --needed --noconfirm postgresql
# PRoot must virtualize a non-root guest identity for PostgreSQL initdb.
id postgres
runuser -u postgres -- id
probe=/var/lib/postgres/cable-flow-phone-seed
if [[ -d "$probe" && ! -f "$probe/global/pg_control" ]]; then
    mv "$probe" "$probe.incomplete-$(date +%s)"
fi
mkdir -p "$probe"
chown postgres:postgres "$probe"
if [[ ! -f "$probe/PG_VERSION" ]]; then
    timeout --kill-after=10s 180s runuser -u postgres -- initdb -D "$probe" \
        --auth-local=trust --auth-host=trust --encoding=UTF8 --locale=C.UTF-8 \
        -c shared_memory_type=mmap -c dynamic_shared_memory_type=mmap \
        -c shared_buffers=32MB -c max_connections=40 -c io_method=sync \
        -c max_parallel_workers=0 -c jit=off
fi
# Loopback only, separate port, and no reliance on Android's unavailable systemd.
runuser -u postgres -- pg_ctl -D "$probe" -l "$probe/server.log" -o '-h 127.0.0.1 -p 55432 -k /tmp -c shared_memory_type=mmap -c dynamic_shared_memory_type=mmap' -w start
cleanup() {
    [[ -z "${web_pid:-}" ]] || kill "$web_pid" 2>/dev/null || true
    runuser -u postgres -- pg_ctl -D "$probe" -m fast -w stop || true
}
trap cleanup EXIT
export PGHOST=127.0.0.1 PGPORT=55432 PGUSER=postgres
psql -d postgres -Atc 'select version()'
if ! test -f /usr/share/postgresql/extension/vector.control; then
    # Upstream stable pgvector, built locally against this phone's PostgreSQL.
    if [[ ! -d /root/pgvector-0.8.2 ]]; then
        git clone --depth 1 --branch v0.8.2 https://github.com/pgvector/pgvector.git /root/pgvector-0.8.2
    fi
    make -C /root/pgvector-0.8.2 -j2 OPTFLAGS='' with_llvm=no
    make -C /root/pgvector-0.8.2 with_llvm=no install
fi
for database in cable_flow_phone_seed cable_flow_phone_seed_pgboss; do
    if ! psql -d postgres -Atc 'select datname from pg_database' | grep -qx "$database"; then createdb "$database"; fi
done
psql -d cable_flow_phone_seed -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_trgm;'
mkdir -p /root/projects/cable-flow-phone
cd /root/projects/cable-flow-phone
# Archive was constructed from committed files, excluding env, licenses and external symlinks.
tar -xf /root/cable-flow-source.tar
pnpm install --frozen-lockfile
export DATABASE_URL=postgresql://postgres@127.0.0.1:55432/cable_flow_phone_seed?schema=public
export PGBOSS_DATABASE_URL=postgresql://postgres@127.0.0.1:55432/cable_flow_phone_seed_pgboss?schema=public
export SEED_DATABASE_GUARD=I_UNDERSTAND_THIS_DATABASE_CAN_BE_RESET
export BETTER_AUTH_URL=http://127.0.0.1:4174
export BETTER_AUTH_SECRET="$(openssl rand -hex 32)"
export BETTER_AUTH_COOKIE_PREFIX=cable-flow-phone-seed
pnpm exec prisma generate
pnpm exec prisma migrate deploy
pnpm exec prisma migrate status
pnpm run db:apply-functions
pnpm run db:verify-managed-sql
pnpm exec tsx prisma/seed.ts
# Exercise the project's existing CI test license, never transfer the host license.
export CI=true CABLE_FLOW_CI_E2E_LICENSE_BYPASS=1
pnpm exec react-router dev --host 127.0.0.1 --port 4174 > /root/cable-flow-server.log 2>&1 &
web_pid=$!
for attempt in $(seq 1 180); do
    if curl --fail --silent http://127.0.0.1:4174/login -o /root/cable-flow-login.html; then break; fi
    kill -0 "$web_pid" || { cat /root/cable-flow-server.log; exit 1; }
    sleep 2
done
curl --fail --silent --show-error http://127.0.0.1:4174/login -o /root/cable-flow-login.html
test -s /root/cable-flow-login.html
codex --version
printf 'ARCH_CODEX_CLI_OK\nARCH_CABLE_FLOW_HTTP_OK\n'
