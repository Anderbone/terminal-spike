# Google Play store-listing package

This folder contains a complete local draft for the Terminal Spike main-app listing. Nothing here
uploads to Play Console or publishes the app.

## Contents

- `metadata/<locale>/title.txt` — localized app name, maximum 30 characters.
- `metadata/<locale>/short_description.txt` — localized short description, maximum 80 characters.
- `metadata/<locale>/full_description.txt` — localized full description, maximum 4,000 characters.
- `graphics/app-icon-512.png` — 512 × 512, 32-bit RGBA PNG, derived from the project-owned app icon.
- `graphics/feature-graphic-1024x500.png` — 1024 × 500, opaque RGB PNG.
- `graphics/feature-graphic.svg` — editable project-owned source for the feature graphic.
- `screenshots/phone/` — five upload-ready 1080 × 1920 phone screenshots.
- `screenshots/tablet-7/` — five upload-ready 1920 × 1080 tablet graphics.
- `screenshots/tablet-10/` — five upload-ready 1920 × 1080 tablet graphics.
- `sources/phone/` — the six original phone captures supplied for this listing.
- `sources/tablet-unfolded/` — the five original unfolded-device captures downloaded from the
  supplied Google Photos share.
- `build-screenshots.sh` — reproducibly crops and frames the upload-ready screenshot sets.
- `SCREENSHOT_ALT_TEXT.md` — unique, concise alt text for each screenshot.
- `TRANSLATION_REVIEW.md` — locale inventory and native-review checklist.
- `PLAY_CONSOLE_CHECKLIST.md` — remaining Play Console owner decisions and upload order.
- `validate-store-assets.sh` — local validation of text limits and required image properties.

## Screenshot provenance

The screenshots use the user's real Android captures of the current app. No UI was generated,
reconstructed, recoloured, or retouched. Phone outputs preserve the native 1080px width and crop
only excess content below 1920px. The redundant phone capture that visibly promotes a third-party
AI product is retained under `sources/phone/` but deliberately excluded from the upload set.

The supplied unfolded-device captures are 2256 × 2504, which is not one of Play's accepted listing
ratios. The first tablet image is a direct 16:9 crop of the colourful terminal. The remaining
tablet images keep the complete capture in a 1920 × 1080 composition; the background is a darkened,
blurred copy of the same capture and contains no additional claims or UI. The same honest adaptive
layout set is prepared for both tablet upload slots.

The screenshots contain no added captions. This allows the same default graphics to accompany all
prepared listing translations without shipping untranslated marketing overlays.

## Validation

Run:

```bash
./store-assets/google-play/build-screenshots.sh
./store-assets/google-play/validate-store-assets.sh
```

The validator checks every locale against the Play title and description limits, verifies the icon
and feature-graphic dimensions and sizes, and requires at least four valid 16:9 or 9:16 screenshots
for every prepared device class.

The package follows Google's current guidance to show real app experiences, include at least four
1080px screenshots for promotional eligibility, avoid rankings and calls to action, and provide
unique alt text. Recheck the official guidance immediately before upload:

- https://support.google.com/googleplay/android-developer/answer/9866151
- https://support.google.com/googleplay/android-developer/answer/9844778
- https://support.google.com/googleplay/android-developer/answer/13393723

## Important release boundary

The main-app copy intentionally does not promote the separately installed Mosh-compatible
extension. Its public distribution remains subject to the licensing, signing, installation, and
trademark decisions recorded in `docs/PUBLISHING.md`.
