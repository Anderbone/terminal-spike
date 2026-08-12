#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
server_dir="$script_dir/.state/server"
port=${TERMINAL_SPIKE_SSH_PORT:-22222}
host=${TERMINAL_SPIKE_SSH_VERIFY_HOST:-127.0.0.1}
private_key="$server_dir/ssh_host_ecdsa_key"
public_key="$private_key.pub"
previous_private="$private_key.previous"
previous_public="$public_key.previous"

"$script_dir/prepare.sh" >/dev/null

rm -f "$previous_private" "$previous_public"
mv "$private_key" "$previous_private"
mv "$public_key" "$previous_public"

umask 077
if ! ssh-keygen -q -t ecdsa -b 256 -N '' -C 'terminal-spike rotated integration host' \
    -f "$private_key"; then
    mv "$previous_private" "$private_key"
    mv "$previous_public" "$public_key"
    exit 1
fi
chmod 0600 "$private_key"
chmod 0644 "$public_key"

docker compose --project-directory "$script_dir" -f "$script_dir/compose.yaml" \
    up -d --force-recreate ssh

attempt=0
until ssh-keyscan -T 2 -p "$port" "$host" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        printf 'Rotated OpenSSH test server did not become ready.\n' >&2
        exit 1
    fi
    sleep 1
done

printf 'Previous host key (recoverable at %s):\n' "$previous_public"
ssh-keygen -lf "$previous_public"
printf 'New host key:\n'
ssh-keygen -lf "$public_key"
printf 'The next strict connection must report a changed host key.\n'
