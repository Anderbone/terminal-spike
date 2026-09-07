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
    echo "Android platform-tools not found. Put adb on PATH or configure the SDK." >&2
    exit 1
fi

usage() {
    cat >&2 <<'EOF'
Usage:
  scripts/install-wireless.sh --list
  scripts/install-wireless.sh --test-old-phone <serial> <gradle-task>...
  scripts/install-wireless.sh --install-old-phone <serial> <app-debug.apk>
  scripts/install-wireless.sh --final-fold-install <serial> <app-debug.apk> --feature-complete
EOF
}

if [[ "${1:-}" == "--list" ]]; then
    [[ $# -eq 1 ]] || { usage; exit 2; }
    "$adb_bin" devices -l
    exit 0
fi

mode="${1:-}"
serial="${2:-}"
if [[ -z "$mode" || -z "$serial" ]]; then
    usage
    exit 2
fi
if [[ ! "$serial" =~ ^[A-Za-z0-9._:-]+$ ]]; then
    echo "Refusing unsafe ADB serial syntax." >&2
    exit 2
fi

verify_target() {
    local expected_model="$1"
    local state
    local model
    state="$("$adb_bin" -s "$serial" get-state 2>/dev/null || true)"
    if [[ "$state" != "device" ]]; then
        echo "The requested ADB target is offline, unauthorized, or unavailable: $serial" >&2
        exit 3
    fi
    model="$("$adb_bin" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    if [[ "$model" != "$expected_model" ]]; then
        echo "Refusing target model '$model'; expected '$expected_model'." >&2
        exit 4
    fi
}

require_main_debug_apk() {
    local requested="$1"
    local expected="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
    if [[ ! -f "$requested" ]]; then
        echo "Main debug APK does not exist: $requested" >&2
        exit 5
    fi
    local resolved
    resolved="$(realpath "$requested")"
    if [[ "$resolved" != "$expected" ]]; then
        echo "Only the repository's main debug APK may be installed by this helper." >&2
        exit 5
    fi
    printf '%s\n' "$resolved"
}

launch_and_verify() {
    local expected_model="$1"
    verify_target "$expected_model"
    "$adb_bin" -s "$serial" shell am start -W \
        -n com.yanjiyu.terminalspike/.MainActivity >/dev/null
    local activities
    activities="$("$adb_bin" -s "$serial" shell dumpsys activity activities)"
    if ! grep -Eq 'topResumedActivity=.*com\.yanjiyu\.terminalspike/\.MainActivity' \
        <<<"$activities"; then
        echo "MainActivity did not become the top-resumed activity on $serial." >&2
        exit 6
    fi
}

case "$mode" in
    --test-old-phone)
        shift 2
        [[ $# -gt 0 ]] || { usage; exit 2; }
        for task in "$@"; do
            if [[ ! "$task" =~ ^(:[A-Za-z0-9_.-]+:)?(connected[A-Za-z0-9_.-]*AndroidTest|[A-Za-z0-9_.-]+BenchmarkAndroidTest|generate[A-Za-z0-9_.-]*BaselineProfile|collect[A-Za-z0-9_.-]*BaselineProfile)$ ]]; then
                echo "Refusing non-device-test Gradle task: $task" >&2
                exit 2
            fi
        done
        verify_target "SM-S911B"
        cd "$project_dir"
        ANDROID_SERIAL="$serial" ./gradlew "$@"
        ;;
    --install-old-phone)
        [[ $# -eq 3 ]] || { usage; exit 2; }
        apk="$(require_main_debug_apk "$3")"
        verify_target "SM-S911B"
        "$adb_bin" -s "$serial" install -r "$apk"
        launch_and_verify "SM-S911B"
        ;;
    --final-fold-install)
        [[ $# -eq 4 && "$4" == "--feature-complete" ]] || {
            echo "Final fold installation requires --feature-complete." >&2
            usage
            exit 2
        }
        apk="$(require_main_debug_apk "$3")"
        verify_target "SM-F976B"
        "$adb_bin" -s "$serial" install -r "$apk"
        launch_and_verify "SM-F976B"
        ;;
    *)
        usage
        exit 2
        ;;
esac
