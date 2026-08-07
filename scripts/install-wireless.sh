#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb_bin="$(command -v adb || true)"
if [[ -z "$adb_bin" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$sdk_root" && -f "$project_dir/local.properties" ]]; then
        sdk_root="$(sed -n 's/^sdk\.dir=//p' "$project_dir/local.properties" | tail -n 1)"
    fi
    adb_bin="${sdk_root:+$sdk_root/platform-tools/adb}"
fi

if [[ -z "$adb_bin" || ! -x "$adb_bin" ]]; then
    echo "Android platform-tools not found. Put adb on PATH or configure the SDK in the environment/local.properties." >&2
    exit 1
fi

if [[ "${1:-}" == "--list" ]]; then
    "$adb_bin" devices -l
    exit 0
fi

serial="${1:-}"
if [[ -z "$serial" ]]; then
    echo "Usage: scripts/install-wireless.sh <wireless-adb-serial>" >&2
    echo "Run scripts/install-wireless.sh --list to find the exact target." >&2
    exit 2
fi

device_line="$($adb_bin devices -l | awk -v serial="$serial" '$1 == serial && $2 == "device" { print }')"
if [[ -z "$device_line" ]]; then
    echo "The requested ADB target is not connected and authorized: $serial" >&2
    exit 3
fi

if [[ "$serial" != *"._adb-tls-connect._tcp"* && "$serial" != *":"* ]]; then
    echo "Refusing to install: $serial does not look like a wireless ADB target." >&2
    exit 4
fi

if ! command -v java >/dev/null && [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
    echo "Java not found. Set JAVA_HOME to a compatible JDK before installing." >&2
    exit 5
fi

cd "$project_dir"
./gradlew testDebugUnitTest lintDebug assembleDebug
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$serial" shell am start -n com.yanjiyu.terminalspike/.MainActivity

echo "Installed and launched Terminal Spike on:"
echo "$device_line"
