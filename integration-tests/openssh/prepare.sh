#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
state_dir="$script_dir/.state"
client_dir="$state_dir/client"
server_dir="$state_dir/server"

umask 077
mkdir -p "$client_dir" "$server_dir"

if [ ! -f "$client_dir/id_ed25519" ]; then
    ssh-keygen -q -t ed25519 -N '' -C 'terminal-spike integration client' \
        -f "$client_dir/id_ed25519"
fi

if [ ! -f "$server_dir/ssh_host_ecdsa_key" ]; then
    ssh-keygen -q -t ecdsa -b 256 -N '' -C 'terminal-spike integration host' \
        -f "$server_dir/ssh_host_ecdsa_key"
fi

touch "$client_dir/known_hosts"
chmod 0600 "$client_dir/id_ed25519" "$client_dir/known_hosts" \
    "$server_dir/ssh_host_ecdsa_key"
chmod 0644 "$client_dir/id_ed25519.pub" "$server_dir/ssh_host_ecdsa_key.pub"

printf 'Client public key: %s\n' "$client_dir/id_ed25519.pub"
ssh-keygen -lf "$client_dir/id_ed25519.pub"
printf 'Server host-key fingerprint:\n'
ssh-keygen -lf "$server_dir/ssh_host_ecdsa_key.pub"
