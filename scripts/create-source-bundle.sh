#!/usr/bin/env bash
# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
repository_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repository_dir"
if ! git diff --quiet || ! git diff --cached --quiet || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
    echo "Commit the complete source first; a source archive must match one reviewed revision." >&2
    exit 1
fi
mosh-core/scripts/verify-sources.sh
revision=$(git rev-parse HEAD)
output_dir=${1:-"$repository_dir/build/distributions"}
mkdir -p "$output_dir"
output_dir=$(CDPATH= cd -- "$output_dir" && pwd)
name="terminal-spike-source-$revision"
archive="$output_dir/$name.tar.gz"
git archive --format=tar --prefix="$name/" "$revision" | gzip -n > "$archive"
(cd "$output_dir" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256")
echo "Complete Corresponding Source: $archive"
