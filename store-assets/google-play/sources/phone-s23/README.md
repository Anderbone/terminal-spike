# Phone capture recipe

These are real Terminal Spike captures for the English Play listing refresh. Final captures and
build/device provenance are listed in `manifest.json`. Never add a screenshot of personal hosts,
credentials, notifications, or a live work transcript to this directory.

## Capture setup

1. Resolve the authorized S23 with `adb devices -l`, verify `ro.product.model` is `SM-S911B`, and
   use its exact USB serial for every command. Do not capture while another session runs tests.
2. Use the latest verified debug build for drafts. Before a release upload, check that its UI
   matches the final release candidate and recapture changed screens.
3. Use the disposable OpenSSH fixture on loopback, reachable through an explicitly targeted
   `adb reverse tcp:22222 tcp:22222`. Use a separate Compose project, not somebody else's running
   test server. Do not expose its public test password on the LAN.
4. Create only demo data: a saved host named `Atlas workspace`, tmux sessions `atlas`, `docs`,
   and `workbench`, and command snippets such as `git status --short` and `df -h`.
5. Capture app screens with `adb -s <verified-serial> exec-out screencap -p`. Keep the raw PNGs.
   Terminal output must come from the real remote shell. Never fabricate a successful test,
   connection status, throughput figure, or remote tool UI in the artwork.
6. Frame captures with `build-phone-artwork.py`. Only crop system bars and scale uniformly;
   headline text stays outside the app image. The top 340px is less than 20% of the 1920px canvas.
7. Review every image at full resolution and in the contact sheet. Check example data,
   clipping, keyboard visibility, spelling, and that each headline matches the visible feature.
8. Remove the temporary reverse and shut down only the screenshot fixture. Leave user data and
   unrelated device processes alone.

## Intended reading order

1. tmux + Herdr. Anywhere. — a real SSH terminal with readable example output.
2. Pick up where you left off. — the app's remote session picker.
3. Less typing. More doing. — reusable command snippets.
4. Your keys. Your access. — the native SSH key-generation dialog, with no key material displayed.
5. Make it your terminal. — themes and an actual terminal preview.
6. Local ports. Remote work. — a local TCP forwarding rule for a development server.
7. Files within reach. — real SFTP demo files.

Tailscale compatibility belongs in the listing copy; no Tailscale connection is claimed by the
loopback screenshot fixture. Likewise, the pictures are product examples, not performance or
real-workflow acceptance evidence for tmux/Herdr scrolling.
