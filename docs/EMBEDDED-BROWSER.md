# Embedded workspace browser

The **Code server** button on Connections opens the server page without starting
an SSH connection. The terminal toolbar's **Project tools** menu offers independent actions:
**Native files & diff**, **Code server**, and **Browser**. The browser is also available in the
session's long-press menu, including when that SSH session is offline. It does not
depend on loading a Git repository. The native project toolbar's **Code server** action
opens the selected worktree through code-server's `folder` query parameter.

## Choose the tab

**Code server** opens your remote editor, remembers its server address per SSH
host and can save its login password. Its address, password and layout controls
are in the tab menu, keeping the editor area clear.

**Browser** opens a separate tab for ordinary websites with a visible address bar.
Enter an HTTP or HTTPS address and tap **Go**. It remembers its own last entered
address and layout, independently of Code server. The Code server password prompt
and Native diff action are absent from this tab. Both tabs retain their live pages
when switching to another tab; each can be closed separately.

For computer and phone setup, open **Settings → User guide → Setup & reference**
and expand **Tailscale** or **Code server**. These sections explain which device to
configure, include a copyable Serve command, and link to the official instructions.

## Connect an existing server

Enter the HTTP or HTTPS address of an existing code-server, for example a Tailscale
Serve HTTPS address with its configured port. An omitted scheme defaults to HTTPS.
Invisible direction/word-joining marks introduced by copying an address from chat
are removed before validation. An address-storage failure does not masquerade as
an invalid URL or prevent the page from opening.
Proxy subpaths and query parameters are preserved. Addresses and phone/desktop
layout preferences are stored per SSH host, port and user. No address is inferred
from a terminal title, hardcoded for a particular computer, or published to a service.

Opening a verified code-server login page shows a native password prompt, with
**Remember on this device** selected. A successful login saves the password using
the existing AES-256-GCM credential implementation and a dedicated non-exportable
Android Keystore key. Failed logins do not replace saved passwords. WebView's
app-private persistent cookies retain the ordinary session; when a fresh login is
needed, the saved password is submitted once, without an automatic retry loop.
Android Chrome's login is separate. SSH passwords and private keys are never used.

**Password** in the Code server tab menu opens password management, including **Forget
saved password**. Password records are scoped to the exact scheme, host, port and
reverse-proxy base path. Only a same-origin, matching-path code-server password
POST form receives an autofill; redirects to another server do not receive it.
Passwords are not placed in URLs, logs, Activity saved state or portable backups.
The password prompt can be cancelled to use the website's own form instead; that
manual website entry is not captured by the app.
Tailscale runs separately on the phone and host. This feature does not configure
Tailscale, install code-server, start a server, or expose a new port.

The browser appears as a **Code server** tab in the terminal's existing tab strip.
Tap a terminal tab or the Code server tab to switch without reloading the editor.
The browser has no separate header or toolbar. The selected tab's overflow button
(or a long press on the tab) opens Back, Forward, Reload, Project home, Address,
Password, and phone/desktop layout actions. Closing the tab releases its live page. **Native diff** opens the originating terminal's native
project reader when its SSH connection is available. Android Back navigates browser
history before returning to the terminal. Layout changes reload the current page;
Project home returns to the configured address and selected worktree.

The live editor is retained in memory while returning to the terminal and across
Activity recreation. Up to three host browsers are retained, using application
context and detaching UI callbacks when hidden. Opening another worktree on the
same host replaces that host's browser. Process death, renderer death, or eviction
can reload a page: save important edits using the remote editor. Address/layout
preferences and ordinary persistent website cookies survive process restarts.

Website upload controls use Android's document picker; only returned content URIs
are passed back. Pop-up windows, automatic external-app launching, and native
JavaScript bridges are not provided. Website download handling is not implemented.

## Network and lifecycle boundary

The browser uses Android's system WebView inside the existing Kotlin/Compose app.
It is not part of the native terminal renderer, parser, viewport or transport.
It remains inside MainActivity's app-lock and secure-window boundary. A browser
failure does not close the SSH/Mosh terminal. Renderer termination produces a
reloadable error rather than terminating the application.

HTTPS certificate failures are rejected; there is no trust-all fallback. Mixed
content, local file access, and third-party cookies are disabled. Top-level
navigation is limited to HTTPS and the exact HTTP origin explicitly entered by the
user. HTTP subresource requests to other origins are blocked as well. Supporting a
user-supplied HTTP endpoint requires `usesCleartextTraffic=true` in the application
manifest; the WebView client imposes the origin restriction above. Prefer a valid
Tailscale HTTPS address. Android 17 local-network permission is requested when an
entered endpoint resolves to a local-network address; public addresses do not
trigger that permission solely because the browser was opened.

There is no new Android dependency or bundled IDE code. The remote server and its
extensions remain separately installed software, supplied by the user.

## Verification

`BrowserAddressTest` verifies Tailscale addresses, proxy paths, project query
encoding, HTTP-origin restrictions, rejection of privileged schemes and embedded
credentials, and desktop user-agent conversion. `BrowserWorkspaceTest` uses a real
WebView and local HTTP server to verify DOM storage, live unsaved editor retention,
navigation, layout switching, a separate ordinary-browser address bar, independent
server addresses, equal-height shared tab chrome, and live editor
retention when switching between terminal and browser tabs. `BrowserLoginTest` exercises wrong-password
rejection, successful login, ciphertext-only persistence through browser/controller
recreation, automatic re-login and explicit forgetting on a real Android WebView.
Exact Tailscale-address parsing is also checked on Android. These tests do not substitute for a particular user's
code-server configuration, extensions, authentication provider, or tailnet routing.
