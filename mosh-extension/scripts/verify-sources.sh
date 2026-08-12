#!/usr/bin/env bash
# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
extension_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)

cd "$extension_dir/third_party"
sha256sum --check SHA256SUMS

(
    cd "$extension_dir/licenses"
    sha256sum --check SHA256SUMS
)

if ! cmp --silent \
    "$extension_dir/THIRD_PARTY_NOTICES.md" \
    "$extension_dir/src/main/assets/THIRD_PARTY_NOTICES.md"; then
    echo "Packaged and source-tree third-party notices differ." >&2
    exit 1
fi

mosh_version=$(tar -xOf distfiles/mosh-1.4.0.tar.gz mosh-1.4.0/configure.ac | sed -n 's/^AC_INIT(\[mosh\], \[\([^]]*\)\].*/\1/p')
if [[ "$mosh_version" != "1.4.0" ]]; then
    echo "Mosh archive declares unexpected version: $mosh_version" >&2
    exit 1
fi

nettle_version=$(tar -xOf distfiles/nettle-3.10.2.tar.gz nettle-3.10.2/configure.ac | sed -n 's/^AC_INIT(\[nettle\], \[\([^]]*\)\].*/\1/p')
if [[ "$nettle_version" != "3.10.2" ]]; then
    echo "Nettle archive declares unexpected version: $nettle_version" >&2
    exit 1
fi

protobuf_version=$(tar -xOf distfiles/protobuf-all-21.12.tar.gz protobuf-21.12/configure.ac | sed -n 's/^AC_INIT(\[Protocol Buffers\],\[3\.\([^]]*\)\].*/\1/p')
if [[ "$protobuf_version" != "21.12" ]]; then
    echo "Protocol Buffers archive declares unexpected version: $protobuf_version" >&2
    exit 1
fi

echo "Pinned native source archives verified."
