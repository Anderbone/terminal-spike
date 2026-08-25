# Store artwork

- `terminal-spike-icon-512.png` and `terminal-spike-mosh-icon-512.png` are the 512 × 512,
  fully opaque PNG store icons generated from the adjacent project-owned SVG masters.
- Regenerate them with `rsvg-convert --width 512 --height 512 --output <png> <svg>`.
- Launcher variants live in each Android application's `res/mipmap-*` and `res/drawable`
  directories. Keep the store and launcher families visually aligned when changing either one.
- `google-play/` contains the prepared main-app listing copy, translations, feature graphic, actual
  release-variant phone/tablet screenshots, alt text, upload checklist, and validation script.
- Re-capture screenshots from the final production-signed release candidate if UI or branding
  changes after this draft is approved.
