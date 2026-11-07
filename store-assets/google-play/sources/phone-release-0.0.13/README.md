# Signed release capture recipe

These eight English phone images show the real main application from the signed
0.0.13 release candidate on an isolated API 35 phone emulator. The manifest records
the installed APK hash, device configuration and every unmodified capture hash.
No debug-only capture screen, synthetic app UI, personal account or work transcript
is used. This is listing artwork, not terminal acceptance or performance evidence.

Use a dedicated emulator at 1080 × 1920, density 360, with the normal dark app theme.
Install the verified signed release APK and launch MainActivity. Build a separate
loopback-only SSH fixture with public demo credentials and a small Atlas Git project.
Use actual shell output, a real Git change, the native project reader and SFTP browser.
A separate code-server instance opens the same demo files; an original sample web
page supplies browser content. Keep all keys, credentials and server state outside Git.

Add the Working tree, Review changes and Recent commits snippets through the app.
Enable their display above the keyboard in Settings → Snippets. Capture the native
terminal, file comparison, code-server, browser, snippet management, buffered draft,
SFTP file list and Settings → User guide. Capture only after the interface settles.

Resolve and explicitly target the dedicated emulator for every ADB command. Use
`exec-out screencap -p` and retain the original PNGs. The frame builder only scales
these images uniformly and adds headlines outside the app image; it never redraws
controls or changes terminal output. Recheck hashes and view the contact sheet and
each full-resolution output before upload. Stop only this capture fixture afterward.

Rebuild with the repository's phone-artwork builder and this manifest. The older
S23 source set remains separately archived and is not part of this phone refresh.
