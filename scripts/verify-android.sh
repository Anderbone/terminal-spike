#!/usr/bin/env bash
# The same verification entry point locally and in GitHub Actions.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mode="${1:-all}"
case "$mode" in
    all|preflight|build) ;;
    *) echo 'Usage: scripts/verify-android.sh [all|preflight|build]' >&2; exit 2 ;;
esac
mkdir -p build/verification
# /tmp can report free bytes while its per-user tmpfs quota rejects every write.
# Keep large Android/Kotlin/SQLite temporary files on disk and fail early on real write errors.
export TMPDIR="${TMPDIR:-${RUNNER_TEMP:-${XDG_CACHE_HOME:-$HOME/.cache}/terminal-spike/tmp}}"
mkdir -p "$TMPDIR"
python3 - "$TMPDIR" <<'PY'
import os, sys, tempfile
fd, path = tempfile.mkstemp(prefix="android-verify-write-", dir=sys.argv[1])
try:
    os.write(fd, b"x" * 4096)
    os.fsync(fd)
finally:
    os.close(fd)
    os.unlink(path)
PY
if [[ "$mode" != build ]]; then
    python3 scripts/verify-android-test-contract.py --validate \
        scripts/android-test-contract.json app/src/androidTest/java
    scripts/verify-bundled-fonts.sh >build/verification/fonts.log 2>&1
    store-assets/google-play/validate-store-assets.sh >build/verification/store-assets.log 2>&1
    if ! python3 -m unittest discover -s scripts/tests -p 'test_*.py' \
        >build/verification/tooling.log 2>&1; then
        tail -80 build/verification/tooling.log >&2
        exit 1
    fi
    tail -4 build/verification/tooling.log
fi
if [[ "$mode" != preflight ]]; then
    start=$SECONDS
    gradle_workers="${TERMINAL_SPIKE_GRADLE_WORKERS:-2}"
    jvm_processors="${TERMINAL_SPIKE_JVM_PROCESSORS:-2}"
    [[ "$gradle_workers" =~ ^[1-9][0-9]?$ && "$jvm_processors" =~ ^[1-9][0-9]?$ ]] || {
        echo 'Invalid Android verification worker/processor limit.' >&2
        exit 2
    }
    signing_options=()
    temporary_options=()
    if [[ -n "${TMPDIR:-${RUNNER_TEMP:-}}" ]]; then
        # A reused desktop Kotlin daemon can still point at a quota-limited /tmp.
        # Make its temporary-directory input explicit without disabling caches.
        temporary_options+=("-Pkotlin.daemon.jvmargs=-Djava.io.tmpdir=${TMPDIR:-$RUNNER_TEMP}")
        gradle_jvm_args="$(sed -n 's/^org.gradle.jvmargs=//p' gradle.properties | tail -n 1)"
        temporary_options+=("-Dorg.gradle.jvmargs=${gradle_jvm_args} -Djava.io.tmpdir=\"$TMPDIR\" -XX:ActiveProcessorCount=$jvm_processors")
        # In-process compilation avoids reusing a Kotlin daemon initialized against old /tmp.
        temporary_options+=("-Pkotlin.compiler.execution.strategy=in-process")
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=\"$TMPDIR\" -Dsqlite.tmpdir=\"$TMPDIR\" -XX:ActiveProcessorCount=$jvm_processors"
    fi
    if [[ -n "${TERMINAL_SPIKE_RELEASE_STORE_FILE:-}" ]]; then
        # Signing passwords must not be serialized into the configuration cache.
        signing_options+=(--no-configuration-cache --no-daemon)
    fi
    # Do not clean caches, disable incremental compilation, or run the same graph twice.
    priority_command=()
    if command -v nice >/dev/null; then priority_command+=(nice -n 19); fi
    if command -v ionice >/dev/null; then priority_command+=(ionice -c 3); fi
    if "${priority_command[@]}" ./gradlew --dependency-verification=strict --console=plain --max-workers="$gradle_workers" "${signing_options[@]}" "${temporary_options[@]}" \
        :mosh-api:testDebugUnitTest :mosh-api:lintDebug :mosh-api:assembleDebug \
        :mosh-core:testDebugUnitTest :mosh-core:lintDebug :mosh-core:lintRelease \
        :mosh-core:assembleDebug :mosh-core:assembleRelease :mosh-core:assembleDebugAndroidTest \
        :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
        :app:assembleDebug :app:assembleRelease :app:bundleRelease \
        :app:assembleDebugAndroidTest :app:stageRuntimeTestUtilities :benchmark:assemble \
        >build/verification/gradle.log 2>&1; then
        tail -5 build/verification/gradle.log
        printf 'ANDROID_VERIFY status=passed seconds=%s log=build/verification/gradle.log\n' "$((SECONDS-start))"
    else
        tail -100 build/verification/gradle.log >&2
        exit 1
    fi
fi
