# Publishing checklist

The source tree is release-candidate quality, but it is not yet authorized for public production
distribution. Complete every applicable item below for each separately published package.

<!-- release-evidence-current:start -->
Current release evidence: app JVM `971` tests (`971` passed, `0` skipped, `0` failures/errors); Mosh API JVM `11` tests (`11` passed, `0` skipped, `0` failures/errors); Mosh extension JVM `8` tests (`8` passed, `0` skipped, `0` failures/errors); old-phone Android app `332` tests (`307` passed, `25` skipped, `0` failures/errors). The source and artifact hashes and any pending external gates are recorded in `build/release-evidence/candidate-manifest.json`.
<!-- release-evidence-current:end -->

## Repository gates — complete

- The dated 2026-09-03 local gate was green with 960 app JVM tests. Stable API
  37.0 passed exact boundary membership `42/42` with no failures or skips. It
  covers cold launch, real system denial/grant, exact-action continuation,
  Activity recreation, public-host bypass, and the permission-preserving Nearby
  system picker. The runner then proved grant, original-process termination on
  revoke, relaunch, and retained denial. Off-main hostname classification requests
  permission for resolved LAN answers without prompting for public answers or DNS
  failure. The real API 37 LAN gate passed both required methods: absent permission
  blocked raw TCP and production SSH before protocol traffic, and a grant completed
  terminal SSH plus SFTP upload/download/delete against the same live endpoint.
  That API 35 full run accounted for its 329-method contract (`307` passed, `22`
  reviewed skips), including all seven API-37-only methods by exact skip identity.
  The contract now contains 332 methods because three clean-install backup methods are reserved for
  their disposable-host runner; rerun the default suite on the final source before candidate freeze.
  Completed SSH over an actual public-Internet route while local-network access is
  denied remains an external release gate.

- Main and Mosh launcher icons are declared as legacy/adaptive resources, with Android 13
  monochrome layers and focused on-device resource tests.
- Reproducible 512 × 512 RGBA store icons and SVG masters are in `store-assets/`.
- The 2026-09-02 full gate passed 475 tasks across unit tests, lint, debug/release builds,
  Android-test assembly, release APK/AAB packaging checks, both native Mosh ABIs, and benchmark
  assembly. That run's unit totals were app `939/0/0/0`, Mosh API `11/0/0/0`, and extension
  `8/0/0/0` in tests/failures/errors/skipped order.
- The exact model-checked old `SM-S911B` passed the 316-test default app runner (303 passed and 13
  expected opt-in real-server skips) and the guarded real-Mosh 4+1 password/private-key matrix.
  Project policy forbids tests on the connected fold.
- Clean contract-enforced emulator suites passed all 316 named tests on APIs 26 and 35 and the
  exact 33-test boundary suite on APIs 28, 29, 32, and 33. Skip identities matched the reviewed
  API rules; aggregate counts alone are not accepted.
- A dedicated API 35 disposable-AVD gate exported encrypted Standard and Full backups, crossed a
  separate uninstall/reinstall boundary for each restore, proved old private state absent, and
  restored Full portable credentials under a fresh Keystore. Its three exact methods passed with
  no failures or skips, and the gate is required in CI for pull requests and main pushes.
- The post-change forced source gate executed 451/451 tasks in 5m49s. App/Mosh API/Mosh extension
  JVM totals are `971/11/8` with zero failures/errors/skips; lint, debug/release/Android-test
  packaging, both Mosh ABIs, benchmark assembly, 95 host-script tests, and release APK/AAB
  packaging checks are green.
- A fresh ephemeral-key `0.0.2` release-like gate passed signature verification, clean install,
  real extension-absent SSH, same-certificate update, process restart, non-secret host retention,
  password re-prompt, and a second SSH connection on the named disposable API 35 AVD. This is not
  production signing or Play evidence.
- A separate external API 35 lifecycle gate passed real SSH before and after an exact ownership-
  verified app-PID kill. Background, deep idle, restricted standby, and Data Saver cases restored
  their recorded AVD state; the dead process did not restart itself, its service/notification
  cleared, one host and recent row remained, no session-only secret was stored, and restart used a
  distinct PID with an honest empty-session UI and password re-prompt.
- The same minified release/update SSH gate is wired into CI through an ephemeral-signing
  orchestrator that uploads only a sanitized status file and deletes its key and signed outputs.
  The local CI-equivalent rehearsal is green; the first hosted run remains pending an authorized
  commit/push and is not claimed here.
- Both packages target API 37, above Google Play's announced 31 August 2026 phone-app minimum of
  API 36.

## Decisions and external work — required before production

1. Approve the public product identity. Confirm whether **Terminal Spike** is the final store name,
   then approve the permanent application IDs. Change them only before the first public upload.
2. The next main-app internal upload uses `versionCode = 6`, `versionName = 0.0.3`; the extension
   remains independently versioned at code 5 and `1.0.1-mosh-1.4.0`. Play Console has consumed
   main-app codes 1, 4, and 5, with code 5 active on `internal`. Increment every later upload's
   version code while choosing public version names independently.
3. Create and securely back up a production upload key outside Git, enroll both Play apps in Play
   App Signing, and deliberately configure the same app-signing certificate for both packages.
   Their signature-protected Binder permission requires matching installed signatures. Produce and
   test signed release artifacts; the current release outputs are unsigned unless all four external
   signing inputs are supplied.
4. Complete Android/Play developer verification and register both package names to the publishing
   account.
5. Host a public, non-editable, non-geofenced HTML privacy-policy URL naming the app/developer.
   Complete an accurate Data safety form for each package even if no user data is collected or
   shared. Keep the declarations aligned with SSH/Mosh network traffic, locally stored credentials,
   backup export, notification behavior, and every distributed SDK.
6. Submit the Play Console foreground-service declaration for each package's `specialUse` service.
   Provide the required feature description, interruption impact, and demonstration video showing
   the user-started remote terminal session and persistent notification.
7. Finish each store listing: final title, short/full descriptions, category, contact details,
   content rating, target audience, pricing/countries, 1024 × 500 feature graphic, and at least two
   release-accurate screenshots. Do not use debug builds or synthetic UI for screenshots.
8. Run the signed release through an internal track and Play pre-launch report. Manually cover a
   handset and expanded/folded or tablet layout; light/dark and large text; TalkBack; IME and
   physical keyboard; notification denied; locked screen; OEM battery restrictions;
   split screen/rotation; backup round trip; SSH trust/change/authentication; concurrent sessions;
   and real Mosh resize, extension death, and Wi-Fi/cellular/VPN transitions.
9. Obtain specialist open-source and trademark review before publishing the Mosh extension or
   linking the two listings. Resolve GPL Corresponding Source and installation-information duties,
   Play signing/key-rotation implications, combined presentation, and use of the Mosh name. The APK
   boundary is architecture evidence, not a licensing conclusion.
10. Promote only after the signed artifacts, store declarations, legal decision, manual matrix,
    release notes, support contact, and rollback plan are frozen and recorded.

## Current official references

- [Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Prepare an Android app for release](https://developer.android.com/studio/publish/preparing)
- [App signing and Play App Signing](https://developer.android.com/studio/publish/app-signing)
- [Google Play preview/store assets](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Google Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Foreground-service declarations](https://support.google.com/googleplay/android-developer/answer/13392821)

Recheck these policies immediately before submission; store requirements can change independently
of the repository.
