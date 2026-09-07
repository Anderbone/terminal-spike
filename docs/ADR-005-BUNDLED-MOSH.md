# ADR-005: One APK with built-in Mosh

- Date: 2026-09-07
- Status: Accepted and implemented
- Supersedes: ADR-003's separate APK boundary and ADR-004's retained packaging scope

The owner explicitly requested one repository and one APK. The existing GPL-3.0-or-later
application now incorporates the reviewed Mosh 1.4.0, Nettle 3.10.2 and Protocol Buffers 21.12
native implementation. This is the explicit architecture and licensing decision approving those
components in the main APK. Component licences, source archives, exceptions and notices remain.

`mosh-core` is an internal Android library and has no application ID, launcher, signing key,
Play version or independent release. The single product remains `com.yanjiyu.terminalspike`.
`mosh-api` retains its Apache-2.0 bounded AIDL/models and file-descriptor streaming interface.
The broker runs in `:mosh_broker` and ten workers in `:mosh_session_0` through `:mosh_session_9`.
All services are private, share the app UID and accept only that UID. The application container
and credential stores initialize only in the main process. Real process boundaries preserve
Binder descriptor ownership, callback-death cleanup and upstream Mosh's process-global state.

The app binds its own broker, never the legacy separately installed extension. It does not
uninstall an existing legacy extension automatically. SSH host verification, authentication and
Mosh bootstrap stay in the main app. Terminal rendering, history and viewport behavior stay
unchanged. Both supported ABIs and all native notices must be present in the release APK/AAB.

There is one application version and source/build/release process. This task does not upload a
Play release or independently bump/publish the legacy Mosh extension.

Acceptance requires unit tests, lint, debug/release APK and AAB packaging verification; private
service/UID and separate-process device checks; actual Mosh bootstrap, bytes, resize, multiple
sessions, worker/broker death and reconnection on the authorized old USB phone; and installation
and launch of the completed build on the foldable if available, without tests there. The public
repository must contain the final implementation and reproducible complete source instructions.

Verification on 2026-09-07, isolated from concurrent Local Arch/manual-tmux work:

- Strict dependency verification, unit tests, lint, debug/release APK, release AAB, app/core
  instrumentation compilation and benchmark assembly passed (428 Gradle tasks). Debug JVM totals:
  app 970, API 11, core 8, with no failures, errors or skips. All 97 host-script tests passed.
- Release APK/AAB checks require both native ELF ABIs and the native notices/licence texts,
  including the preserved upstream OpenSSL exception. The release manifest has one launcher
  (`MainActivity`) and eleven private Mosh services in separate processes.
- Verified USB `RZCW81JZ9CP`, model `SM-S911B`: five real password/lifecycle Mosh methods and one
  private-key method passed. Four concurrent sessions, worker/broker death recovery, real tmux
  touch routing, 500 ordered rows and exact reader anchoring across Activity recreation passed.
  Installed APK hashes were checked before/after each batch to reject concurrent replacement.
- Nine Binder/PFD/manifest tests, four native JNI/licence smoke tests and four Mosh UI tests
  passed on that same verified old phone. A lifecycle test now samples a resting reader after
  its first-gesture assertions; its former immediate post-fling sample raced animation. The
  exact anchor assertion remains unchanged. One UI selector was made exact after the new copy
  matched two substring nodes. Production scrolling code was not changed.
- Final debug APK SHA-256:
  `4e868b663fb5f8a612e7533d2daf454c0ec19672a4e8219476251d56106947bf`.
  The real six-method matrix preceded final wording-only changes; final UI/native/Binder checks
  used this final app APK. Full source/build checks were repeated after the wording changes.
- The Wi-Fi foldable was unavailable. No installation or test targeted it, and no Play release
  was uploaded. Broader actual-Codex/manual-tmux acceptance remains a separate task.
