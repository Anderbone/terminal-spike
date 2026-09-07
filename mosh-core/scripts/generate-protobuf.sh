#!/usr/bin/env bash
# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
extension_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
generated_dir="$extension_dir/src/main/cpp/generated"
work_dir=$(mktemp -d /tmp/terminal-spike-mosh-protobuf.XXXXXX)
trap 'rm -rf "$work_dir"' EXIT

"$script_dir/verify-sources.sh"
tar -xzf "$extension_dir/third_party/distfiles/protobuf-all-21.12.tar.gz" -C "$work_dir"
tar -xzf "$extension_dir/third_party/distfiles/mosh-1.4.0.tar.gz" -C "$work_dir"

cmake \
    -S "$work_dir/protobuf-21.12/cmake" \
    -B "$work_dir/protobuf-host" \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_WITH_ZLIB=OFF \
    -DCMAKE_BUILD_TYPE=Release
cmake --build "$work_dir/protobuf-host" --target protoc --parallel 2

mkdir -p "$work_dir/generated"
"$work_dir/protobuf-host/protoc" \
    --cpp_out="$work_dir/generated" \
    -I"$work_dir/mosh-1.4.0/src/protobufs" \
    "$work_dir/mosh-1.4.0/src/protobufs/userinput.proto" \
    "$work_dir/mosh-1.4.0/src/protobufs/hostinput.proto" \
    "$work_dir/mosh-1.4.0/src/protobufs/transportinstruction.proto"

if [[ "${1:-}" == "--write" ]]; then
    cp "$work_dir/generated/"*.pb.cc "$generated_dir/"
    cp "$work_dir/generated/"*.pb.h "$generated_dir/"
    (
        cd "$generated_dir"
        sha256sum hostinput.pb.cc hostinput.pb.h \
            transportinstruction.pb.cc transportinstruction.pb.h \
            userinput.pb.cc userinput.pb.h > SHA256SUMS
    )
    echo "Regenerated checked-in Mosh protobuf C++ sources."
else
    for generated in "$work_dir/generated/"*.pb.cc "$work_dir/generated/"*.pb.h; do
        name=$(basename "$generated")
        cmp "$generated" "$generated_dir/$name"
    done
    (
        cd "$generated_dir"
        sha256sum --check SHA256SUMS
    )
    echo "Checked-in Mosh protobuf C++ sources are reproducible with protoc 3.21.12."
fi
