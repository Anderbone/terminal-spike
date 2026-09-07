# Release process

## 1. Freeze identity and compatibility

- Confirm package ID `com.yanjiyu.terminalspike.mosh` and the public product name.
- Increment `versionCode` for every Play upload and set a factual `versionName`.
- Confirm the paired Terminal Spike release negotiates the published API and capability range.
- Confirm both Play applications use compatible app-signing certificates; the Binder boundary is
  signature protected.

## 2. Build and verify

Run the clean CI command from a fresh checkout, then create the Corresponding Source archive.

```bash
./gradlew :mosh-extension:test \
  :mosh-extension:lint \
  :mosh-extension:bundleRelease \
  :mosh-extension:assembleRelease \
  :mosh-extension:assembleDebugAndroidTest
./mosh-extension/scripts/create-source-bundle.sh
```

Release signing is enabled only when all four external inputs are supplied. Never commit them.

```bash
export TERMINAL_SPIKE_RELEASE_STORE_FILE=/secure/path/upload.jks
export TERMINAL_SPIKE_RELEASE_STORE_PASSWORD='...'
export TERMINAL_SPIKE_RELEASE_KEY_ALIAS='...'
export TERMINAL_SPIKE_RELEASE_KEY_PASSWORD='...'
./gradlew :mosh-extension:bundleRelease :mosh-extension:assembleRelease
```

Verify the signed APK with the latest Android SDK `apksigner`, record its certificate SHA-256, and
verify it matches the paired main-app release. Preserve the AAB, native debug symbols, R8 mapping,
source archive, checksums, and release notes.

## 3. Device and Play gates

- Test extension absence, install/update, API negotiation, private-key and password bootstrap,
  resize, simultaneous sessions, cancellation, extension death, and SSH fallback.
- Test real Wi-Fi/cellular/VPN transitions against a controlled Mosh server.
- Upload to an internal Play track, review the pre-launch report, and verify Play-generated APKs are
  signed compatibly with the main app.
- Complete Data safety, privacy policy, content rating, target audience, and the `specialUse`
  foreground-service declaration with its demonstration video.

## 4. Publish source and binary together

Create a GitHub release containing the signed release notes, Corresponding Source archive and its
SHA-256 file. Link that durable source release from the Play listing before production rollout.
