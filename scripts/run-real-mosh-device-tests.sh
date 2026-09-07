#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
serial=""
host_address=""
trusted_lan=false
output_dir="$project_dir/build/real-mosh-device-results"
fixture_dir="$project_dir/integration-tests/openssh"
app_apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$project_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
extension_apk="$project_dir/mosh-extension/build/outputs/apk/debug/mosh-extension-debug.apk"
orchestrator_apk="$project_dir/app/build/outputs/runtime-test-utils/orchestrator-1.6.1.apk"
test_services_apk="$project_dir/app/build/outputs/runtime-test-utils/test-services-1.6.0.apk"
private_key_file="$fixture_dir/.state/client/id_ed25519"
fixture_started=false
private_workspace=""

usage() {
    cat >&2 <<'EOF'
Usage: scripts/run-real-mosh-device-tests.sh --serial <old-phone-serial>
       --host-address <trusted-lan-ipv4> --trusted-lan [--output-dir <path>]

Runs the fixed real-Mosh acceptance matrix only on model SM-S911B. The trusted-LAN
acknowledgement is required because the disposable SSH/Mosh fixture is exposed to that LAN.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) serial="${2:-}"; shift 2 ;;
        --host-address) host_address="${2:-}"; shift 2 ;;
        --trusted-lan) trusted_lan=true; shift ;;
        --output-dir) output_dir="${2:-}"; shift 2 ;;
        *) usage; exit 2 ;;
    esac
done

[[ -n "$serial" && -n "$host_address" ]] || { usage; exit 2; }
[[ "$trusted_lan" == true ]] || {
    echo "Refusing to expose the disposable fixture without --trusted-lan." >&2
    exit 2
}
[[ "$serial" =~ ^[A-Za-z0-9._:-]+$ ]] || {
    echo "Refusing unsafe ADB serial syntax." >&2
    exit 2
}

validate_ipv4() {
    local address="$1"
    local octets
    local octet
    [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
    IFS=. read -r -a octets <<<"$address"
    for octet in "${octets[@]}"; do
        ((10#$octet <= 255)) || return 1
    done
    [[ "$address" != "0.0.0.0" && "$address" != 127.* && "$address" != 169.254.* ]]
}
validate_ipv4 "$host_address" || {
    echo "Host address must be one explicit, non-loopback trusted-LAN IPv4 address." >&2
    exit 2
}

adb_bin="$(command -v adb || true)"
if [[ -z "$adb_bin" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$sdk_root" && -f "$project_dir/local.properties" ]]; then
        sdk_root="$(sed -n 's/^sdk\.dir=//p' "$project_dir/local.properties" | tail -n 1)"
    fi
    adb_bin="${sdk_root:+$sdk_root/platform-tools/adb}"
fi
[[ -n "$adb_bin" && -x "$adb_bin" ]] || {
    echo "Android platform-tools not found. Put adb on PATH or configure the SDK." >&2
    exit 3
}
for required_tool in docker timeout base64 python3; do
    command -v "$required_tool" >/dev/null || {
        echo "Required tool is missing: $required_tool" >&2
        exit 3
    }
done

verify_old_phone() {
    local devices
    local matching_line
    local state
    local model
    devices="$("$adb_bin" devices -l)"
    matching_line="$(awk -v wanted="$serial" '$1 == wanted { print; count += 1 } END { if (count != 1) exit 1 }' <<<"$devices")" || {
        echo "The requested ADB target is not uniquely present: $serial" >&2
        return 1
    }
    [[ "$matching_line" == *$'\tdevice '* || "$matching_line" == *" device "* ]] || {
        echo "The requested ADB target is offline or unauthorized: $serial" >&2
        return 1
    }
    [[ "$matching_line" == *"model:SM_S911B"* ]] || {
        echo "Refusing ADB inventory target that is not model SM_S911B." >&2
        return 1
    }
    state="$("$adb_bin" -s "$serial" get-state 2>/dev/null || true)"
    [[ "$state" == "device" ]] || {
        echo "The requested ADB target is offline, unauthorized, or unavailable: $serial" >&2
        return 1
    }
    model="$("$adb_bin" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    [[ "$model" == "SM-S911B" ]] || {
        echo "Refusing target model '$model'; expected 'SM-S911B'." >&2
        return 1
    }
}

cleanup() {
    if [[ "$fixture_started" == true ]]; then
        docker compose --project-directory "$fixture_dir" -f "$fixture_dir/compose.yaml" down \
            >/dev/null 2>&1 || true
    fi
    if [[ -n "$private_workspace" && -d "$private_workspace" && \
        "$(dirname "$private_workspace")" == "$(realpath "${TMPDIR:-/tmp}")" && \
        "$(basename "$private_workspace")" == terminal-spike-real-mosh.* ]]; then
        rm -rf -- "$private_workspace"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

verify_old_phone
cd "$project_dir"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:stageRuntimeTestUtilities \
    :mosh-extension:assembleDebug

for artifact in "$app_apk" "$test_apk" "$extension_apk" "$orchestrator_apk" "$test_services_apk"; do
    [[ -f "$artifact" ]] || { echo "Required APK is missing: $artifact" >&2; exit 3; }
done

mkdir -p "$output_dir"
output_dir="$(realpath "$output_dir")"
task_temp_root="$(realpath "${TMPDIR:-/tmp}")"
private_workspace="$(mktemp -d "$task_temp_root/terminal-spike-real-mosh.XXXXXX")"
chmod 0700 "$private_workspace"

# A clean container is part of the test contract: stale mosh-server processes otherwise consume
# the fixture's intentionally bounded UDP range and make reruns order-dependent.
docker compose --project-directory "$fixture_dir" -f "$fixture_dir/compose.yaml" down
fixture_started=true
TERMINAL_SPIKE_SSH_BIND_ADDRESS="$host_address" \
TERMINAL_SPIKE_SSH_VERIFY_HOST="$host_address" \
    "$fixture_dir/smoke.sh" >/dev/null
[[ -f "$private_key_file" ]] || { echo "Disposable fixture private key is missing." >&2; exit 3; }

install_apk() {
    local apk="$1"
    local force_queryable="${2:-false}"
    local -a options=(-r)
    [[ "$force_queryable" == true ]] && options=(--force-queryable -r)
    for ((attempt = 1; attempt <= ${TERMINAL_SPIKE_INSTALL_ATTEMPTS:-3}; attempt++)); do
        verify_old_phone
        if "$adb_bin" -s "$serial" install "${options[@]}" "$apk" >/dev/null; then
            return 0
        fi
        sleep "${TERMINAL_SPIKE_INSTALL_POLL_SECONDS:-2}"
    done
    echo "Failed to install APK after bounded retries: $apk" >&2
    return 1
}

install_apk "$app_apk"
install_apk "$test_apk"
install_apk "$extension_apk"
install_apk "$orchestrator_apk" true
install_apk "$test_services_apk" true

verify_old_phone
"$adb_bin" -s "$serial" shell "toybox nc -z -w 5 $host_address 22222" >/dev/null || {
    echo "The old phone cannot reach the disposable SSH fixture at the requested address." >&2
    exit 4
}

private_key_base64="$(base64 -w 0 "$private_key_file")"
instrumentation_target="com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"
orchestrator_target="androidx.test.orchestrator/.AndroidTestOrchestrator"
mosh_class="com.yanjiyu.terminalspike.connection.MoshRealEndToEndTest"

run_acceptance() {
    local label="$1"
    local filter="$2"
    local auth_kind="$3"
    local expected_count="$4"
    shift 4
    local -a required_tests=("$@")
    local -a arguments=(
        -e class "$filter"
        -e moshE2eHost "$host_address"
        -e moshE2eSshPort 22222
        -e moshE2eUsername terminal
        -e moshE2eUdpFirst 62000
        -e moshE2eUdpLast 62010
    )
    if [[ "$auth_kind" == password ]]; then
        arguments+=(-e moshE2ePassword terminal-spike-test-only)
    else
        arguments+=(-e moshE2ePrivateKeyBase64 "$private_key_base64")
    fi
    local forwarded_arguments=""
    local target_arguments=""
    printf -v forwarded_arguments ' %q' "${arguments[@]}"
    printf -v target_arguments ' %q' \
        -e targetInstrumentation "$instrumentation_target" \
        -e clearPackageData true "$orchestrator_target"
    local command='CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -w -r'
    command+="$forwarded_arguments$target_arguments"
    local private_result="$private_workspace/instrumentation-$label.txt"
    local public_result="$output_dir/instrumentation-real-mosh-$label.txt"
    local report="$output_dir/TEST-real-mosh-$label.xml"

    verify_old_phone
    "$adb_bin" -s "$serial" logcat -c >/dev/null 2>&1 || true
    set +e
    timeout --foreground "${TERMINAL_SPIKE_INSTRUMENTATION_TIMEOUT_SECONDS:-900}" \
        "$adb_bin" -s "$serial" shell "$command" >"$private_result" 2>&1
    local status=$?
    set -e
    python3 "$project_dir/scripts/redact-android-test-log.py" <"$private_result" >"$public_result"
    "$adb_bin" -s "$serial" logcat -d | \
        python3 "$project_dir/scripts/redact-android-test-log.py" \
        >"$output_dir/logcat-real-mosh-$label.txt"
    [[ $status -eq 0 ]] || {
        echo "Real-Mosh instrumentation command failed for $label." >&2
        return "$status"
    }
    if grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[34]' "$public_result"; then
        echo "Real-Mosh instrumentation contained an assumption skip for $label." >&2
        return 5
    fi
    for required_test in "${required_tests[@]}"; do
        grep -Fq "test=$required_test" "$public_result" || {
            echo "Required real-Mosh test did not run: $required_test" >&2
            return 5
        }
    done
    grep -Fq "OK ($expected_count test" "$public_result" || {
        echo "Real-Mosh test count was not exactly the required $expected_count for $label." >&2
        return 5
    }
    python3 "$project_dir/scripts/instrumentation-output-to-junit.py" "$public_result" "$report"
    python3 "$project_dir/scripts/assert-test-report.py" "$report"
}

run_acceptance password "$mosh_class" password 5 \
    appSelectedTmuxLocalScrollAndReaderAnchorSurviveActivityRecreation \
    realServerCarriesInteractiveTerminalBytesThroughTheExtension \
    fourConcurrentSessionsResizeIndependentlyAndOneCloseDoesNotStopTheOthers \
    realWorkerDeathFailsOnlyItsSessionAndTheReleasedSlotIsReusable \
    realBrokerDeathFailsTheSessionThenClientRebindsForFreshTraffic
run_acceptance private-key \
    "$mosh_class#realServerCarriesInteractiveTerminalBytesThroughTheExtension" \
    private-key 1 realServerCarriesInteractiveTerminalBytesThroughTheExtension
unset private_key_base64

echo "REAL_MOSH_DEVICE_RESULT model=SM-S911B password_tests=5 private_key_tests=1 failures=0 skips=0"
