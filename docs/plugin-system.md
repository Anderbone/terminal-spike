# Extensions and monitoring retired

At the user’s request on 2026-11-12, the Extensions catalog and its installation
state were removed, followed by Server monitor, Codex quota and their dependent
home-screen widget. Their settings/search entries, collectors, parsers, widget
receiver, startup registration, resources and feature-specific tests are removed.
No monitoring or quota polling remains from this feature.

Terminal Spike still ships one complete phone APK. Arch Linux has its own
Settings entry and retains its existing rootfs installation, storage reporting
and explicit data deletion. Mosh is bundled and available directly; its
diagnostics remain under Settings → Advanced → Mosh. Legacy plugin records no
longer block either feature. Existing Linux files and saved connections remain.

No watch bridge was implemented. Terminal input/status features maintained
separately are outside this removal.
