# Fork features

Features this fork added on top of upstream AndCode. The reasoning behind each one is in
[`AGENTIC_CODING_LANDSCAPE.md`](AGENTIC_CODING_LANDSCAPE.md). Token-usage changes have their own
page: [`TOKEN_EFFICIENCY.md`](TOKEN_EFFICIENCY.md).

## Look and feel

| Feature | Where | Notes |
|---------|-------|-------|
| Material You theme | Settings → Appearance → Theme → **Dynamic** | Android 12+. Colors come from the wallpaper and follow the system's light/dark setting. Status colors (running, error, diff tints) keep their fixed meaning. |
| JetBrains Mono for code | everywhere code is shown | Code blocks, diffs, the code viewer, the editor and the terminal. The "NL" cut is used, so `!=` and `->` look as typed. Licensed under OFL 1.1 (Settings → Legal → License texts). |
| Haptic feedback | Settings → Appearance → **Haptic feedback** | Confirm on send, approve, save and commit. Reject on deny and failures. A tick on pinning, and a buzz on long-press. On by default. |
| Screen transitions | – | A short fade-through between screens instead of a hard cut. |

## Chats

| Feature | Where | Notes |
|---------|-------|-------|
| Search chats | Drawer → search field above the chat list | Matches every word against the chat title and its project folder. |
| Pin chats | Long-press a chat in the drawer → pin icon | Pinned chats get their own section at the top. Long-press again to unpin. |
| Saved prompts | Settings → Chat → **Saved prompts**; type `/` in the composer | Each saved prompt answers to `/name`. Picking it from the popup puts its text in the composer to edit first; sending a message that is exactly `/name` sends the saved text. A prompt can't take the name of a built-in command. |
| Share or copy a chat | Chat → ⋮ → **Share chat** / **Copy as Markdown** | Markdown with one heading per turn, tool calls summarised and reasoning left out. Shared as a `.md` file, so long chats don't hit Android's intent size limit. |
| Usage readout | Chat → ⋮ | Tokens in and out for the whole chat, plus cost when the runtime reports one (OpenCode does). |

## Workspace (on-device runtime)

These work on the runtime on this device, where AndCode can run `git` and write files itself. On a
remote PC runtime the buttons stay hidden.

| Feature | Where | Notes |
|---------|-------|-------|
| Edit files | Code viewer → pencil icon | Save writes the file in place and keeps its mode, so an executable script stays executable. Back with unsaved edits asks first. Files up to 1 MB. |
| Commit | Workspace → Changes → **Commit** | Stages everything (except the `.andcode-attachments` copies AndCode makes for the agent) and commits with your message. A fresh runtime with no git identity commits as `AndCode <andcode@localhost>`. A configured identity always wins. |
| Pull / Push / Stash | Workspace → Changes | Pull is fast-forward only. Push uses `-u origin HEAD`, so a new branch's first push just works. Git's own error text is shown when something fails. |
| Switch / create branch | Workspace → Changes → tap the branch name | This sheet existed before but was never connected. It now lists real branches and switches between them. |
| Recent commits | Workspace → Changes | The last 15 commits. |

The command runner now mounts `/workspace` the same way the agents see it. The terminal can reach
it too.

## Launcher and system

| Feature | Where | Notes |
|---------|-------|-------|
| App shortcuts | Long-press the app icon | New chat, Workspaces, Schedules. Ignored until onboarding is done. |
| Quick Settings tile | Edit Quick Settings → **AndCode prompt** | Opens the quick-prompt box from the home screen widget. Asks you to unlock first. |
| Schedule templates | Schedules → new schedule → **Start from a template** | Daily change summary, weekly dependency check, nightly test run, TODO sweep, monthly security review. Each one fills in the name, prompt and timing. Templates are only offered for a new schedule. |

## Not done yet

Researched and ranked in the landscape doc but not built: checkpoints / rewind, per-workspace
rules, an AI-written commit message button, an ongoing "run in progress" notification, an
interactive PTY terminal, one worktree per chat, best-of-N attempts, inline diff comments, E2E
relay pairing, spec-driven templates, an agent tasks inbox, a dev-server preview button and an
issue-to-chat entry point.
