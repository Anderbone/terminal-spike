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

## GitHub Actions on the local runner

- All jobs use `[self-hosted, Linux, X64]`, matching the registered `epee` runner.
  Pushes/manual runs on `main` and pushes to same-repository `codex/ci-fix-*`
  repair branches execute jobs. Checks on those commits appear on their matching PRs.
  No pull-request event triggers are enabled; forks and ordinary feature PRs do not
  execute. The local [bounded repair loop](CI-AUTOFIX.md) updates the same PR
  after each failed run; green PRs still require human review/merge. There is no schedule;
  start the full API 26–37 compatibility sweep manually when needed.
- The runner needs a writable Android SDK exposed as `ANDROID_HOME` and
  `ANDROID_SDK_ROOT`, command-line tools at `cmdline-tools/latest/bin/sdkmanager`,
  Python 3, Docker Compose, and read/write access to `/dev/kvm`. Java 25 is set up
  by the workflow. Configure KVM access on the runner once; CI checks access
  without changing host udev rules or requiring passwordless sudo.
- PRs require the user's manual review and explicit merge approval. Keep repository
  auto-merge disabled. Only the narrowly authorized CI-repair PRs run automatically.
  CI success never authorizes an agent to merge a PR.
- Push runs and manual compatibility runs use separate concurrency
  groups, so starting a compatibility sweep does not cancel normal CI. Newer runs
  within the same branch and group still supersede older ones.
- SDK tooling/platform installation retries the identical command once, after ten
  seconds, only for HTTP 429/502/503/504 or recognized Java socket timeout/reset
  errors. Missing packages, licensing errors and unknown failures fail immediately.
  Tests are never automatically retried by this helper.
- Optional post-test logcat collection is bounded and warns if unavailable. The
  instrumentation exit status, JUnit conversion, exact membership, skip rules and
  any subsequent acceptance steps still determine success. A crashed or incomplete
  test run cannot become green because logcat failed.
- Artifact uploads replace the prior attempt's same-named artifact within the run,
  including APKs and release artifacts if the build itself is retried. A job-only
  retry continues to reuse the existing verified APKs; it does not rebuild them.
- The full API 26/35 suites each have two deterministic class shards with Orchestrator
  isolation unchanged. Each shard enforces exact membership and skip identity. The
  existing `required-runtime (26)` and `(35)` gate names now aggregate the shards;
  they reject failed shards, missing reports, duplicates, missing tests and incorrect
  skips. Boundary APIs and all end-to-end gates remain.
- Manual full API 26–37 coverage uses four class shards per API, with the same
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
- The persistent self-hosted verification runner reuses its local Gradle User Home.
  `setup-gradle` still validates the wrapper but does not synchronize a remote cache;
  the November 13 run downloaded about 22 GB before a cache authentication failure.
  Local incremental/task caches remain enabled. Fresh acceptance installs complete
  the visible Getting started guide before connecting. Lifecycle authentication
  selects the native password field by its accessible label; saved-data, process-death
  and remote-output assertions remain required.
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
checks. GitHub Actions continues independently. Request `$push-main-green` explicitly
when the task includes waiting for and repairing all hosted checks. Never describe
pending hosted checks as passed.

### Repair without restarting passing work

1. Read the completed failed job's log/artifact and classify the first failure.
   Reproduce a test failure with the affected class and API; repair assertions only
   when the product contract changed, not simply because an assertion is red.
2. Batch confirmed fixes, run the affected checks and then the shared local gate
   once. Reuse passing results only when their source, dependencies and configuration
   are unchanged. A changed commit still needs its own required CI checks.
3. For a proven transient infrastructure failure, wait for the run to complete and
   use `gh run rerun RUN_ID --job JOB_ID` once. This preserves successful jobs and
   reuses that run's APK artifacts; dependent aggregate checks rerun automatically.
   Do not use a fresh workflow dispatch or rerun-all to recover one failed shard.
4. Run the manual sweep once at the end only when full compatibility
   verification is requested. Summarize milestone changes and final evidence;
   repeated status narration adds no diagnostic value.

The concurrency and replacement behavior follows GitHub's
[concurrency documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
and [artifact overwrite contract](https://github.com/actions/upload-artifact#overwriting-an-artifact).

## Main-app release and promotion to every channel

The user's standing authorization in `AGENTS.md` makes an unqualified push/publish/release
request include a public main-app release to all four existing channels. Respect an
explicitly narrower request; commit-only requests do not trigger publication. Target package
`com.yanjiyu.terminalspike`; upload once to `internal`, then promote the identical bundle
to `production`, `internal`, `beta`, and `alpha`. Signing material, verified artifacts,
and the Publisher API credential live outside this repository in
`/home/jiyu/git/app_sign`. Never print or commit credentials or passwords, or copy
them into this repository.

Version the main app and standalone Mosh extension independently; matching version
codes or names are never required for compatibility. Leave the published extension
unchanged unless its code, API contract, dependencies, manifest/SDK configuration,
resources, notices, signing requirements, or compatibility behavior changes.
Publishing it requires an explicit request and completed licensing, trademark,
signing, source-offer, and listing gates. Any changed or deliberately rebuilt Mosh
bundle needs a new unconsumed Play version code and a version name based on its own
release history. Enforce compatibility through the protocol and API contract.

Read the external signing directory's `README.txt` and the existing
[publishing checklist](PUBLISHING.md) first. This helper implements the established
main-app release safeguards. The standing instruction authorizes production submission;
`promote --track all` explicitly selects all four channels. The separate production-only
path still requires `--track production` for preparation and publication. It never publishes the
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

# Default completion of an unqualified push/publish/release request:
# Commit an accurate current Play summary first, then promote the same uploaded bundle.
python3 scripts/publish-play-internal.py promote --track all

# When the release explicitly includes the prepared English store refresh:
python3 scripts/publish-play-internal.py publish --with-store-assets

# Alternative when the user specifically requests a production-only upload:
python3 scripts/publish-play-internal.py inspect --track production
python3 scripts/publish-play-internal.py prepare --version-name 0.0.13 --track production
# Review, commit and push the prepared source first.
python3 scripts/publish-play-internal.py publish --track production
```

Promotion requires the frozen verified AAB, its completed upload receipt, clean source
pushed to `origin/main`, and a matching bundle code/SHA-256 returned by Play. It permits
only documentation, this publishing helper/tests, and the Play summary to change since
the upload; application changes require preparing and publishing the latest source first.
It updates all four existing tracks in one validated edit, commits it, then checks every
track's version, completed status and release notes. It never reuploads the bundle or
silently creates new channels. An ambiguous promotion leaves
`build/play-release/promotion-receipt.json` with its edit ID; inspect before retrying.
For an already completed latest internal upload, promotion alone is sufficient. Reuse
passing Android verification for unchanged application inputs and run affected tooling checks.

An unqualified release also includes a matching GitHub Release `v<versionName>`:
commit and push all intended non-ignored local work, create the tag at the reviewed
release commit, attach the verified signed release APK, the complete Corresponding
Source archive generated by `scripts/create-source-bundle.sh`, and SHA-256 checksums,
and use accurate current release notes. Publish it as a normal release and mark it
Latest. Inspect any existing tag/release first; never retarget a published tag or
replace an existing asset with a different artifact silently. Re-read the GitHub
release metadata/assets and all Play tracks before reporting completion. Reuse
existing verified artifacts when application inputs are unchanged; a docs/tooling-only
commit can follow the artifact commit, which must remain recorded in the receipt.

Upload an additional identical APK named `terminal-spike-latest.apk` to every normal
release. README and both official website locales use the permanent URL
`https://github.com/Anderbone/terminal-spike/releases/latest/download/terminal-spike-latest.apk`.
Create the normal public Release and this asset before claiming the URL works:
GitHub Latest excludes prereleases. The website's documentation links follow
`/blob/main/docs/`, not an old release commit. Website source is the separate
`Anderbone/yanjiyu.com` repository; follow its checks and deploy relevant link changes
to Cloudflare Workers. Subsequent releases need no website deployment when the stable
links remain correct.

Required final gate:

```bash
python3 scripts/verify-publication.py
```

It exits nonzero for stale/missing Play tracks, an incorrect public Latest release/tag,
incomplete or mismatched APK/source assets and checksums, an actual Latest APK download
that differs from the verified signed APK, an old README download link, or stale
English/Chinese deployed website links. A sanitized receipt is saved in ignored
`build/play-release/publication-verification.json`. Do not call a release complete
unless this gate passes; report Google review/managed publishing separately. This
is a required agent workflow after a push/publish request, not automatic deployment
on a bare `git push`; ordinary CI still performs verification without production keys.

Preparation chooses a code above both the current source and every code returned
by Play. It stores source/artifact hashes in ignored `build/play-release/manifest.json`.
Commit creation does not invalidate that evidence, but source changes do. Publication
rechecks Play codes and signatures, uploads the frozen bundle once, verifies Play's
reported hash/code, validates and commits the edit, then rereads the selected track. The default remains `internal`; a prepared artifact
cannot silently switch tracks. Production includes the committed English release
summary from `store-assets/google-play/release-notes/main-en-US.txt` (1–500 characters)
without replacing listing text or images. A completed production track readback
confirms submission; Google review, managed publishing or store propagation can delay
public availability.
The opt-in `--with-store-assets` flag patches only English listing text, replaces phone
screenshots in filename order and the English feature graphic, and includes English
release notes in the same validated edit. Uploaded image SHA-256 values and ordered
listing readback must match before commit and again after publication. Other locales,
tablet pictures and the icon remain untouched. An ambiguous image upload leaves the
same receipt guard as a bundle failure; inspect the saved edit before recovery.

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

## CI infrastructure failure evidence

The self-hosted runner requires a stable `gh` executable on `PATH`. Artifact and
job metadata reads use the workflow token's `actions: read` permission.

Emulator scratch directories use the runner's disk through `TMPDIR` (with a
`RUNNER_TEMP` fallback), avoiding
the desktop user's quota-limited `/tmp` tmpfs. Runtime test AVDs have no emulated
hardware keyboard so the software keyboard follows touch-input policy. Required
IME assertions and test identities remain unchanged.

The shared `install-android-sdk.py --system-image TOOLS IMAGE` path installs the
same selected stable image with the Android CLI and falls back to sdkmanager.
Only recognized transient download errors retry once. Lifecycle failures retain
the redacted installer output in `sdk-install.txt`.

CI downloads use the local `download-ci-artifact` action and authenticated GitHub
REST API. Run ID, source SHA, artifact ID and SHA-256 must match before extraction.
An artifact ZIP request retries once for a known transport error, or for a 404 only
when metadata confirms the same unexpired artifact still exists. Digest errors,
missing membership and exhausted retries fail the job. No APK is rebuilt to
recover an artifact transfer.

Before uploads, sanitized runtime evidence is retained separately under
`~/.local/state/terminal-spike-ci-evidence/<run>/<attempt>/<artifact>/` on the
self-hosted runner. This archive survives the next checkout's cleanup and Artifact
service failures; it contains the same sanitized outputs intended for upload.
Failed instrumentation produces JUnit test identities/stacks and still exits
nonzero. Per-API aggregation reports its own exact shard outcomes, and one failed
aggregate no longer cancels the other API's diagnostic gate.
