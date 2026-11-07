# In-app release notes

Settings → About → View update history opens the bundled, offline release history.
After an upgrade, the unlocked Workspace also shows unread entries in one dismissible
dialog. Terminal work, connection prompts, permissions and startup recovery take priority.
Fresh installations record the current baseline without showing an update dialog.

For each public release with user-visible changes:

1. Add resource-backed text to `app/src/main/res/values/release_notes_strings.xml`.
   Describe user-visible features, improvements and fixes, including where to find new controls.
2. Prepend an entry to `bundledReleaseNotes` in `ui/releasenotes/ReleaseNotes.kt` with
   the release's version name and a new, increasing revision. Keep previous entries.
3. Keep that revision unchanged for rebuilds of the same release. APK version codes
   are independent; installing a new build with the same notes does not repeat them.
4. Update the Play release summary separately when publishing. Do not describe an
   unverified workflow as fixed, and do not copy internal commit messages into the UI.

The optional `AppSettings.release_notes_seen_revision` field distinguishes older
installations from an acknowledged baseline of zero. The first launch uses Android's
first-install and last-update timestamps when the marker is absent. Acknowledgement
is persisted only on dismissal and never moves backwards, including after a downgrade.
Portable backups omit this device-local field; restoring settings preserves the local
marker through the existing base-settings merge. Viewing history remains available offline.

Verification: `scripts/verify-android.sh`. Focused UI coverage is
`com.yanjiyu.terminalspike.ui.ReleaseNotesDialogTest`; run it only on an emulator or an
authorized test phone, never on the Wi-Fi foldable.
