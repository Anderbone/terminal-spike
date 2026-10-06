# Verification and routine releases

Use `scripts/verify-android.sh` locally. GitHub Actions runs its same preflight and
build stages. It validates the exact instrumentation inventory and skip rules
before building, then runs all existing JVM tests, lint, debug/release packaging,
instrumentation assembly and benchmark assembly in one Gradle invocation. Strict
dependency checks, JDK toolchain rules and release packaging checks remain enabled.
Do not use `clean` or disable incremental compilation for routine verification.
Logs and timings are in ignored `build/verification/`.

For UI changes, also run the affected instrumentation classes before pushing:

```bash
scripts/verify-android.sh
scripts/run-android-emulator-tests.sh --api 35 --suite full \
  --test-filter com.yanjiyu.terminalspike.MainScreenSmokeTest
```

Select classes relevant to the actual change; use API 26 as well for platform or
keyboard behavior spanning supported Android versions. A filtered run is diagnostic
evidence, not a passing full suite. Compiling the instrumentation APK does not execute
its tests. Existing real-server and real-phone acceptance contracts still apply to
their features; never run tests on the foldable.

## Hosted CI

- The full API 26/35 suites each have two deterministic class shards with Orchestrator
  isolation unchanged. Each shard enforces exact membership and skip identity. The
  existing `required-runtime (26)` and `(35)` gate names now aggregate the shards;
  they reject failed shards, missing reports, duplicates, missing tests and incorrect
  skips. Boundary APIs and all end-to-end gates remain.
- Weekly/manual full API 26–37 coverage uses four class shards per API, with the same
  exact combined membership and skip checks. This bounds emulator lifetime: a local
  unsharded API 27 run grew past 18 GiB of host RSS while its tests kept passing;
  hosted full runs suffered runner shutdowns and an Android system watchdog crash.
  Each shard starts a fresh emulator. No tests or assertions are removed, and the
  `scheduled-runtime (API)` gates reject missing, duplicate or failed results.
- Runtime jobs download the verified APKs without restoring Gradle caches they do not
  use. The release update job verifies the source revision and SHA-256 manifest of the
  first build's unsigned APK/AAB, signs copies with its temporary key, verifies both
  signatures and runs the existing release/update SSH acceptance. It does not compile
  the release a second time. Production credentials never enter this workflow.
- Verification logs and test reports are retained for seven days. Use the Gradle
  setup summary and `FROM-CACHE`/`UP-TO-DATE` counts to assess caching. Compare job
  durations and total billed minutes over several similar runs before claiming a
  speedup. Two shards reduce elapsed test time but add emulator startup overhead;
  they do not promise fewer billed minutes.
- Diagnose the first failed test or infrastructure operation using its report. Fix
  a reproducible synchronization or environment problem; do not add blanket retries,
  arbitrary sleeps, weaker assertions or skip rules to manufacture green results.
  Run the affected check after a fix; repeat the full gate only when inputs justify it.

Ordinary commit/push/publish requests finish after required local and publication
checks. Hosted CI continues independently. Request `$push-main-green` explicitly
when the task includes waiting for and repairing all hosted checks. Never describe
pending hosted checks as passed.

## Main-app internal release

Read the external signing directory's `README.txt` and the existing
[publishing checklist](PUBLISHING.md) first. This helper implements the established
main-app internal-test release; it does not approve production distribution or the
separate Mosh extension. It needs Python 3, OpenSSL, the configured Android/JDK tools,
Git and the existing external signing files/service-account JSON. No new Python
package is required. Default signing directory: `../app_sign`; override with
`--signing-dir` and, when needed, `--credential` pointing outside the repository.

```bash
# Inspect all visible consumed bundle/track codes and the internal track.
python3 scripts/publish-play-internal.py inspect

# Choose the next version name. This bumps only the main app's version code/name,
# runs the shared local gate once with external signing, and freezes the verified AAB.
python3 scripts/publish-play-internal.py prepare --version-name 0.0.7

# Review the intended diff, then commit and push using the normal workflow.
# Publish refuses dirty or changed source and requires this exact commit on origin/main.
python3 scripts/publish-play-internal.py publish
```

Preparation chooses a code above both the current source and every code returned
by Play. It stores source/artifact hashes in ignored `build/play-release/manifest.json`.
Commit creation does not invalidate that evidence, but source changes do. Publication
rechecks Play codes and signatures, uploads the frozen bundle once, verifies Play's
reported hash/code, validates and commits the edit, then rereads the internal track.
It reports the confirmed track state; store availability can still lag. Retain the
publish receipt alongside your external release archive. Perform the project-required
final foldable install/launch separately; this script never selects or installs on a phone.

If full hosted CI is specifically required, use `publish --require-green-ci`.
The script watches the exact commit's push workflow for at most one hour, without
an agent repeatedly polling or reading logs. Failure blocks upload and is reported;
the script does not repair CI. Default publishing does not wait for hosted CI.

An ambiguous upload/commit failure leaves a receipt with the edit ID and blocks a
repeat attempt for that version. Inspect that edit and the Play track before recovery;
never blindly delete the receipt and reupload. After an actual source/artifact change,
prepare a new unconsumed code. Concurrent edits during preparation invalidate evidence.
Use a separate checkout when another session is actively changing the same application.

References: [Android test sharding and Orchestrator](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner),
[Gradle on GitHub Actions](https://docs.gradle.org/current/userguide/github-actions.html),
[Play bundle upload](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload),
[Play track update](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks/update).
