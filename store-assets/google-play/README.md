# Google Play listing package

Local artwork and copy for the next Terminal Spike release. Nothing in this directory uploads to
Play or publishes an app. The 2026-10-06 refresh focuses on the English phone listing.

## What to use

- `metadata/en-US/`: refreshed title, short description and About this app copy, highlighting
  built-in Mosh, tmux, Herdr, Tailscale connectivity, SSH keys, snippets, themes, SFTP and TCP tunnels.
- `screenshots/phone/`: numbered 1080 × 1920 English artwork. Real captures, large headlines, and a
  consistent dark green/mint design. Use filename order.
- `graphics/feature-graphic-1024x500.png`: matching opaque feature graphic; its editable SVG is
  next to it. The icon is unchanged.
- `sources/phone-s23/`: raw captures, a provenance/headline manifest and the capture recipe.
- `fixtures/`: non-private sample workspace and suggested snippet data.
- `preview-phone.jpg`: contact sheet for review; do not upload it as a screenshot.
- `SCREENSHOT_ALT_TEXT.md`: descriptions for the prepared images.
- `PLAY_CONSOLE_CHECKLIST.md`: upload steps and release review.

Older supplied phone captures remain in `sources/phone/`. Tablet captures and generated tablet
sets are retained from the previous package, not recaptured in this refresh. Review them against
the release UI before reusing them. Non-English listing drafts are also older: update them from
the English source and review them before publishing. Captioned artwork is English, so localize
headlines for other locales instead of describing it as language-neutral.

## Rebuild and validate

Use the already-established host tooling: Python 3 standard library, ImageMagick 7, librsvg, and
Liberation Sans regular/bold. No Android dependencies or bundled fonts are added.

```bash
./store-assets/google-play/build-screenshots.sh
./store-assets/google-play/validate-store-assets.sh
```

`build-phone-artwork.py` reads the capture manifest. It crops only the specified system-bar
bounds, uniformly scales the real app image, and places it in an SVG composition. It does not
paint over app content, replace text inside the UI, or generate terminal output. Headlines occupy
the top 340px (under 20% of the canvas); each explains the feature visible below it. No AI image
generation is used. Terminal examples are actual shell output from an isolated demo workspace.

The feature graphic is original vector artwork, not a simulated application screenshot. The
legacy tablet builder preserves the existing real unfolded-device capture workflow.

Validation checks metadata limits, image sizes, screenshot count/ratios, the phone output
inventory, raw-source availability and hashes, and caption/alt-text structure. Also inspect the
contact sheet and individual images visually before upload; format validation cannot prove their content.

## Release review

These are release-preparation assets. Verify that the final release candidate includes every
advertised feature and matches the captured UI. Recapture screens changed after this draft.
Check that built-in Mosh ships in the target main-app version; do not reuse the old “Mosh extension
available” wording. No separate extension is published by this work.

Tailscale is described as an existing network path to a reachable SSH server, with Tailscale setup
and access rules required. The loopback demo is not a Tailscale compatibility test. tmux/Herdr
screenshots likewise do not replace the project's real scrolling acceptance gates.

Recheck Google's requirements before upload:

- [Preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842)
- [SSH over Tailscale](https://tailscale.com/docs/reference/ssh-over-tailscale)
