# Publishing checklist

The source tree is release-candidate quality, but it is not yet authorized for public production
distribution. Complete every applicable item below for each separately published package.

## Repository gates — complete

- Main and Mosh launcher icons are declared as legacy/adaptive resources, with Android 13
  monochrome layers and focused on-device resource tests.
- Reproducible 512 × 512 RGBA store icons and SVG masters are in `store-assets/`.
- The 2026-08-19 full gate passed unit tests, lint, debug/release builds, Android-test assembly,
  release APK/AAB packaging checks, both native Mosh ABIs, and benchmark assembly.
- Current unit totals are app `815/0/0/0`, Mosh API `11/0/0/0`, and extension `8/0/0/0` in
  tests/failures/errors/skipped order.
- Focused connected tests passed 2/2 for the app and 4/4 for the Mosh extension on `SM-F976B`.
  Both debug APKs installed by exact Wi-Fi serial and `MainActivity` became `topResumedActivity`.
- Both packages target API 37, above Google Play's announced 31 August 2026 phone-app minimum of
  API 36.

## Decisions and external work — required before production

1. Approve the public product identity. Confirm whether **Terminal Spike** is the final store name,
   then approve the permanent application IDs. Change them only before the first public upload.
2. The current production upload candidates use main `versionCode = 5`, `versionName = 0.0.2`; the
   extension uses code 5 and `1.0.1-mosh-1.4.0`. Play Console has consumed version code 4 for both
   packages on testing tracks. Increment every later upload's version code while choosing public
   version names independently.
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
   physical keyboard; notification denied; locked screen; battery restrictions; process death;
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
