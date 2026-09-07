#!/usr/bin/env bash
# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
extension_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
distfiles_dir="$extension_dir/third_party/distfiles"
mkdir -p "$distfiles_dir"

fetch() {
    local name=$1
    local url=$2
    local expected=$3
    local destination="$distfiles_dir/$name"

    if [[ -f "$destination" ]]; then
        local actual
        actual=$(sha256sum "$destination" | awk '{print $1}')
        if [[ "$actual" != "$expected" ]]; then
            echo "$destination exists with unexpected SHA-256 $actual" >&2
            exit 1
        fi
        return
    fi

    local temporary
    temporary=$(mktemp "$distfiles_dir/.${name}.XXXXXX")
    trap 'rm -f "$temporary"' RETURN
    curl --fail --location --retry 3 --output "$temporary" "$url"
    local actual
    actual=$(sha256sum "$temporary" | awk '{print $1}')
    if [[ "$actual" != "$expected" ]]; then
        echo "$url returned unexpected SHA-256 $actual" >&2
        exit 1
    fi
    mv "$temporary" "$destination"
    trap - RETURN
}

fetch \
    mosh-1.4.0.tar.gz \
    https://github.com/mobile-shell/mosh/releases/download/mosh-1.4.0/mosh-1.4.0.tar.gz \
    872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd
fetch \
    nettle-3.10.2.tar.gz \
    https://ftp.gnu.org/gnu/nettle/nettle-3.10.2.tar.gz \
    fe9ff51cb1f2abb5e65a6b8c10a92da0ab5ab6eaf26e7fc2b675c45f1fb519b5
fetch \
    protobuf-all-21.12.tar.gz \
    https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protobuf-all-21.12.tar.gz \
    2c6a36c7b5a55accae063667ef3c55f2642e67476d96d355ff0acb13dbb47f09

"$script_dir/verify-sources.sh"
