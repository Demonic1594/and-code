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
| Search inside messages | Drawer → type in the search field → **Search messages for "…"** | Reads every chat's messages through its agent, a few chats at a time, and lists the chats that contain the text, best matches first, each with a line of context. **Include archived** adds the Archive. A chat that was not changed since the last search is not read again. Tapping a result opens the chat with the same text already in its find bar. |
| Find in chat | Chat → 🔍 in the top bar | Highlights every row that contains the text, shows "3 of 12", and jumps between matches with ↑/↓, starting at the newest. Finds the reasoning and tool calls inside collapsed activity groups too. Back closes it. |
| Pin chats | Long-press a chat in the drawer → pin icon | Pinned chats get their own section at the top. Long-press again to unpin. Auto-archive never touches a pinned chat, and up to two pinned chats also appear as launcher shortcuts. |
| Saved prompts | Settings → Chat → **Saved prompts**; type `/` in the composer | Each saved prompt answers to `/name`. Picking it from the popup puts its text in the composer to edit first; sending `/name` sends the saved text. Arguments work like Claude Code's custom commands: `/fix-issue 123 high` fills `$ARGUMENTS` with everything after the command and `$1`, `$2`… with single words. A prompt without placeholders gets the arguments appended. Picking a prompt that takes arguments from the popup inserts `/name ` so you can type them. A prompt can't take the name of a built-in command. |
| Delete for real | Drawer → long-press a chat → delete | The chat is gone everywhere, not just hidden: Claude Code's transcript, todos and file history in the runtime, Antigravity's conversation files, the Codex thread and the OpenCode session, plus AndCode's own pin, draft and unread mark. One chat goes at once with **Undo** on the snackbar for 5 seconds; several ask first. If an agent refuses (an older Codex without `thread/delete`, say), the snackbar says why instead of the chat quietly coming back. |
| Archive | Drawer → **Archive**, or Settings → Sessions → **Archived chats** | Every agent's archived chats in one list, newest first, with search. Tap to read one; ⋮ or long-press to **Restore** or **Delete forever** (several at once too), or ⋮ → **Delete all archived**. Archiving from the drawer has Undo. A restored chat counts as fresh for auto-archive, so the next pass does not archive it again. The legacy OpenCode API cannot un-archive, so there a chat can only be deleted. |
| Share or copy a chat | Chat → ⋮ → **Share chat** / **Copy as Markdown** | Markdown with one heading per turn, tool calls summarised and reasoning left out. Shared as a `.md` file, so long chats don't hit Android's intent size limit. |
| Usage readout | Chat → ⋮ | Tokens in and out for the whole chat, plus cost when the runtime reports one (OpenCode does). Cache reads appear on their own line, since they cost a fraction of fresh input. |
| Run settings | Chat → the tune pill under the message box | One sheet for everything that decides how the next turn runs: agent or permission mode, auto-accept, thinking level, fast mode and the system prompt preset. Each section shows only when the runtime offers it, and changes apply at once. The pill names the mode, thinking level and fast mode, and turns accented when anything differs from a fresh chat. |
| Starter prompts | A new, empty chat | Shows the agent's mark, "What should we work on?" and four starters (explain the project, fix a bug, write tests, review changes). Tapping one fills the message box without sending, so you can add the file or symptom first. |

## Workspace (on-device runtime)

These work on the runtime on this device, where AndCode can run `git` and write files itself. On a
remote PC runtime the buttons stay hidden.

| Feature | Where | Notes |
|---------|-------|-------|
| Edit files | Code viewer → pencil icon | Save writes the file in place and keeps its mode, so an executable script stays executable. Back with unsaved edits asks first. Files up to 1 MB. Undo/redo (a burst of typing is one step) and find & replace-all (literal text, optional ignore-case; one undo reverts it). |
| Commit | Workspace → Changes → **Commit** | Stages everything (except the `.andcode-attachments` copies AndCode makes for the agent) and commits with your message. A fresh runtime with no git identity commits as `AndCode <andcode@localhost>`. A configured identity always wins. The message is prefilled from the changed files ("Update Main.kt and 2 more files") and selected, so typing replaces it. |
| Discard a file | Workspace → Changes → ↶ on a file | Asks first. A tracked file goes back to its last commit; a new file is deleted. |
| Pull / Push / Stash | Workspace → Changes | Pull is fast-forward only. Push uses `-u origin HEAD`, so a new branch's first push just works. Git's own error text is shown when something fails. |
| Switch / create branch | Workspace → Changes → tap the branch name | This sheet existed before but was never connected. It now lists real branches and switches between them. |
| Recent commits | Workspace → Changes | The last 15 commits. |

The command runner now mounts `/workspace` the same way the agents see it. The terminal can reach
it too.

## Launcher and system

| Feature | Where | Notes |
|---------|-------|-------|
| App shortcuts | Long-press the app icon | Up to two pinned chats, then New chat, Workspaces and Schedules, cut to as many as the launcher shows. Ignored until onboarding is done. |
| Quick Settings tile | Edit Quick Settings → **AndCode prompt** | Opens the quick-prompt box from the home screen widget. Asks you to unlock first. |
| Schedule templates | Schedules → new schedule → **Start from a template** | Daily change summary, weekly dependency check, nightly test run, TODO sweep, monthly security review. Each one fills in the name, prompt and timing. Templates are only offered for a new schedule. |
| Live run notification | Notification shade, while a chat runs | One silent, ongoing notification: the chat's title, its agent and the step in flight ("bash · ./gradlew test"), a running clock and **Stop**. Several running chats share one notification that lists each. Subagent sessions are left out; their parent stands for them. Its own channel, **Running chats**, can be turned off in Android's notification settings. |
| Reply from a notification | "Chat finished" and "Chat failed" notices → **Reply** | Type the next instruction in the shade; it is sent to that chat and the live notification takes over. If sending fails, the notice comes back with Reply on it. Approval notices already have Allow once, Always allow and Reject. |

## Not done yet

Researched and ranked in the landscape doc but not built: checkpoints / rewind, per-workspace
rules, an AI-written commit message button, saved prompts stored as files in the repo, an
interactive PTY terminal, one worktree per chat, best-of-N attempts, inline diff comments, E2E
relay pairing, spec-driven templates, an agent tasks inbox, a dev-server preview button and an
issue-to-chat entry point.
