# Antigravity local runtime

The Android app provisions the official Google Antigravity CLI release in the shared Alpine/PRoot environment. Release 1.1.7 is pinned in `AntigravityManifest` and every archive is downloaded through `VerifiedRuntimeDownloader` and SHA-256 checked before activation.

## Versions and updates

`agy --version` boots the whole bundled language server before it prints anything — over a minute on
device — so the app does not ask the binary which release it is. `AntigravityInstaller` writes
`usr/local/share/and-code/antigravity-version` after the SHA-256-verified binary is swapped in, and
`AntigravityRuntime.version` reads that marker. Before the marker existed the app simply reported
`AntigravityManifest.VERSION` for any present binary, which went stale as soon as a new release was
pinned: the card claimed a version the guest was not running. A sandbox provisioned by one of those
builds has no marker and still falls back to the pinned version, since nothing recorded what it
installed.

Antigravity comes from a version pinned in the app rather than a package repository, so the update
check needs no network: the card compares the marker against `AntigravityManifest.VERSION` and offers
`AntigravityController.update` when they differ. That update goes through
`LocalRuntimeInstaller.updateAntigravity`, which re-verifies the release and swaps the single binary
in place — a full reinstall would rebuild the whole environment directory to replace one file. The
outcome is reported as `Updated(from, to)` or `AlreadyLatest(version)` so an update that changed
nothing is distinguishable from one that did.

The Termux fork is reference material only. Its native-Termux wrapper and patched binaries are not bundled or used. The runtime uses Alpine `gcompat`, `util-linux`, and CA certificates; an ABI/loader failure is reported as unsupported instead of silently installing an unofficial binary.

OAuth is a remote PTY flow. The browser URL and one-time code are passed to the PTY, while credentials remain in `/root/.gemini` inside the Linux rootfs and are never copied into Android preferences. `AGY_CLI_DISABLE_AUTO_UPDATE=1` is set so updates remain verified and app-controlled.

## Why the sign-in URL needs an explicit PTY window size

`agy` is a Bubble Tea full-screen program, and the sign-in chooser plus the OAuth URL are rendered
frames, not printed lines. `script` allocates a PTY but only inherits a window size from its own
controlling terminal; launched from `ProcessBuilder` it has pipes instead, so the slave PTY reports
`0 0` and the TUI paints an empty frame. The CLI then looks like it starts, logs `CLI ready for user
input` and `You are not logged into Antigravity`, and never emits a URL.

`AntigravitySandboxLauncher` therefore runs `stty rows/cols` inside the PTY before `exec`ing agy. The
column count is deliberately far wider than a phone screen: the CLI hard-wraps the ~520 character
URL to the terminal width, and a wide terminal keeps it on one line. `AntigravityAuthParser` still
stitches wrapped fragments back together so a narrower terminal cannot silently truncate the URL.

Two further conditions matter. Every agy invocation declares the session as a remote shell
(`SSH_CONNECTION`/`SSH_CLIENT`/`SSH_TTY`), because the token store is chosen per process: sign-in
writes a file-based token, so a later `agy models` started without those markers would look in a
keyring that does not exist in PRoot and report the user as signed out. And `settings.json` in the
guest is rewritten through the JSON serializer and repaired on every launch - a malformed file is
silently ignored by the CLI, which re-enables the alternate screen buffer and makes the transcript
much harder to parse.

## Working directory and project instructions

A chat's `agy` starts in that chat's project folder (PRoot `-w`), the way Claude Code gets `-w` and
Codex gets `cwd`, when the folder is in `/workspace` or device storage and still exists. Those are
the bind mounts agy's Debian guest shares with the Alpine guest the folder picker browses. A chat with
no folder, a folder elsewhere in the Alpine guest (`/root/project`, which agy's guest does not have)
or a deleted one starts in `/workspace` instead: PRoot would otherwise start agy in `/`, with the
whole guest as its workspace. Sign-in, `agy models` and chat naming belong to no project and also
start in `/workspace`. In the pinned 1.1.7, `entrypoints.launchCLI`
makes the working directory the first workspace folder, then adds each `--add-dir`, before it hands
over to `printmode.Run`. So the project folder is agy's workspace.

`agy` reads instructions from the project as well as from `~/.gemini/GEMINI.md` (where AndCode keeps
its own section on `andcode-run`). It loads `AGENTS.md` and `GEMINI.md` from the folder it starts in
and each parent up to the repository root, plus `.agents/rules/*.md`, and adds them together. The
pinned 1.1.7 release says so in the reference text it carries ("Directory-Based Rules (`GEMINI.md` /
`AGENTS.md`) ... walks up from the current working directory to the repository root"), as do the
official docs (antigravity.google/docs/rules and /docs/customization-discovery). Before agy started in
the project folder it started in `/workspace`, the parent of every project, and a project's own file
was not found. Antigravity's settings open the project's `AGENTS.md`, which is always on for its
folder, and Codex and OpenCode read the same file. The file is created and edited through the Alpine
guest, so for a chat folder outside `/workspace` and device storage the settings say so instead of
opening an `AGENTS.md` agy would never read.

Two things outside the project folder still work. A chat's attachments are written under
`/workspace/.andcode-attachments`, so the chat's folder there is passed with `--add-dir` and stays
inside agy's workspace, earlier turns' attachments included, as it was when agy started in
`/workspace`. And `trustedWorkspaces` in the guest
`settings.json` lists only `/workspace`, which 1.1.7 compares as an exact path, so a project folder
is not on it. That list only matters to the TUI's "Do you trust the contents of this project?" step
(`store.derivePhase`): print mode waits for sign-in and eligibility, never for trust. The TUI
sign-in still starts in `/workspace`.

Conversations are kept under the guest's `~/.gemini`, not per folder, so `--conversation` resumes a
chat that started out in `/workspace`.

Hook records use schema version 1 JSONL and contain only conversation/transcript paths, event type, tool metadata, step and stop reason. Tokens, prompts and environment variables must not be written to the hook bridge. Session records retain the app UUID, Antigravity conversation id, workspace and last step so a killed process can be resumed.

Image and PDF attachments are decoded into a turn-specific directory under `/workspace/.andcode-attachments`, added to agy's workspace with `--add-dir` (see above), and passed to print mode through Antigravity's native `@path` context mentions. The original file parts are stored in the app transcript, matching the OpenCode and Claude Code targets, so attachments survive completion, reconnects and app restarts. Unsupported attachment types fail the send instead of appearing attached only in the Android UI.

Device acceptance still requires an x86_64 emulator and arm64 device: install and digest verification, `agy --version`, `models`, browser OAuth, a smoke prompt, file/tool use, permission/question flows, abort, session switching, network recovery and task-kill relaunch. An APK build or version command alone is not completion evidence.
