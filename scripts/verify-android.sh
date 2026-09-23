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
    signing_options=()
    if [[ -n "${TERMINAL_SPIKE_RELEASE_STORE_FILE:-}" ]]; then
        # Signing passwords must not be serialized into the configuration cache.
        signing_options+=(--no-configuration-cache --no-daemon)
    fi
    # Do not clean caches, disable incremental compilation, or run the same graph twice.
    if ./gradlew --dependency-verification=strict --console=plain --max-workers=2 "${signing_options[@]}" \
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
