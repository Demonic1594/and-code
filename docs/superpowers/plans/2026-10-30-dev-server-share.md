# Share a dev server the agent is running, for other people to test

Status: **built**. Decisions made while building:

- **The tunnel is a supervised process** (`share-<port>`, see the process supervisor plan), not a
  service of its own. It gets the supervisor's wake lock, notification with Stop all, time limit
  (two hours) and log for free; the public address is read from that log. It is never restarted on
  its own: a tunnel that died shows as failed, not as a new address. This replaces tasks 2 and 7.
- **No pinned version.** `CloudflaredReleaseClient` reads Cloudflare's latest release and pins the
  download to the SHA-256 digest GitHub reports for the asset, the trust model the Antigravity
  client already uses. A release without a digest is refused. arm64, armv7, x86_64 and x86 builds.
- **The link ends with the server**: when the shared port stops answering, when the terminal's
  shell ends, and when the terminal screen closes.
- **The QR code** is drawn with zxing's `QRCodeWriter`, already a dependency for scanning.
- **More than one server can be shared at once** (one tunnel per port), replacing the "one tunnel
  at a time" limit below: with the supervisor holding each tunnel there was no reason to refuse it.
  The supervisor's cap on running processes still applies.
- **The tunnel points at the loopback address the server answers on** (`127.0.0.1`, else `[::1]`),
  never `localhost`, so an IPv6-only dev server is reachable and nothing beyond this device is.
- **Agents can read a link**: the tunnel is a supervised process, so `andcode-run list` and
  `andcode-run logs` show it to the agents. They could start a tunnel themselves anyway, so this
  grants nothing new, but a shared link should be treated as known to the agent.

## Goal

The terminal already notices when the shell (and so the agent, since it drives the same shell)
starts a local server and lets you open it in the in-app browser (`DevServerDetector`,
`DevServerTracker`, `TerminalServerChip`). This plan adds one more action on that chip: **Share**,
which stands up a public tunnel to the same port and hands back a URL (and a QR code) another
person can open from anywhere, while the agent that is already running the server stays live on
this device.

The use case this is for: an agent has just built and started a test server for a feature; instead
of screen-sharing or exporting a build, the user taps Share and sends the link to a teammate who
can click it and interact with the running app directly, with the on-device agent as the one and
only backend — first-hand, no separate deploy step. This was converged on independently by two of
the OpenCode-focused mobile clients surveyed (both wrap `cloudflared`/`ngrok` tunnels for the
*opposite* direction — reaching their own remote `opencode serve` from outside a LAN); AndCode's
version is for exposing a server the on-device agent itself started, which is a better fit for
AndCode's on-device architecture than either of theirs.

## What exists today

- `DevServerDetector.portsIn()` (`feature/terminal/DevServerDetector.kt`) scans terminal output for
  a port a program claims to be listening on.
- `DevServerTracker` (`feature/terminal/DevServerTracker.kt`) probes each candidate port on
  loopback and tracks it as `RUNNING`/`STOPPED`; `TerminalViewModel` (`:86` `serverProbe`, `:100`
  `tracker`, `:233-242` detection, `:288-289` applying a probe round) republishes the live list as
  `TerminalUiState.servers`.
- `TerminalScreen.kt` renders one chip per server (`TerminalServerChip` at `:219`) inside
  `TerminalServers` (`:200`); tapping a `RUNNING` chip calls `onOpenServer(server.url)`
  (`:101`, `:123`), which opens `http://localhost:<port>/` in the in-app browser
  (`feature/browser/GuestBrowserScreen.kt`). There is no other action on the chip today.
- `DevServerProbe`/`LoopbackDevServerProbe` (`runtime/local/DevServerProbe.kt`) only ever connects
  to `127.0.0.1`/`::1` — nothing in the app today reaches outside the device for this feature.
- The PRoot guest already runs arbitrary Linux binaries with real network access (git, node, the
  agents themselves); `VerifiedRuntimeDownloader` (`runtime/local/VerifiedRuntimeDownloader.kt`)
  is the established pattern for fetching and SHA-256-verifying a binary before running it
  (used by `*ReleaseClient` classes for each agent's own CLI).
- QR code **generation** needs no new dependency: `com.journeyapps:zxing-android-embedded:4.3.0`
  (`app/build.gradle.kts:306`) is already present (today only used to *scan* a QR when adding a
  remote connection, `feature/workspace/RemoteConnectionScreen.kt:101,346`), and its `zxing-core`
  transitive dependency can encode one (`QRCodeWriter`) with no extra library.
- `LocalRuntimeService` (`runtime/local/LocalRuntimeService.kt:290`) already runs a foreground
  notification while the runtime has active work (`:322`, `:666`) — the pattern a live tunnel
  should reuse, so the OS and the user both know something is reachable from outside while it
  runs.

## Design

### Tunnel mechanism: `cloudflared` quick tunnels

Use Cloudflare's "quick tunnel" mode (`cloudflared tunnel --url http://localhost:<port>`), not
ngrok, because it needs **no account, no auth token, and no signup flow** — it prints a random
`https://<random-words>.trycloudflare.com` URL on stderr and starts proxying immediately. ngrok's
free tier requires an account and an authtoken typed into the app, which is an extra setup step
with its own support burden for a feature that should be a single tap. Quick tunnels are meant
for exactly this (temporary, ad-hoc sharing) and are explicitly not for production traffic, which
matches the "let a teammate try this build" use case and nothing more.

- Ship a verified `cloudflared` static Linux binary per ABI (arm64/armv7/x86_64, matching how
  Claude Code/Antigravity/Codex binaries are already fetched and pinned), resolved the same way as
  the existing `*ReleaseClient`s: pin a version, verify SHA-256 via `VerifiedRuntimeDownloader`,
  cache it under the runtime directory.
- Run it inside the same PRoot rootfs the terminal and agents already use (reusing
  `LocalRuntimeCommandRunner`/the terminal's process-running path, per `docs/LOCAL_RUNTIME.md`'s
  existing "no separate sandbox" model — the tunnel sees exactly what the terminal sees, nothing
  more).
- Parse stdout/stderr for the `https://*.trycloudflare.com` line (cloudflared prints it in a
  predictable boxed format); surface a clear "starting…" state until it appears, and a clear
  failure state if the process exits first (no network, binary missing, Cloudflare's quick-tunnel
  service down — it is a best-effort free service, not an SLA).

### UI

- `TerminalServerChip`: add a **Share** icon action, shown only while `RUNNING` (sharing a stopped
  server makes no sense).
- Tapping it opens a bottom sheet: a short, explicit warning line ("This makes `localhost:<port>`
  reachable by anyone with the link, for as long as this stays open") above a "Start sharing"
  button — not implicit, this is a real security decision, so no auto-start on tap.
- Once live: the `trycloudflare.com` URL as selectable text with a copy button, a generated QR
  code (reusing the existing zxing rendering path the scanner already depends on, just the encode
  direction), and a prominent "Stop sharing" button.
- A persistent notification (alongside/reusing `LocalRuntimeService`'s existing notification
  channel) while any tunnel is live, with its own "Stop sharing" action — so a tunnel can never be
  forgotten and left open after the user leaves the app, which would be the actual security
  footgun of this feature if done carelessly.
- Closing the terminal, stopping the shell, or the shared port going `STOPPED` (per
  `DevServerTracker`) all tear the tunnel down automatically — it must never outlive the server it
  was pointed at.

### Scope limit for this plan

One tunnel at a time, tied to the terminal's own lifecycle (not a background-forever feature).
Multiple simultaneous shared servers, or sharing surviving app close, are explicitly deferred (see
Out of scope) — get the single-tunnel, explicit-start/stop flow right first.

## Tasks

1. `CloudflaredReleaseClient` + pinned per-ABI binary manifest, following the existing
   `*ReleaseClient` + `VerifiedRuntimeDownloader` pattern.
2. A `TunnelSession` class: starts `cloudflared` against a given port inside the guest, exposes a
   `StateFlow` of `Starting`/`Live(url)`/`Failed(reason)`/`Stopped`, parses the boxed stdout for
   the URL, and kills the process group on stop (reuse whatever the terminal/`ProcessTree` already
   uses to end a process tree cleanly — `runtime/local/ProcessTree.kt`).
3. Wire `TunnelSession` lifecycle to the owning `DevServer`'s status leaving `RUNNING` (auto-stop).
4. `TerminalViewModel`: own zero-or-one active `TunnelSession`, expose its state to the screen.
5. `TerminalScreen.kt`: Share icon on the chip, the warning/start/stop bottom sheet, the live URL
   + QR + copy + stop UI.
6. QR encode helper (small, local — `QRCodeWriter` → `Bitmap`), likely worth factoring out next to
   the existing scan code in `feature/workspace` rather than duplicating zxing setup.
7. Foreground-notification integration: reuse `LocalRuntimeService`'s channel, add the tunnel's
   own notification while live, with a stop action that calls back into `TunnelSession`.
8. Strings (warning copy, button labels, starting/failed states) in all 8 locales.

## Tests

- `TunnelSession`'s stdout parser against fixed sample `cloudflared` output (success line, and a
  few failure/startup variants) — this is the one piece of real parsing logic and the most likely
  thing to break on a `cloudflared` version bump.
- Auto-stop-on-server-stopped as a unit test against `DevServerTracker` state transitions.
- Not unit-testable: an actual network tunnel to `trycloudflare.com`. Verify on a device.

## Confirm on the device after building

1. Start a trivial server in the terminal (e.g. `python3 -m http.server 8000`); tap Share, read
   the warning, start it; open the printed URL from a *different* network (mobile data, not the
   same Wi-Fi) and confirm it reaches the phone's server.
2. Stop the server in the terminal: the tunnel and its notification both go away without being
   told to separately.
3. Background the app with a tunnel live: the notification persists and its Stop action works.
4. No network / Cloudflare's quick-tunnel endpoint unreachable: a clear failure, not a silent hang
   on "starting…".

## Out of scope

- ngrok, or any tunnel provider that needs an account/authtoken typed into the app.
- More than one simultaneous shared server.
- A tunnel that outlives the terminal/app process (this is deliberately "while I'm actively
  sharing", not a persistent public endpoint).
- Password-protecting or otherwise restricting who can open the link beyond "whoever has the
  URL" — quick tunnels do not support that, and adding it would mean a different, heavier tunnel
  product; revisit only if this ships and people ask for it.
