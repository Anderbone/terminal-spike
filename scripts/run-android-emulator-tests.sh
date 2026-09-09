#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
api=""
suite=""
dry_run=false
output_dir="$project_dir/build/ci-android-results"
app_apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$project_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
private_key_file=""
requested_test_filter=""
orchestrator_apk="$project_dir/app/build/outputs/runtime-test-utils/orchestrator-1.6.1.apk"
test_services_apk="$project_dir/app/build/outputs/runtime-test-utils/test-services-1.6.0.apk"
test_contract="$project_dir/scripts/android-test-contract.json"

usage() {
    cat >&2 <<'EOF'
Usage: scripts/run-android-emulator-tests.sh --api <26-37> --suite <full|boundary|openssh|lan-openssh|backup-clean-install>
       [--app-apk <path>] [--test-apk <path>] [--output-dir <path>]
       [--orchestrator-apk <path>] [--test-services-apk <path>]
       [--private-key-file <path>] [--test-filter <class-or-class-list>] [--dry-run]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --api) api="${2:-}"; shift 2 ;;
        --suite) suite="${2:-}"; shift 2 ;;
        --app-apk) app_apk="${2:-}"; shift 2 ;;
        --test-apk) test_apk="${2:-}"; shift 2 ;;
        --orchestrator-apk) orchestrator_apk="${2:-}"; shift 2 ;;
        --test-services-apk) test_services_apk="${2:-}"; shift 2 ;;
        --output-dir) output_dir="${2:-}"; shift 2 ;;
        --private-key-file) private_key_file="${2:-}"; shift 2 ;;
        --test-filter) requested_test_filter="${2:-}"; shift 2 ;;
        --dry-run) dry_run=true; shift ;;
        *) usage; exit 2 ;;
    esac
done

if [[ ! "$api" =~ ^(26|27|28|29|30|31|32|33|34|35|36|37)$ ]]; then
    echo "API must be one stable supported level from 26 through 37." >&2
    exit 2
fi
case "$suite" in
    full) test_filter="" ;;
    boundary)
        test_filter="com.yanjiyu.terminalspike.AppLockRuntimeTest,com.yanjiyu.terminalspike.LocalNetworkPermissionRuntimeTest,com.yanjiyu.terminalspike.MainActivityLaunchModeTest,com.yanjiyu.terminalspike.SessionForegroundServiceContractTest,com.yanjiyu.terminalspike.SessionNotificationActionTest,com.yanjiyu.terminalspike.connection.Ed25519AndroidCryptoTest,com.yanjiyu.terminalspike.connection.mosh.MoshExtensionClientInstrumentedTest,com.yanjiyu.terminalspike.core.data.repository.RoomTerminalDataPersistenceTest,com.yanjiyu.terminalspike.terminal.view.TerminalClipboardWriterTest,com.yanjiyu.terminalspike.terminal.view.TerminalTypefaceRegistryTest"
        ;;
    openssh)
        [[ "$api" == "35" ]] || { echo "OpenSSH E2E is pinned to API 35." >&2; exit 2; }
        test_filter="com.yanjiyu.terminalspike.connection.SshRealEndToEndTest#importedPrivateKeySelectedBySavedHostAuthenticatesToRealServer,com.yanjiyu.terminalspike.connection.SshRealEndToEndTest#changedSavedHostKeyIsBlockedByRealServer,com.yanjiyu.terminalspike.connection.SshRealEndToEndTest#realServerCarriesInteractiveBytesWhileMoshExtensionIsAbsent,com.yanjiyu.terminalspike.connection.SftpRealEndToEndTest"
        ;;
    lan-openssh)
        [[ "$api" == "37" ]] || { echo "LAN OpenSSH E2E is pinned to API 37." >&2; exit 2; }
        test_filter="com.yanjiyu.terminalspike.connection.LocalNetworkSocketRuntimeTest"
        ;;
    backup-clean-install)
        [[ "$api" == "35" ]] || { echo "Clean-install backup E2E is pinned to API 35." >&2; exit 2; }
        test_filter="com.yanjiyu.terminalspike.backup.BackupDocumentsProviderIntegrationTest"
        ;;
    *) echo "Suite must be full, boundary, openssh, lan-openssh, or backup-clean-install." >&2; exit 2 ;;
esac
if [[ -n "$requested_test_filter" ]]; then
    [[ "$suite" != "openssh" && "$suite" != "lan-openssh" && "$suite" != "backup-clean-install" ]] || {
        echo "Real E2E suites use fixed required test lists." >&2
        exit 2
    }
    [[ "$requested_test_filter" =~ ^[A-Za-z0-9_.,#$]+$ ]] || {
        echo "Test filter contains unsupported characters." >&2
        exit 2
    }
    test_filter="$requested_test_filter"
fi

avd_name="terminal-spike-api${api}-${suite}"
platform_version="$api"
if [[ "$api" == "37" ]]; then
    platform_version="37.0"
fi
system_image="system-images;android-${platform_version};google_apis;x86_64"
if [[ "$dry_run" == true ]]; then
    isolation="orchestrator"
    [[ "$suite" == "backup-clean-install" ]] && isolation="uninstall-reinstall"
    printf 'EMULATOR_PLAN api=%s avd=%s suite=%s image=%s filter=%s isolation=%s\n' \
        "$api" "$avd_name" "$suite" "$system_image" "${test_filter:-<all>}" "$isolation"
    exit 0
fi

[[ -f "$app_apk" ]] || { echo "App APK is missing: $app_apk" >&2; exit 3; }
[[ -f "$test_apk" ]] || { echo "Test APK is missing: $test_apk" >&2; exit 3; }
[[ -f "$orchestrator_apk" ]] || { echo "Orchestrator APK is missing: $orchestrator_apk" >&2; exit 3; }
[[ -f "$test_services_apk" ]] || { echo "Test Services APK is missing: $test_services_apk" >&2; exit 3; }
if [[ "$suite" == "openssh" && ! -f "$private_key_file" ]]; then
    echo "OpenSSH E2E requires a private-key file." >&2
    exit 3
fi
if [[ "$suite" != "openssh" && -z "$requested_test_filter" && ! -f "$test_contract" ]]; then
    echo "Android test contract is missing: $test_contract" >&2
    exit 3
fi
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" ]] || { echo "ANDROID_SDK_ROOT is required." >&2; exit 3; }
android_tools_dir="$sdk_root/cmdline-tools/22.0/bin"
[[ -d "$android_tools_dir" ]] || android_tools_dir="$sdk_root/cmdline-tools/latest/bin"
android_cli_bin="$android_tools_dir/android"
sdkmanager_bin="$android_tools_dir/sdkmanager"
avdmanager_bin="$android_tools_dir/avdmanager"
emulator_bin="${TERMINAL_SPIKE_EMULATOR_BIN:-$sdk_root/emulator/emulator}"
adb_bin="$sdk_root/platform-tools/adb"
for required_tool in "$avdmanager_bin" "$emulator_bin" "$adb_bin"; do
    [[ -x "$required_tool" ]] || { echo "Required Android SDK tool is missing: $required_tool" >&2; exit 3; }
done
if [[ ! -x "$android_cli_bin" && ! -x "$sdkmanager_bin" ]]; then
    echo "Neither Android SDK installer is available under: $android_tools_dir" >&2
    exit 3
fi
serial="emulator-${TERMINAL_SPIKE_EMULATOR_PORT:-5554}"
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
    echo "Resolved emulator serial is invalid." >&2
    exit 3
fi
emulator_memory_mb="${TERMINAL_SPIKE_EMULATOR_MEMORY_MB:-4096}"
if [[ ! "$emulator_memory_mb" =~ ^[0-9]+$ ]] ||
    ((emulator_memory_mb < 2048 || emulator_memory_mb > 8192)); then
    echo "TERMINAL_SPIKE_EMULATOR_MEMORY_MB must be an integer from 2048 through 8192." >&2
    exit 3
fi

mkdir -p "$output_dir"
output_dir="$(realpath "$output_dir")"
task_temp_root="$(realpath "${TMPDIR:-/tmp}")"
avd_workspace="$(mktemp -d "$task_temp_root/terminal-spike-avd.XXXXXX")"
export ANDROID_AVD_HOME="$avd_workspace/avd"
mkdir -p "$ANDROID_AVD_HOME"
emulator_pid=""

cleanup() {
    if [[ -n "$emulator_pid" ]]; then
        # A stalled console or emulator must not keep a completed test job alive.
        timeout --kill-after=1 5 "$adb_bin" -s "$serial" emu kill >/dev/null 2>&1 || true
        kill "$emulator_pid" >/dev/null 2>&1 || true
        for ((shutdown_attempt = 0; shutdown_attempt < 5; shutdown_attempt++)); do
            kill -0 "$emulator_pid" 2>/dev/null || break
            sleep 1
        done
        kill -KILL "$emulator_pid" >/dev/null 2>&1 || true
        wait "$emulator_pid" >/dev/null 2>&1 || true
    fi
    if [[ -n "$avd_workspace" && -d "$avd_workspace" && \
        "$(dirname "$avd_workspace")" == "$task_temp_root" && \
        "$(basename "$avd_workspace")" == terminal-spike-avd.* ]]; then
        rm -rf -- "$avd_workspace"
    fi
}
trap cleanup EXIT INT TERM

sdk_install_log="$avd_workspace/sdk-install.txt"
# Accept only the selected stable image's license. API 32 still references the SDK's
# legacy preview-license identifier; unattended installers otherwise skip it.
if [[ -x "$android_cli_bin" ]]; then
    if ! printf 'y\n' | "$android_cli_bin" sdk install "$system_image" >"$sdk_install_log" 2>&1; then
        echo "Android system-image installation failed:" >&2
        sed -n '1,120p' "$sdk_install_log" >&2
        exit 4
    fi
elif ! printf 'y\n' | "$sdkmanager_bin" "$system_image" >"$sdk_install_log" 2>&1; then
    echo "Android system-image installation failed:" >&2
    sed -n '1,120p' "$sdk_install_log" >&2
    exit 4
fi
avd_create_log="$avd_workspace/avd-create.txt"
if ! printf 'no\n' | "$avdmanager_bin" create avd --force --name "$avd_name" \
    --package "$system_image" --device pixel_6 >"$avd_create_log" 2>&1; then
    echo "Android Virtual Device creation failed:" >&2
    sed -n '1,120p' "$avd_create_log" >&2
    exit 4
fi
# Old command-line tools parse Android 17's fractional API metadata as zero,
# producing android-0 and making the emulator choose API 3 graphics defaults.
avd_target=$(sed -n 's/^target[[:space:]]*=[[:space:]]*//p' "$ANDROID_AVD_HOME/$avd_name.ini" | tr -d '\r')
if [[ "$avd_target" != "android-$platform_version" && "$avd_target" != "android-$api" ]]; then
    echo "AVD target does not match requested API $api: $avd_target. Install cmdline-tools;22.0." >&2
    exit 4
fi
# CI text entry uses semantics and Espresso actions. A hardware keyboard prevents the old API 26
# software IME from repeatedly resizing Compose dialogs; real IME behavior remains device-tested.
printf '\nhw.keyboard=yes\n' >>"$ANDROID_AVD_HOME/$avd_name.avd/config.ini"
graphics_options=(-gpu swiftshader)
if [[ "$api" == "37" ]]; then
    # API 37's mapper rejects the host ReadColorBufferDMA path, crashing
    # SurfaceFlinger even with software host graphics. Guest ANGLE uses Vulkan
    # buffers instead. Explicit Vulkan keeps this software rendering path deterministic.
    graphics_options=(-gpu software -feature Vulkan -feature GuestAngle)
fi
"$emulator_bin" -avd "$avd_name" -port "${serial#emulator-}" -no-window -no-audio \
    -no-boot-anim "${graphics_options[@]}" -memory "$emulator_memory_mb" -partition-size 4096 \
    -wipe-data -no-snapshot -no-metrics &
emulator_pid=$!

booted=false
for ((attempt = 1; attempt <= ${TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS:-180}; attempt++)); do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "Emulator exited before boot completed." >&2
        exit 4
    fi
    if [[ "$("$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        booted=true
        break
    fi
    sleep "${TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS:-2}"
done
[[ "$booted" == true ]] || { echo "Timed out waiting for emulator boot." >&2; exit 4; }
[[ "$("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "Refusing non-emulator ADB target: $serial" >&2
    exit 4
}
observed_avd_name="$("$adb_bin" -s "$serial" emu avd name | tr -d '\r' | head -n 1)"
[[ "$observed_avd_name" == "$avd_name" ]] || {
    echo "Refusing emulator whose AVD identity does not match $avd_name." >&2
    exit 4
}

package_manager_ready=false
for ((attempt = 1; attempt <= ${TERMINAL_SPIKE_PACKAGE_MANAGER_ATTEMPTS:-60}; attempt++)); do
    if "$adb_bin" -s "$serial" shell pm path android 2>/dev/null | grep -q '^package:'; then
        package_manager_ready=true
        break
    fi
    sleep "${TERMINAL_SPIKE_PACKAGE_MANAGER_POLL_SECONDS:-1}"
done
[[ "$package_manager_ready" == true ]] || {
    echo "Timed out waiting for the emulator package manager." >&2
    exit 4
}

for animation in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb_bin" -s "$serial" shell settings put global "$animation" 0
done
"$adb_bin" -s "$serial" shell settings put secure show_ime_with_hard_keyboard 0
install_apk() {
    local apk="$1"
    shift
    for ((attempt = 1; attempt <= ${TERMINAL_SPIKE_INSTALL_ATTEMPTS:-5}; attempt++)); do
        if "$adb_bin" -s "$serial" install "$@" "$apk" >/dev/null; then
            return 0
        fi
        sleep "${TERMINAL_SPIKE_INSTALL_POLL_SECONDS:-2}"
    done
    echo "Failed to install APK after bounded retries: $apk" >&2
    return 1
}

install_apk "$app_apk" -r
install_apk "$test_apk" -r
install_options=(-r)
if ((api >= 30)); then
    install_options=(--force-queryable -r)
fi
install_apk "$orchestrator_apk" "${install_options[@]}"
install_apk "$test_services_apk" "${install_options[@]}"
# Some old system images reject clearing one or more log buffers. This AVD was just created with
# -wipe-data, so a clear failure is harmless and must not suppress the runtime tests themselves.
"$adb_bin" -s "$serial" logcat -c >/dev/null 2>&1 || true

if [[ "$suite" == "backup-clean-install" ]]; then
    app_package="com.yanjiyu.terminalspike"
    test_package="com.yanjiyu.terminalspike.test"
    instrumentation_target="$test_package/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"
    backup_class="com.yanjiyu.terminalspike.backup.BackupDocumentsProviderIntegrationTest"
    backup_argument="terminalSpikeBackupCleanInstallStage"
    standard_document="clean-install-standard.tsbak"
    full_document="clean-install-full.tsbak"
    archive_dir="$avd_workspace/encrypted-backup-archives"
    mkdir -p "$archive_dir"

    run_backup_stage() {
        local stage="$1"
        local method="$2"
        local private_output="$avd_workspace/backup-$stage-private.txt"
        local public_output="$output_dir/instrumentation-api${api}-${suite}-${stage}.txt"
        set +e
        timeout --foreground "${TERMINAL_SPIKE_INSTRUMENTATION_TIMEOUT_SECONDS:-1800}" \
            "$adb_bin" -s "$serial" shell am instrument -w -r \
            -e class "$backup_class#$method" \
            -e "$backup_argument" "$stage" \
            "$instrumentation_target" >"$private_output" 2>&1
        local status=$?
        set -e
        python3 "$project_dir/scripts/redact-android-test-log.py" \
            <"$private_output" >"$public_output"
        [[ $status -eq 0 ]] || {
            echo "Clean-install backup $stage instrumentation failed." >&2
            return "$status"
        }
        grep -Fq "test=$method" "$public_output" || {
            echo "Clean-install backup $stage method did not run." >&2
            return 5
        }
        if grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[0-9]+' "$public_output"; then
            echo "Clean-install backup $stage method failed or skipped." >&2
            return 5
        fi
        [[ "$(grep -Fc 'INSTRUMENTATION_STATUS_CODE: 0' "$public_output")" == "1" ]] || {
            echo "Clean-install backup $stage did not have one successful result." >&2
            return 5
        }
        python3 "$project_dir/scripts/instrumentation-output-to-junit.py" \
            "$public_output" "$output_dir/TEST-api${api}-${suite}-${stage}.xml"
    }

    run_backup_stage export exportStandardAndFullArchivesForCleanInstall
    for document in "$standard_document" "$full_document"; do
        archive="$archive_dir/$document"
        "$adb_bin" -s "$serial" exec-out run-as "$app_package" cat \
            "files/backup-documents/$document" >"$archive"
        [[ -s "$archive" ]] || { echo "Exported $document is empty." >&2; exit 5; }
        if LC_ALL=C grep -aFq 'clean-install-portable-secret-4271' "$archive"; then
            echo "Exported $document leaked the portable credential plaintext." >&2
            exit 5
        fi
    done
    standard_sha="$(sha256sum "$archive_dir/$standard_document" | cut -d' ' -f1)"
    full_sha="$(sha256sum "$archive_dir/$full_document" | cut -d' ' -f1)"

    reset_app_install() {
        "$adb_bin" -s "$serial" uninstall "$app_package" >/dev/null
        "$adb_bin" -s "$serial" uninstall "$test_package" >/dev/null 2>&1 || true
        if "$adb_bin" -s "$serial" shell pm path "$app_package" | grep -q '^package:'; then
            echo "The previous app package remained installed after uninstall." >&2
            exit 5
        fi
        install_apk "$app_apk"
        install_apk "$test_apk"
        "$adb_bin" -s "$serial" shell run-as "$app_package" mkdir -p files/backup-documents
    }

    copy_archive_to_clean_install() {
        local document="$1"
        temporary_device_path="/data/local/tmp/terminal-spike-$document"
        "$adb_bin" -s "$serial" push "$archive_dir/$document" "$temporary_device_path" >/dev/null
        "$adb_bin" -s "$serial" shell chmod 0644 "$temporary_device_path"
        "$adb_bin" -s "$serial" shell run-as "$app_package" cp \
            "$temporary_device_path" "files/backup-documents/$document"
        "$adb_bin" -s "$serial" shell rm -f "$temporary_device_path"
        expected_sha="$(sha256sum "$archive_dir/$document" | cut -d' ' -f1)"
        restored_sha="$("$adb_bin" -s "$serial" exec-out run-as "$app_package" cat \
            "files/backup-documents/$document" | sha256sum | cut -d' ' -f1)"
        [[ "$restored_sha" == "$expected_sha" ]] || {
            echo "Restored $document differs from the exported archive." >&2
            exit 5
        }
    }

    reset_app_install
    copy_archive_to_clean_install "$standard_document"
    run_backup_stage restore-standard restoreStandardArchiveAfterCleanInstall
    reset_app_install
    copy_archive_to_clean_install "$full_document"
    run_backup_stage restore-full restoreFullArchiveAfterCleanInstall
    "$adb_bin" -s "$serial" logcat -d | \
        python3 "$project_dir/scripts/redact-android-test-log.py" \
        >"$output_dir/logcat-api${api}-${suite}.txt"
    printf 'BACKUP_CLEAN_INSTALL_E2E stage=export status=pass standard_sha256=%s full_sha256=%s\n' \
        "$standard_sha" "$full_sha" | tee "$output_dir/backup-clean-install-api${api}.txt"
    printf 'BACKUP_CLEAN_INSTALL_E2E stage=uninstall-reinstall status=pass boundaries=2 old_private_state=absent\n' | \
        tee -a "$output_dir/backup-clean-install-api${api}.txt"
    printf 'BACKUP_CLEAN_INSTALL_E2E stage=restore status=pass modes=standard,full independent_clean_installs=true portable_secret=restored\n' | \
        tee -a "$output_dir/backup-clean-install-api${api}.txt"
    exit 0
fi

instrumentation_arguments=()
if [[ -n "$test_filter" ]]; then
    instrumentation_arguments+=(-e class "$test_filter")
fi
if [[ "$suite" == "openssh" ]]; then
    # Route the host fixture over ADB instead of depending on the emulator's guest NAT being
    # ready immediately after boot. This remains loopback-only inside Android and is deterministic
    # on both local and GitHub-hosted emulators.
    "$adb_bin" -s "$serial" reverse tcp:22222 tcp:22222
    private_key_base64="$(base64 -w 0 "$private_key_file")"
    instrumentation_arguments+=(
        -e sshE2eHost 127.0.0.1
        -e sshE2ePort 22222
        -e sshE2eUsername terminal
        -e sshE2ePassword terminal-spike-test-only
        -e sshE2ePrivateKeyBase64 "$private_key_base64"
        -e sshE2eRequireMoshAbsent true
        -e terminalSpikeRunRealSftp true
        -e terminalSpikeSftpHost 127.0.0.1
        -e terminalSpikeSftpPort 22222
    )
elif [[ "$suite" == "lan-openssh" ]]; then
    instrumentation_arguments+=(
        -e terminalSpikeRunApi37Lan true
        -e terminalSpikeLanHost 10.0.2.2
        -e terminalSpikeLanPort 22222
        -e terminalSpikeLanUsername terminal
        -e terminalSpikeLanPassword terminal-spike-test-only
    )
fi
instrumentation_target="com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"
orchestrator_target="androidx.test.orchestrator/.AndroidTestOrchestrator"
forwarded_arguments=""
if ((${#instrumentation_arguments[@]} > 0)); then
    printf -v forwarded_arguments ' %q' "${instrumentation_arguments[@]}"
fi
printf -v target_arguments ' %q' \
    -e targetInstrumentation "$instrumentation_target" \
    -e clearPackageData true "$orchestrator_target"
instrumentation_command='CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -w -r'
instrumentation_command+="$forwarded_arguments$target_arguments"

private_result="$avd_workspace/instrumentation-private.txt"
raw_result="$output_dir/instrumentation-api${api}-${suite}.txt"
: >"$private_result"
set +e
timeout --foreground "${TERMINAL_SPIKE_INSTRUMENTATION_TIMEOUT_SECONDS:-1800}" \
    "$adb_bin" -s "$serial" shell "$instrumentation_command" >"$private_result" 2>&1
instrumentation_status=$?
set -e
unset private_key_base64 2>/dev/null || true
python3 "$project_dir/scripts/redact-android-test-log.py" <"$private_result" >"$raw_result"
"$adb_bin" -s "$serial" logcat -d | \
    python3 "$project_dir/scripts/redact-android-test-log.py" \
    >"$output_dir/logcat-api${api}-${suite}.txt"
[[ $instrumentation_status -eq 0 ]] || {
    echo "Android instrumentation command failed." >&2
    exit "$instrumentation_status"
}

if [[ "$suite" == "openssh" ]]; then
    if grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[34]' "$raw_result"; then
        echo "OpenSSH instrumentation contained an assumption skip." >&2
        exit 5
    fi
    for required_test in \
        importedPrivateKeySelectedBySavedHostAuthenticatesToRealServer \
        changedSavedHostKeyIsBlockedByRealServer \
        realServerCarriesInteractiveBytesWhileMoshExtensionIsAbsent \
        liveSshTransportUploadsAPastedImageThroughItsSideChannel \
        createsTransfersCopiesMovesDownloadsAndDeletesAgainstOpenSsh; do
        grep -Fq "test=$required_test" "$raw_result" || {
            echo "Required OpenSSH test did not run: $required_test" >&2
            exit 5
        }
    done
elif [[ "$suite" == "lan-openssh" ]]; then
    if grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[34]' "$raw_result"; then
        echo "LAN OpenSSH instrumentation contained an assumption skip." >&2
        exit 5
    fi
    for required_test in \
        deniedPermissionBlocksRawTcpAndProductionSshBeforeProtocolTraffic \
        grantedPermissionCompletesProductionSshAndSftpAgainstTheSameLanServer; do
        grep -Fq "test=$required_test" "$raw_result" || {
            echo "Required LAN OpenSSH test did not run: $required_test" >&2
            exit 5
        }
    done
fi

python3 "$project_dir/scripts/instrumentation-output-to-junit.py" \
    "$raw_result" "$output_dir/TEST-api${api}-${suite}.xml"
if [[ "$suite" != "openssh" && "$suite" != "lan-openssh" && -z "$requested_test_filter" ]]; then
    python3 "$project_dir/scripts/verify-android-test-contract.py" \
        "$test_contract" "$raw_result" "$suite" "$api"
fi

if [[ "$api" == "37" && ( "$suite" == "full" || "$suite" == "boundary" ) && -z "$requested_test_filter" ]]; then
    revocation_result="$output_dir/local-network-revocation-api37-${suite}.txt"
    : >"$revocation_result"
    local_network_permission="android.permission.ACCESS_LOCAL_NETWORK"
    app_package="com.yanjiyu.terminalspike"
    app_activity="$app_package/.MainActivity"
    log_revocation_stage() {
        printf 'LOCAL_NETWORK_REVOCATION_E2E stage=%s status=pass\n' "$1" | tee -a "$revocation_result"
    }
    permission_granted() {
        "$adb_bin" -s "$serial" shell dumpsys package "$app_package" | \
            grep -Fq "$local_network_permission: granted=true"
    }
    permission_denied() {
        ! permission_granted
    }
    wait_for_revocation_condition() {
        local label="$1"
        shift
        for ((attempt = 1; attempt <= ${TERMINAL_SPIKE_REVOCATION_POLL_ATTEMPTS:-120}; attempt++)); do
            if "$@"; then return 0; fi
            sleep "${TERMINAL_SPIKE_REVOCATION_POLL_SECONDS:-0.1}"
        done
        echo "Timed out waiting for API 37 local-network $label." >&2
        return 1
    }

    "$adb_bin" -s "$serial" shell pm grant "$app_package" "$local_network_permission"
    wait_for_revocation_condition "grant" permission_granted
    log_revocation_stage grant
    "$adb_bin" -s "$serial" shell am start -W -n "$app_activity" >/dev/null
    original_pid="$("$adb_bin" -s "$serial" shell pidof "$app_package" | tr -d '\r')"
    [[ "$original_pid" =~ ^[0-9]+$ ]] || {
        echo "API 37 revocation gate could not resolve the original app process." >&2
        exit 6
    }
    log_revocation_stage running

    "$adb_bin" -s "$serial" shell pm revoke "$app_package" "$local_network_permission"
    process_was_terminated() {
        local current_pid
        current_pid="$("$adb_bin" -s "$serial" shell pidof "$app_package" | tr -d '\r')"
        [[ -z "$current_pid" || "$current_pid" != "$original_pid" ]]
    }
    wait_for_revocation_condition "process termination" process_was_terminated
    wait_for_revocation_condition "denial state" permission_denied
    log_revocation_stage revoked

    "$adb_bin" -s "$serial" shell am start -W -n "$app_activity" >/dev/null
    main_activity_resumed() {
        "$adb_bin" -s "$serial" shell dumpsys activity activities | \
            grep -Eq "(mResumedActivity|topResumedActivity).*${app_package}/.MainActivity"
    }
    wait_for_revocation_condition "relaunch" main_activity_resumed
    permission_denied || {
        echo "API 37 local-network permission unexpectedly returned after relaunch." >&2
        exit 6
    }
    log_revocation_stage relaunched-denied
fi
