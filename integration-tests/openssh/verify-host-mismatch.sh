#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
port=${TERMINAL_SPIKE_SSH_PORT:-22222}
host=${TERMINAL_SPIKE_SSH_VERIFY_HOST:-127.0.0.1}
known_hosts="$script_dir/.state/client/known_hosts"
identity="$script_dir/.state/client/id_ed25519"
output_file=$(mktemp)
trap 'rm -f "$output_file"' EXIT HUP INT TERM

set +e
ssh -F /dev/null \
    -i "$identity" \
    -o BatchMode=yes \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$known_hosts" \
    -p "$port" \
    "terminal@$host" true >"$output_file" 2>&1
status=$?
set -e

if [ "$status" -eq 0 ]; then
    printf 'Expected a changed-host-key failure, but SSH connected.\n' >&2
    exit 1
fi

if ! grep -q 'REMOTE HOST IDENTIFICATION HAS CHANGED' "$output_file"; then
    printf 'SSH failed, but not because the saved host key changed.\n' >&2
    sed -n '1,20p' "$output_file" >&2
    exit 1
fi

printf 'Changed-host-key protection verified.\n'
