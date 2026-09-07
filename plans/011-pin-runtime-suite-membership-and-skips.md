# Plan 011: Make runtime gates fail on silent test or skip erosion

> **Executor instructions**: Follow this plan step by step. Run every verification command and
> confirm the expected result before moving on. Preserve the full/default, boundary, and OpenSSH
> suites; do not remove or weaken tests to make their inventories easier to express. If a STOP
> condition occurs, report it instead of improvising. When done, update this plan and
> `plans/README.md` with exact evidence.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- scripts/run-android-emulator-tests.sh scripts/instrumentation-output-to-junit.py scripts/tests app/src/androidTest .github/workflows/android-ci.yml`
> The worktree was intentionally dirty when this plan was written. Also inspect the live unstaged
> diff for these paths and compare the facts below before editing.

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: LOW
- **Depends on**: Plan 009
- **Category**: tests / CI
- **Planned at**: commit `93d070a`, 2026-09-02
- **Execution status**: DONE — source discovery, runtime membership, and
  API-specific skip identities are enforced; the clean local matrix and full
  repository gate are green.

## Why this matters

The emulator runner rejects zero tests and execution failures, but its full and boundary suites do
not prove which tests ran or which tests skipped. A renamed, undiscovered, filtered, or newly
skipped test can therefore reduce coverage while CI remains green. The OpenSSH suite already uses
the stronger pattern: it rejects assumption skips and requires named methods. Apply that standard
to every required runtime suite without making normal test additions painful.

## Current state

- `scripts/run-android-emulator-tests.sh:41-54` defines the full, boundary, and OpenSSH suites.
- `scripts/run-android-emulator-tests.sh:252-268` rejects skips and checks required names only for
  OpenSSH.
- `scripts/run-android-emulator-tests.sh:270-271` converts any otherwise successful nonzero run to
  JUnit but performs no full/boundary inventory check.
- `scripts/tests/test_android_emulator_runner.py:220-227` proves only that zero-test and explicit
  failure output fail.
- `scripts/tests/test_android_emulator_runner.py:230-246` is the exemplar for named membership and
  skip enforcement.
- `app/src/androidTest/java/com/yanjiyu/terminalspike/TerminalSpikeTestRunner.kt:7-17` changes only
  idling timeouts; it does not own suite membership.
- The latest observed default counts are platform-dependent because explicitly opt-in or
  API-gated methods skip: old-phone XML is `316/0/0/13`; prior clean API 26 and API 35 evidence is
  `312/0/0/14` and `312/0/0/10` respectively. These counts are evidence to remeasure, not constants
  to copy blindly after the newly added credential regression.

Repository convention: runners use strict Bash, exact serial targeting, bounded retries, sanitized
durable output, Python `unittest` host tests, and Android Test Orchestrator with
`clearPackageData=true`. Match `scripts/run-real-mosh-device-tests.sh` for a fixed expected method
set and `scripts/instrumentation-output-to-junit.py` for parser behavior.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Host tests | `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | all pass |
| Shell syntax | `bash -n scripts/run-android-emulator-tests.sh` | exit 0 |
| API dry runs | `for api in 26 28 29 32 33 35; do scripts/run-android-emulator-tests.sh --api "$api" --suite "$([ "$api" = 26 ] || [ "$api" = 35 ] && echo full || echo boundary)" --dry-run; done` | six exact plans, exit 0 |
| Android-test build | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:stageRuntimeTestUtilities` | build successful |
| Full gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | build successful |

## Scope

**In scope**:

- `scripts/run-android-emulator-tests.sh`
- `scripts/instrumentation-output-to-junit.py` only if reusable parsed testcase data is needed
- one new repository-owned suite-contract file under `scripts/`
- host tests under `scripts/tests/`
- `.github/workflows/android-ci.yml` only to pass an explicit contract/version argument
- documentation evidence in `README.md`, `IMPLEMENTATION_STATUS.md`, and Plan 009 after green runs

**Out of scope**:

- changing application behavior or production code
- deleting, renaming, filtering, or converting tests to assumptions
- treating one aggregate count as sufficient proof of method membership
- requiring opt-in SSH/SFTP/Mosh/Codex methods in the default suite
- physical-device operations or Mosh fixture changes

## Git workflow

Preserve all unrelated dirty work. Do not commit, push, or open a PR unless the operator asks.

## Steps

### Step 1: Define a reviewable runtime-suite contract

Create a small data file that declares, per suite, required test class/method identifiers, explicit
opt-in methods, the allowed API-gated skip set for each supported API boundary, and a schema/version.
Generate the initial entries from current source and actual API 26/28/29/32/33/35 reports, then
review them against every `assumeTrue` in `app/src/androidTest`. Do not put secrets, host addresses,
raw instrumentation arguments, or build paths in the contract. Do not use only a minimum test
count: a removed method and an unrelated added method must not cancel each other out.

**Verify**: a host test loads the contract, rejects duplicate IDs and unknown suites/APIs, and
proves every statically discoverable `@Test` method is required or explicitly opt-in/API gated.

### Step 2: Validate executed membership and skips

After converting instrumentation output, compare its testcase IDs and skipped IDs with the
contract for the selected suite/API. Fail with a concise diff when a required test is missing, an
unexpected test is skipped, a required test appears more than once, or zero executable tests
remain. Additions may fail with an instruction to update the reviewed contract or be reported
distinctly; choose one policy and enforce it consistently. Preserve the strict OpenSSH checks.

Normalize only runner-added display suffixes proven by captured output; never collapse two distinct
parameterized cases to one identifier. Keep raw and JUnit artifacts sanitized.

**Verify**: fake-runner tests cover a missing method, unexpected skip, allowed API skip, duplicate
execution, added method, malformed contract, and a fully matching run.

### Step 3: Rebaseline from clean/wiped supported APIs

Run the full suite on clean API 26 and 35 AVDs and the boundary suite on clean API 28, 29, 32, and
33 AVDs. If output disagrees with the reviewed source contract, determine why; do not simply copy
the output into the allowlist. Record exact tests/failures/errors/skips for each API.

**Verify**: every run exits 0 under the contract, and deliberately removing one captured testcase
or changing one pass to skip makes the validator exit nonzero.

### Step 4: Keep CI and documentation honest

Make every runtime CI invocation use the checked-in contract. Update Plan 009 and release-status
docs with newly measured counts and explain that membership plus skip identity is enforced.

**Verify**: workflow YAML parses; `git diff --check`, all host tests, and the full Gradle gate pass.

## Test plan

- Host contract/parser tests for exact membership, missing/duplicate/additional methods, allowed and
  forbidden skips, malformed output, and redacted diagnostics.
- Clean AVD full acceptance on APIs 26 and 35.
- Clean AVD boundary acceptance on APIs 28, 29, 32, and 33.
- Existing fixed OpenSSH five-test suite remains zero-skip and green.

## Done criteria

- [x] Full and boundary suites fail if any required test silently disappears.
- [x] Full and boundary suites fail on any skip not explicitly allowed for that API.
- [x] Parameterized tests remain individually accountable.
- [x] Every current `@Test` is classified as required, explicit opt-in, or API-gated.
- [x] All runner host tests and clean API 26/28/29/32/33/35 executions pass.
- [x] Full unit/lint/debug/release/Android-test/benchmark gate and `git diff --check` pass.
- [x] No physical device was targeted by the emulator runner.

## STOP conditions

- Instrumentation output cannot uniquely identify parameterized cases; stop with sanitized examples
  rather than merging distinct tests.
- A current assumption has no explicit product/platform rationale; report it instead of allowlisting.
- The contract would require a new parser dependency; use a reviewed manifest or existing tooling.
- Any proposed change weakens the fixed OpenSSH or real-Mosh gates.

## Maintenance notes

Every added, removed, renamed, newly parameterized, or newly API-gated instrumentation test must
update this contract in the same review. Reject count-only substitutions and wildcard skip rules.

## 2026-09-02 execution evidence

- `scripts/android-test-contract.json` records all 316 source `@Test` identities, the exact
  33-test boundary suite, supported APIs 26–36, and four narrowly scoped skip rules. A host test
  discovers Kotlin test methods and requires the full contract to match them one-for-one.
- `scripts/verify-android-test-contract.py` rejects missing, added, duplicate, malformed, or
  unsupported results and requires the observed skip identities to equal the active API rules.
  Full and boundary invocations in `scripts/run-android-emulator-tests.sh` enforce it
  automatically; an explicit diagnostic filter remains intentionally outside release-suite
  accounting. The existing fixed, zero-skip OpenSSH checks remain unchanged.
- The first clean API 35 validation exposed that the log scrubber's whitespace expression could
  cross a newline after method names ending in `Password`, `Secret`, or `Credential` and redact the
  following status code. Redaction is now line-local and a regression proves instrumentation
  records remain intact; no test or contract entry was removed to clear that failure.
- Clean full suites passed on API 26 (`316/0/0/17`) and API 35 (`316/0/0/13`). Clean boundary
  suites passed on API 28 (`33/0/0/2`), API 29 (`33/0/0/2`), API 32 (`33/0/0/2`), and API 33
  (`33/0/0/1`). Each tuple is tests/failures/errors/skips and every run reported
  `membership=exact`.
- All 57 host/script tests, shell syntax, workflow YAML, `git diff --check`, and the 475-task full
  Gradle unit/lint/debug/release/Android-test/benchmark gate passed. Only disposable
  `emulator-5554` targets were used; no physical device was addressed during this plan.
