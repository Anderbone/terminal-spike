#!/usr/bin/env bash
# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
extension_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
repository_dir=$(CDPATH= cd -- "$extension_dir/.." && pwd)
bundle_name="terminal-spike-mosh-extension-source-1.0.0"
output_dir=${1:-"$extension_dir/build/distributions"}
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/terminal-spike-mosh-source.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT

"$script_dir/verify-sources.sh"

mkdir -p "$output_dir"
output_dir=$(CDPATH= cd -- "$output_dir" && pwd)
bundle_dir="$work_dir/$bundle_name"
mkdir -p "$bundle_dir/gradle/wrapper" "$bundle_dir/docs"

# Copy only the two modules that form the separate GPL extension boundary. Build output and IDE
# state are excluded even when the requested archive directory is inside mosh-extension/build.
tar \
    --exclude='mosh-extension/build' \
    --exclude='mosh-extension/.cxx' \
    --exclude='mosh-api/build' \
    --exclude='mosh-api/.cxx' \
    -C "$repository_dir" \
    -cf - \
    mosh-extension mosh-api | tar -C "$bundle_dir" -xf -

install -m 0644 "$extension_dir/source-build/settings.gradle.kts" "$bundle_dir/settings.gradle.kts"
install -m 0644 "$extension_dir/source-build/build.gradle.kts" "$bundle_dir/build.gradle.kts"
install -m 0644 "$extension_dir/source-build/gradle.properties" "$bundle_dir/gradle.properties"
install -m 0644 "$extension_dir/source-build/README.md" "$bundle_dir/README.md"
install -m 0755 "$repository_dir/gradlew" "$bundle_dir/gradlew"
install -m 0644 "$repository_dir/gradlew.bat" "$bundle_dir/gradlew.bat"
install -m 0644 "$extension_dir/source-build/libs.versions.toml" "$bundle_dir/gradle/libs.versions.toml"
install -m 0644 "$repository_dir/gradle/wrapper/gradle-wrapper.jar" "$bundle_dir/gradle/wrapper/gradle-wrapper.jar"
install -m 0644 "$repository_dir/gradle/wrapper/gradle-wrapper.properties" "$bundle_dir/gradle/wrapper/gradle-wrapper.properties"
install -m 0644 "$repository_dir/docs/ADR-003-MOSH-EXTENSION-BOUNDARY.md" "$bundle_dir/docs/ADR-003-MOSH-EXTENSION-BOUNDARY.md"
install -m 0644 "$repository_dir/docs/ADR-004-OPEN-SOURCE-APP.md" "$bundle_dir/docs/ADR-004-OPEN-SOURCE-APP.md"
install -m 0644 "$repository_dir/LICENSE" "$bundle_dir/LICENSE"
install -m 0644 "$repository_dir/docs/mosh-extension-protocol.md" "$bundle_dir/docs/mosh-extension-protocol.md"

archive="$output_dir/$bundle_name.tar.gz"
checksum="$archive.sha256"
temporary_archive="$work_dir/$bundle_name.tar.gz"

# Normalize archive metadata and the gzip header. The checked-in source file modes remain visible,
# while host usernames, mtimes, atimes and ctimes cannot change the resulting bytes.
tar \
    --sort=name \
    --mtime='UTC 1970-01-01' \
    --owner=0 \
    --group=0 \
    --numeric-owner \
    --pax-option=delete=atime,delete=ctime \
    -C "$work_dir" \
    -cf - \
    "$bundle_name" | gzip -n > "$temporary_archive"

install -m 0644 "$temporary_archive" "$archive"
(
    cd "$output_dir"
    sha256sum "$(basename "$archive")" > "$(basename "$checksum")"
)

echo "Corresponding Source archive: $archive"
echo "Corresponding Source checksum: $checksum"
