#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
serial=""
host_address=""
host_port=22
host_username="$(id -un)"
trusted_lan=false
output_dir="$project_dir/build/real-codex-tmux-device-results"
app_apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$project_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
orchestrator_apk="$project_dir/app/build/outputs/runtime-test-utils/orchestrator-1.6.1.apk"
test_services_apk="$project_dir/app/build/outputs/runtime-test-utils/test-services-1.6.0.apk"
private_workspace=""
authorized_key_added=false
auth_marker="terminal-spike-codex-e2e-$PPID-$$-$RANDOM"

usage() {
    cat >&2 <<'EOF'
Usage: scripts/run-real-codex-tmux-device-test.sh --serial <old-phone-serial>
       --host-address <trusted-lan-ipv4> --trusted-lan [--host-port <port>]
       [--host-username <name>] [--output-dir <path>]

Runs the actual-Codex and real mouse-application app-selected-tmux acceptance
only on model SM-S911B. It
temporarily authorizes one generated SSH key for the current host account and
removes that exact key on every exit. The trusted-LAN acknowledgement is required.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) serial="${2:-}"; shift 2 ;;
        --host-address) host_address="${2:-}"; shift 2 ;;
        --host-port) host_port="${2:-}"; shift 2 ;;
        --host-username) host_username="${2:-}"; shift 2 ;;
        --trusted-lan) trusted_lan=true; shift ;;
        --output-dir) output_dir="${2:-}"; shift 2 ;;
        *) usage; exit 2 ;;
    esac
done

[[ -n "$serial" && -n "$host_address" && -n "$host_username" ]] || { usage; exit 2; }
[[ "$trusted_lan" == true ]] || {
    echo "Refusing to authorize the host account without --trusted-lan." >&2
    exit 2
}
[[ "$serial" =~ ^[A-Za-z0-9._:-]+$ ]] || { echo "Unsafe ADB serial syntax." >&2; exit 2; }
[[ "$host_username" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] || {
    echo "Unsafe SSH username syntax." >&2
    exit 2
}
[[ "$host_port" =~ ^[0-9]+$ ]] && ((10#$host_port >= 1 && 10#$host_port <= 65535)) || {
    echo "SSH port must be between 1 and 65535." >&2
    exit 2
}

validate_ipv4() {
    local address="$1"
    local -a octets
    local octet
    [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
    IFS=. read -r -a octets <<<"$address"
    for octet in "${octets[@]}"; do ((10#$octet <= 255)) || return 1; done
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
[[ -n "$adb_bin" && -x "$adb_bin" ]] || { echo "Android platform-tools not found." >&2; exit 3; }
for required_tool in base64 codex node python3 ssh ssh-keygen timeout; do
    command -v "$required_tool" >/dev/null || {
        echo "Required tool is missing: $required_tool" >&2
        exit 3
    }
done

codex_bin="$(readlink -f "$(command -v codex)")"
node_bin="$(readlink -f "$(command -v node)")"
[[ "$codex_bin" == /* && "$node_bin" == /* && "$codex_bin" != *$'\n'* && "$node_bin" != *$'\n'* ]] || {
    echo "Codex and Node paths must be absolute single-line paths." >&2
    exit 3
}
printf -v quoted_codex_bin '%q' "$codex_bin"
printf -v quoted_node_bin '%q' "$node_bin"
codex login status 2>&1 | grep -Fq 'Logged in' || {
    echo "The host Codex CLI is not logged in." >&2
    exit 3
}

authorized_keys="${TERMINAL_SPIKE_AUTHORIZED_KEYS_FILE:-$HOME/.ssh/authorized_keys}"
[[ "$authorized_keys" == /* && "$(basename "$authorized_keys")" == authorized_keys ]] || {
    echo "Authorized-keys path must be an absolute file named authorized_keys." >&2
    exit 3
}
mkdir -p "$(dirname "$authorized_keys")"
chmod 0700 "$(dirname "$authorized_keys")"
if [[ -s "$authorized_keys" && "$(tail -c 1 "$authorized_keys" | wc -l)" -ne 1 ]]; then
    echo "Refusing an authorized_keys file without a terminating newline." >&2
    exit 3
fi

verify_old_phone() {
    local devices matching_line state model
    devices="$("$adb_bin" devices -l)"
    matching_line="$(awk -v wanted="$serial" '$1 == wanted { print; count += 1 } END { if (count != 1) exit 1 }' <<<"$devices")" || {
        echo "The requested ADB target is not uniquely present: $serial" >&2
        return 1
    }
    [[ "$matching_line" == *$'\tdevice '* || "$matching_line" == *" device "* ]] || return 1
    [[ "$matching_line" == *"model:SM_S911B"* ]] || {
        echo "Refusing ADB inventory target that is not model SM_S911B." >&2
        return 1
    }
    state="$("$adb_bin" -s "$serial" get-state 2>/dev/null || true)"
    [[ "$state" == device ]] || return 1
    model="$("$adb_bin" -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    [[ "$model" == SM-S911B ]] || {
        echo "Refusing target model '$model'; expected 'SM-S911B'." >&2
        return 1
    }
}

remove_authorized_key() {
    [[ "$authorized_key_added" == true ]] || return 0
    local filtered
    filtered="$(mktemp "$(dirname "$authorized_keys")/.authorized_keys.terminal-spike.XXXXXX")"
    if [[ -f "$authorized_keys" ]]; then
        awk -v marker="$auth_marker" 'index($0, marker) == 0' "$authorized_keys" >"$filtered"
    fi
    chmod 0600 "$filtered"
    mv -f -- "$filtered" "$authorized_keys"
    authorized_key_added=false
}

cleanup() {
    local cleanup_failed=false
    remove_authorized_key || cleanup_failed=true
    if [[ -n "$private_workspace" && -d "$private_workspace" && \
        "$(dirname "$private_workspace")" == "$(realpath "${TMPDIR:-/tmp}")" && \
        "$(basename "$private_workspace")" == terminal-spike-real-codex.* ]]; then
        find "$private_workspace" -type f -exec shred -u -- {} + 2>/dev/null || cleanup_failed=true
        rmdir "$private_workspace" 2>/dev/null || cleanup_failed=true
    fi
    [[ "$cleanup_failed" == false ]]
}

on_exit() {
    local status=$?
    trap - EXIT
    if ! cleanup; then
        echo "Failed to revoke or remove all temporary real-Codex test material." >&2
        status=70
    fi
    exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

verify_old_phone
cd "$project_dir"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:stageRuntimeTestUtilities
for artifact in "$app_apk" "$test_apk" "$orchestrator_apk" "$test_services_apk"; do
    [[ -f "$artifact" ]] || { echo "Required APK is missing: $artifact" >&2; exit 3; }
done

mkdir -p "$output_dir"
output_dir="$(realpath "$output_dir")"
rm -f -- \
    "$output_dir/instrumentation-real-codex-tmux.txt" \
    "$output_dir/logcat-real-codex-tmux.txt" \
    "$output_dir/evidence-real-codex-tmux.txt" \
    "$output_dir/TEST-real-codex-tmux.xml"
private_workspace="$(mktemp -d "$(realpath "${TMPDIR:-/tmp}")/terminal-spike-real-codex.XXXXXX")"
chmod 0700 "$private_workspace"
ssh-keygen -q -t ed25519 -N '' -C "$auth_marker" -f "$private_workspace/id_ed25519"
public_key="$(<"$private_workspace/id_ed25519.pub")"
[[ "$public_key" == *" $auth_marker" ]] || { echo "Generated key marker is missing." >&2; exit 3; }
printf '%s\n' "$public_key" >>"$authorized_keys"
chmod 0600 "$authorized_keys"
authorized_key_added=true

ssh -F /dev/null -p "$host_port" -i "$private_workspace/id_ed25519" \
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null "$host_username@$host_address" \
    "command -v tmux >/dev/null && command -v less >/dev/null && command -v vim >/dev/null && command -v htop >/dev/null && test -x $quoted_node_bin && test -r $quoted_codex_bin" \
    >/dev/null 2>&1 || {
    echo "The temporary identity cannot reach tmux and Codex on the selected host." >&2
    exit 4
}

install_apk() {
    local apk="$1"
    local force_queryable="${2:-false}"
    local -a options=(-r)
    [[ "$force_queryable" == true ]] && options=(--force-queryable -r)
    verify_old_phone
    "$adb_bin" -s "$serial" install "${options[@]}" "$apk" >/dev/null
}
install_apk "$app_apk"
install_apk "$test_apk"
install_apk "$orchestrator_apk" true
install_apk "$test_services_apk" true

verify_old_phone
"$adb_bin" -s "$serial" shell "toybox nc -z -w 5 $host_address $host_port" >/dev/null || {
    echo "The old phone cannot reach the selected SSH host." >&2
    exit 4
}

private_key_base64="$(base64 -w 0 "$private_workspace/id_ed25519")"
codex_command="$quoted_node_bin $quoted_codex_bin --no-alt-screen -c check_for_update_on_startup=false --disable in_app_updates --ask-for-approval never --sandbox read-only"
codex_command_base64="$(printf '%s' "$codex_command" | base64 -w 0)"
ssh_class="com.yanjiyu.terminalspike.connection.SshRealEndToEndTest"
direct_test="realServerScrollsToFirstRowThroughProductionSessionAndComposeView"
tmux_test="appSelectedTmuxActualCodexFirstGestureUsesLocalPixelScroll"
mouse_app_test="appSelectedTmuxExplicitRemoteMouseScrollsRealLessVimAndHtop"
test_filter="$ssh_class#$direct_test,$ssh_class#$tmux_test,$ssh_class#$mouse_app_test"
instrumentation_target="com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"
orchestrator_target="androidx.test.orchestrator/.AndroidTestOrchestrator"
arguments=(
    -e class "$test_filter"
    -e sshE2eHost "$host_address"
    -e sshE2ePort "$host_port"
    -e sshE2eUsername "$host_username"
    -e sshE2ePrivateKeyBase64 "$private_key_base64"
    -e sshE2eCodexCommandBase64 "$codex_command_base64"
)
printf -v forwarded_arguments ' %q' "${arguments[@]}"
printf -v target_arguments ' %q' \
    -e targetInstrumentation "$instrumentation_target" \
    -e clearPackageData true "$orchestrator_target"
instrumentation_command='CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -w -r'
instrumentation_command+="$forwarded_arguments$target_arguments"
private_result="$private_workspace/instrumentation.txt"
public_result="$output_dir/instrumentation-real-codex-tmux.txt"
report="$output_dir/TEST-real-codex-tmux.xml"

verify_old_phone
"$adb_bin" -s "$serial" logcat -c >/dev/null 2>&1 || true
set +e
printf '%s\n' "$instrumentation_command" | \
    timeout --foreground "${TERMINAL_SPIKE_INSTRUMENTATION_TIMEOUT_SECONDS:-900}" \
        "$adb_bin" -s "$serial" shell sh >"$private_result" 2>&1
status=$?
set -e
python3 "$project_dir/scripts/sanitize-real-codex-instrumentation.py" \
    <"$private_result" >"$public_result"
verify_old_phone
private_logcat="$private_workspace/logcat.txt"
"$adb_bin" -s "$serial" logcat -d -s SshRealScrollE2E:I AndroidRuntime:E '*:S' \
    >"$private_logcat"
python3 "$project_dir/scripts/sanitize-real-codex-tmux-log.py" \
    <"$private_logcat" >"$output_dir/evidence-real-codex-tmux.txt"
unset private_key_base64 codex_command codex_command_base64 forwarded_arguments instrumentation_command

[[ $status -eq 0 ]] || { echo "Real-Codex tmux instrumentation failed." >&2; exit "$status"; }
grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[34]' "$public_result" && {
    echo "Real-Codex tmux instrumentation was skipped." >&2
    exit 5
}
for required_test in "$direct_test" "$tmux_test" "$mouse_app_test"; do
    grep -Fq "test=$required_test" "$public_result" || {
        echo "A required real-Codex test did not run: $required_test" >&2
        exit 5
    }
done
grep -Fq 'OK (3 tests)' "$public_result" || { echo "The exact three-test result was not green." >&2; exit 5; }
grep -Fq 'stage=complete subrow=pass fling=pass catch=pass' \
    "$output_dir/evidence-real-codex-tmux.txt" || {
    echo "The final real-Codex tmux checkpoint is missing." >&2
    exit 5
}
grep -Eq 'stage=reader_anchor autoFollow=false pixelDelta=-?0(\.0+)? fractional=true$' \
    "$output_dir/evidence-real-codex-tmux.txt" || {
    echo "The real-Codex reader-anchor checkpoint is missing or moved." >&2
    exit 5
}
grep -Fq 'stage=live_bottom autoFollow=true markerVisible=true' \
    "$output_dir/evidence-real-codex-tmux.txt" || {
    echo "The real-Codex live-bottom checkpoint is missing." >&2
    exit 5
}
grep -Eq 'stage=mouse_application app=less destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=[1-9][0-9]*$' \
    "$output_dir/evidence-real-codex-tmux.txt" || {
    echo "The real tmux mouse-application checkpoint is missing." >&2
    exit 5
}
grep -Eq 'stage=mouse_application app=vim destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=[1-9][0-9]*$' \
    "$output_dir/evidence-real-codex-tmux.txt" || {
    echo "The real tmux Vim mouse-application checkpoint is missing." >&2
    exit 5
}
grep -Eq 'stage=mouse_application app=htop destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=[1-9][0-9]* swipes=[1-9][0-9]*$' \
    "$output_dir/evidence-real-codex-tmux.txt" || {
    echo "The real tmux htop mouse-application checkpoint is missing." >&2
    exit 5
}
python3 "$project_dir/scripts/instrumentation-output-to-junit.py" "$public_result" "$report"
python3 "$project_dir/scripts/assert-test-report.py" "$report"
printf 'REAL_CODEX_TMUX_RESULT tests=3 failures=0 skips=0 direct=pass tmux=pass mouse_apps=less,vim,htop mouse_route=remote mouse_wheels=nonzero route=local wheels=0 paging=5000 subrow=pass fling=pass catch=pass reader_anchor=pass live_bottom=pass\n'
