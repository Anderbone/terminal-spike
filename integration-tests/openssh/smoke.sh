#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
port=${TERMINAL_SPIKE_SSH_PORT:-22222}
host=${TERMINAL_SPIKE_SSH_VERIFY_HOST:-127.0.0.1}
known_hosts="$script_dir/.state/client/known_hosts"
identity="$script_dir/.state/client/id_ed25519"

"$script_dir/prepare.sh"
docker compose --project-directory "$script_dir" -f "$script_dir/compose.yaml" up -d --build

attempt=0
until ssh-keyscan -T 2 -p "$port" "$host" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        printf 'OpenSSH test server did not become ready.\n' >&2
        exit 1
    fi
    sleep 1
done

ssh -F /dev/null \
    -i "$identity" \
    -o BatchMode=yes \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile="$known_hosts" \
    -p "$port" \
    "terminal@$host" \
    "printf 'terminal-spike-openssh-ok\\n'"
