# Screenshot example data

`atlas/` is an intentionally small sample workspace for a disposable SSH server. Its shell script
reads real directory, file, and disk information. It makes no network requests or modifications.
It is example content displayed by the terminal, not a new app feature or performance evidence.

Copy this directory into the isolated screenshot fixture's `/home/terminal/projects/atlas` and
set its owner to the fixture's `terminal` user. Run `./scripts/workspace.sh` in a real SSH terminal.
Use the same files for the SFTP capture. No private projects, keys or credentials belong here.

Suggested reusable snippets (save in the app, do not execute against a personal server):

- Working tree → `git status --short`
- Disk space → `df -h`
- Recent commits → `git log --oneline -5`
- Listening ports → `ss -tln`

The port-forwarding example is local `127.0.0.1:5173 → 127.0.0.1:5173`. The screenshot shows the rule editor,
not a running web server; the rule is cancelled after capture. The phone key example shows the
native generation dialog with the name `Atlas demo key`.
The dialog is cancelled after capture; no key is generated or authorized on a server.
