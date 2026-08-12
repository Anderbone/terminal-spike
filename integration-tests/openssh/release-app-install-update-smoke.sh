#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    printf 'Usage: %s <emulator-serial> <initial-apk> [update-apk]\n' "$0" >&2
    exit 64
fi

serial=$1
initial_apk=$2
update_apk=${3:-$2}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/jiyu/Android/Sdk}}
adb_bin=${ADB:-$sdk_root/platform-tools/adb}
apksigner_bin=${APKSIGNER:-}
fixture_port=${TERMINAL_SPIKE_SSH_PORT:-22222}
fixture_user=${TERMINAL_SPIKE_SSH_USER:-terminal}
fixture_password=${TERMINAL_SPIKE_SSH_PASSWORD:-terminal-spike-test-only}
app_package=com.yanjiyu.terminalspike
extension_package=com.yanjiyu.terminalspike.mosh
activity_component=$app_package/.MainActivity
expected_avd=terminal-spike-release-test

log_stage() {
    printf 'RELEASE_APP_UPDATE_SMOKE stage=%s status=%s\n' "$1" "$2"
}

if [ ! -x "$adb_bin" ]; then
    printf 'adb is unavailable at %s\n' "$adb_bin" >&2
    exit 1
fi
for apk in "$initial_apk" "$update_apk"; do
    if [ ! -f "$apk" ]; then
        printf 'APK does not exist: %s\n' "$apk" >&2
        exit 1
    fi
done

if [ -z "$apksigner_bin" ]; then
    build_tools=$sdk_root/build-tools
    apksigner_bin=$(find "$build_tools" -mindepth 2 -maxdepth 2 -type f -name apksigner \
        -print 2>/dev/null | sort -V | tail -n 1)
fi
if [ ! -x "$apksigner_bin" ]; then
    printf 'apksigner is unavailable; signature continuity cannot be proved.\n' >&2
    exit 1
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    PATH=$JAVA_HOME/bin:$PATH
    export PATH
elif ! command -v java >/dev/null 2>&1; then
    gradle_jdks=${GRADLE_USER_HOME:-${HOME:-}/.gradle}/jdks
    java_bin=$(find "$gradle_jdks" -mindepth 3 -maxdepth 3 -type f -path '*/bin/java' \
        -perm -u+x -print 2>/dev/null | sort -V | head -n 1)
    if [ -z "$java_bin" ]; then
        printf 'java is unavailable; set JAVA_HOME before running the release smoke.\n' >&2
        exit 1
    fi
    JAVA_HOME=$(CDPATH= cd -- "$(dirname -- "$java_bin")/.." && pwd)
    PATH=$JAVA_HOME/bin:$PATH
    export JAVA_HOME PATH
fi

adb() {
    "$adb_bin" -s "$serial" "$@"
}

device_state=$($adb_bin devices | awk -v wanted="$serial" '$1 == wanted { print $2 }')
if [ "$device_state" != device ]; then
    printf 'Target %s is not a connected authorized device.\n' "$serial" >&2
    exit 1
fi
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
    printf 'Refusing destructive release smoke on a non-emulator target: %s\n' "$serial" >&2
    exit 1
fi
avd_name=$(adb emu avd name 2>/dev/null | sed -n '1p' | tr -d '\r')
if [ "$avd_name" != "$expected_avd" ]; then
    printf 'Refusing destructive release smoke on unexpected AVD %s (wanted %s).\n' \
        "$avd_name" "$expected_avd" >&2
    exit 1
fi

initial_cert=$($apksigner_bin verify --print-certs "$initial_apk" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)
update_cert=$($apksigner_bin verify --print-certs "$update_apk" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)
if [ -z "$initial_cert" ] || [ "$initial_cert" != "$update_cert" ]; then
    printf 'Initial and update APKs do not have the same signing certificate.\n' >&2
    exit 1
fi
log_stage preflight pass

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/terminal-spike-release-smoke.XXXXXX")
ui_xml=$temporary_dir/window.xml
cleanup() {
    rm -rf -- "$temporary_dir"
}
trap cleanup EXIT HUP INT TERM

dump_ui() {
    adb shell uiautomator dump --compressed /sdcard/terminal-spike-window.xml >/dev/null
    adb exec-out cat /sdcard/terminal-spike-window.xml >"$ui_xml"
}

node_center() {
    attribute=$1
    value=$2
    occurrence=${3:-0}
    class_name=${4:-}
    python3 - "$ui_xml" "$attribute" "$value" "$occurrence" "$class_name" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, attribute, value, occurrence, class_name = sys.argv[1:]
matches = []
for node in ET.parse(path).iter("node"):
    if node.attrib.get(attribute, "") != value:
        continue
    if class_name and node.attrib.get("class", "") != class_name:
        continue
    bounds = node.attrib.get("bounds", "")
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if match:
        left, top, right, bottom = map(int, match.groups())
        matches.append(((left + right) // 2, (top + bottom) // 2))
index = int(occurrence)
if index < len(matches):
    print(*matches[index])
PY
}

wait_for_node() {
    attribute=$1
    value=$2
    occurrence=${3:-0}
    class_name=${4:-}
    attempt=0
    while [ "$attempt" -lt 80 ]; do
        dump_ui
        center=$(node_center "$attribute" "$value" "$occurrence" "$class_name")
        if [ -n "$center" ]; then
            printf '%s\n' "$center"
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 0.25
    done
    printf 'Timed out waiting for UI node %s=%s.\n' "$attribute" "$value" >&2
    return 1
}

tap_node() {
    center=$(wait_for_node "$@")
    # shellcheck disable=SC2086
    adb shell input tap $center
}

wait_for_ui_text() {
    expected=$1
    attempt=0
    while [ "$attempt" -lt 120 ]; do
        dump_ui
        if grep -Fq -- "$expected" "$ui_xml"; then
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 0.25
    done
    printf 'Timed out waiting for privacy-safe UI marker: %s\n' "$expected" >&2
    return 1
}

dismiss_notification_rationale_from_dump() {
    if ! grep -Fq -- "Show background session notifications?" "$ui_xml"; then
        return 1
    fi
    center=$(node_center text "Not now" 0 android.widget.TextView)
    if [ -z "$center" ]; then
        return 1
    fi
    # shellcheck disable=SC2086
    adb shell input tap $center
    log_stage notification_rationale dismissed
}

wait_for_terminal_ready() {
    attempt=0
    terminal_observations=0
    while [ "$attempt" -lt 120 ]; do
        dump_ui
        if dismiss_notification_rationale_from_dump; then
            wait_for_ui_text "Terminal"
            return 0
        fi
        if grep -Fq -- "Terminal" "$ui_xml"; then
            terminal_observations=$((terminal_observations + 1))
            if [ "$terminal_observations" -ge 2 ]; then
                return 0
            fi
        else
            terminal_observations=0
        fi
        attempt=$((attempt + 1))
        sleep 0.25
    done
    printf 'Timed out waiting for the terminal or notification rationale.\n' >&2
    return 1
}

replace_edit_text() {
    occurrence=$1
    value=$2
    center=$(wait_for_node class android.widget.EditText "$occurrence" android.widget.EditText)
    # shellcheck disable=SC2086
    adb shell input tap $center
    adb shell input keyevent KEYCODE_MOVE_END
    delete_count=0
    while [ "$delete_count" -lt 128 ]; do
        adb shell input keyevent KEYCODE_DEL
        delete_count=$((delete_count + 1))
    done
    adb shell input text "$value"
    adb shell input keyevent KEYCODE_BACK
    sleep 0.25
}

launch_app() {
    adb shell am force-stop "$app_package"
    adb shell am start -W -n "$activity_component" >/dev/null
    wait_for_ui_text "Workspace"
}

connect_new_saved_host() {
    tap_node text "New connection" 0 android.widget.TextView
    wait_for_ui_text "New SSH session"
    replace_edit_text 0 127.0.0.1
    replace_edit_text 1 "$fixture_user"
    replace_edit_text 2 "$fixture_port"
    replace_edit_text 3 "$fixture_password"
    tap_node text "Save host details" 0 android.widget.TextView
    tap_node text "Connect" 0 android.widget.TextView
    wait_for_ui_text "Trust once"
    tap_node text "Trust and save" 0 android.widget.TextView
    wait_for_terminal_ready
}

send_marker() {
    marker=$1
    # Dismissing the first-session rationale returns to the terminal without restoring focus.
    # Focus the single native renderer before injecting the fixed smoke command.
    tap_node content-desc "Native terminal renderer" 0 android.widget.EditText
    adb shell input text "printf%s$marker"
    adb shell input keyevent KEYCODE_ENTER
}

send_marker_and_verify() {
    marker=$1
    wait_for_terminal_ready
    send_marker "$marker"
    attempt=0
    while [ "$attempt" -lt 120 ]; do
        dump_ui
        if grep -Fq -- "$marker" "$ui_xml"; then
            return 0
        fi
        # The rationale can be composed after the first terminal frames. If it arrives at the
        # input boundary, dismiss it and resend the privacy-safe marker that it may have consumed.
        if dismiss_notification_rationale_from_dump; then
            wait_for_ui_text "Terminal"
            send_marker "$marker"
        fi
        attempt=$((attempt + 1))
        sleep 0.25
    done
    printf 'Timed out waiting for a privacy-safe terminal marker.\n' >&2
    return 1
}

reconnect_saved_host() {
    wait_for_ui_text "Saved hosts"
    # The saved card uses its display name, while its unique action remains stable.
    tap_node text "Connect" 0 android.widget.TextView
    # Password storage is deliberately off; the saved non-secret host must reopen the existing
    # authentication prompt after update rather than silently retaining a transient password.
    wait_for_ui_text "Password"
    replace_edit_text 0 "$fixture_password"
    tap_node text "Connect" 0 android.widget.TextView
    wait_for_terminal_ready
}

log_stage fixture start
"$script_dir/smoke.sh" >/dev/null
log_stage fixture pass
adb reverse "tcp:$fixture_port" "tcp:$fixture_port" >/dev/null
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
if adb shell pm list packages "$extension_package" | grep -Fq "$extension_package"; then
    printf 'Mosh extension is installed; extension-absent SSH proof is invalid.\n' >&2
    exit 1
fi

# Destructive operations are guarded above by the exact disposable AVD name.
log_stage initial_install start
adb uninstall "$app_package" >/dev/null 2>&1 || true
adb install "$initial_apk" >/dev/null
log_stage initial_install pass
log_stage initial_connection start
launch_app
connect_new_saved_host
send_marker_and_verify RELEASE_SMOKE_BEFORE_42
log_stage initial_connection pass

log_stage update_install start
adb install -r "$update_apk" >/dev/null
log_stage update_install pass
log_stage preserved_connection start
launch_app
reconnect_saved_host
send_marker_and_verify RELEASE_SMOKE_AFTER_42
log_stage preserved_connection pass

version_name=$(adb shell dumpsys package "$app_package" \
    | sed -n 's/.*versionName=//p' | head -n 1 | tr -d '\r')
printf 'RELEASE_APP_UPDATE_SMOKE serial=%s avd=%s version=%s ssh_before=pass ssh_after=pass data=preserved extension=absent certificate=%s\n' \
    "$serial" "$avd_name" "$version_name" "$initial_cert"
