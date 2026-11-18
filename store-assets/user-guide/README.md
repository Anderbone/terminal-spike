# User guide recordings

The visual lessons use recordings of the actual Android `MainActivity`, accessory
keys, Snippets editor, Android keyboard, and remote Herdr UI. The SSH connection
uses a disposable, loopback-only demo host with sample Atlas, Notes and website
directories. These are product demonstrations, not terminal acceptance or
performance evidence.

Canonical full recordings live in `store-assets/user-guide/source/` as `typing.mp4`,
`snippets.mp4`, `projects.mp4` and `gestures.mp4`. They remain part of the repository
and its complete source archive, outside Android's packaged resources.
`recordings.json` preserves capture provenance and the historical camera positions. Focused excerpts
only pan and crop the recorded pixels; they do not redraw or replace controls.
The first-install tour has four steps: typing, Snippets, switching workspaces and Herdr layout.
Settings uses a single index and continuous pager for all twelve lessons. Any
lesson can be opened directly, then browsed with left/right swipes or Back/Next.
The same lesson definitions supply the four essential first-install pages; the
eight additional tips stay out of first install. Each page has one real screenshot
or a 4–8 second loop, a title and one caption. Longer documentation is reached
through **Setup & reference** at the end of the index.
`visuals.json` records every source, trim, crop, playback speed and poster frame. No controls are
redrawn. Playback is silent,
offline, pausable, stops when the guide is backgrounded, and never sends input
to a terminal. Lossless WebP stills also work with animations disabled or when
video playback is unavailable.

Regenerate the current lesson clips and stills with installed FFmpeg:

```sh
python3 scripts/build-guide-recordings.py
```

The previous short-only command remains an alias for this same operation:

```sh
python3 scripts/build-guide-recordings.py --short-only
```

Both commands emit only the eleven posters and seven loops referenced by `GuideContent.kt`.
They do not package full recordings or regenerate the retired long clips and keyboard tip.

To import a fresh capture directory containing `typing-source.mp4`,
`snippets-source.mp4`, `projects-source.mp4` and `gestures-source.mp4`:

```sh
python3 scripts/build-guide-recordings.py --capture-dir /path/to/captures
```

This replaces the four canonical source recordings, then rebuilds the current lesson resources.
All four captures finish encoding in a temporary directory before any existing canonical file
is replaced. An encoding failure preserves the previous recording set and removes partial files.
The final file replacements are sequential; this is not a filesystem transaction across four files.
Use either `--capture-dir` or `--short-only`; the two modes cannot be combined.

Use a disposable emulator and current APK to recapture changed UI. The debug-only
`GuideCaptureActivity` can seed sample Snippets and connect to its loopback SSH
fixture using a generated key from its private `files/guide-capture-key`. Pass
the fixture username with the `username` intent extra. It refuses physical
phones and is absent from release builds. Keep keys, fixture state and raw
logs out of Git. Never use a personal host, credentials or live work transcript.
Android keyboards vary between phones; the app controls in these recordings
are unmodified. Review each loop, still and caption together before packaging.
