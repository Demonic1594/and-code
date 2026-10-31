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
| One design language | Every screen | One card style, spacing and shape scale, grouped settings with icon tiles, consistent top bars, restyled chat and drawer, a welcome screen with a logo hero, and a grouped file explorer (folders first, compact path bar, animated folder changes). |

## Chats

| Feature | Where | Notes |
|---------|-------|-------|
| Search chats | Drawer → search field above **Recent chats** | Matches every word against the chat title and its project folder. Typing opens Recent chats if it was collapsed; clearing the field puts it back as you left it. |
| Search inside messages | Drawer → type in the search field → **Search messages for "…"** | Reads every chat's messages through its agent, a few chats at a time, and lists the chats that contain the text, best matches first, each with a line of context. **Include archived** adds the Archive. A chat that was not changed since the last search is not read again. Tapping a result opens the chat with the same text already in its find bar. |
| Find in chat | Chat → 🔍 in the top bar | Highlights every row that contains the text, shows "3 of 12", and jumps between matches with ↑/↓, starting at the newest. Finds the reasoning and tool calls inside collapsed activity groups too. Back closes it. |
| Pin chats | Long-press a chat in the drawer → pin icon | Pinned chats get their own section at the top. Long-press again to unpin. Auto-archive never touches a pinned chat, and up to two pinned chats also appear as launcher shortcuts. |
| Saved prompts | Settings → Chat → **Saved prompts**; type `/` in the composer | Each saved prompt answers to `/name`. Picking it from the popup puts its text in the composer to edit first; sending `/name` sends the saved text. Arguments work like Claude Code's custom commands: `/fix-issue 123 high` fills `$ARGUMENTS` with everything after the command and `$1`, `$2`… with single words. A prompt without placeholders gets the arguments appended. Picking a prompt that takes arguments from the popup inserts `/name ` so you can type them. A prompt can't take the name of a built-in command. |
| Delete for real | Drawer → long-press a chat → delete | The chat is gone everywhere, not just hidden: Claude Code's transcript, todos and file history in the runtime, Antigravity's conversation files, the Codex thread and the OpenCode session, plus AndCode's own pin, draft and unread mark. One chat goes at once with **Undo** on the snackbar for 5 seconds; several ask first. If an agent refuses (an older Codex without `thread/delete`, say), the snackbar says why instead of the chat quietly coming back. |
| Archive | Drawer → **Archive**, or Settings → Sessions → **Archived chats** | Every agent's archived chats in one list, newest first, with search. Tap to read one; ⋮ or long-press to **Restore** or **Delete forever** (several at once too), or ⋮ → **Delete all archived**. Archiving from the drawer has Undo. A restored chat counts as fresh for auto-archive, so the next pass does not archive it again. The legacy OpenCode API cannot un-archive, so there a chat can only be deleted. |
| Share or copy a chat | Chat → ⋮ → **Share chat** / **Copy as Markdown** | Markdown with one heading per turn, tool calls summarised and reasoning left out. Shared as a `.md` file, so long chats don't hit Android's intent size limit. |
| Open the repository | Chat → ⋮ → **Open on GitHub** | Shown when the chat's project folder was cloned from GitHub (its `origin` points at github.com), on the on-device runtime. Opens the repository page. |
| Copy tool input or output | Chat → expand a tool card → copy icon beside **Input** or **Output** | Each block sits on its own code surface with a copy button that turns into a check mark for two seconds. |
| Find a project | Drawer → 🔍 on the **Projects** header | Filters the project chips by name. **Add project** sits on the same header, so it no longer scrolls off with the chips. |
| Usage readout | Chat → ⋮ | Tokens in and out for the whole chat, plus cost when the runtime reports one (OpenCode does). Cache reads appear on their own line, since they cost a fraction of fresh input. |
| Usage page | Drawer → **Usage** | Tokens and cost across every chat for 7, 30 or 90 days or all time (archived chats optional): totals, chats, turns, daily average and cache hit rate, a day-by-day stacked chart (tap a day for its numbers), the token mix, and breakdowns by model, by agent and by the chats that used the most. Chats are read through their runtimes a few at a time and cached while a chat is unchanged, so the page fills in as they arrive. Claude Code usage is recorded too, and only what a runtime reports is counted. |
| Stays live after you leave | Open chat, after the app was in the background | After about 10 seconds or more away, coming back to the app reconnects the live stream at once (it used to wait up to 30 seconds), and the open chat re-reads its transcript, so replies written while you were away show up without reopening the session from the drawer. A short glance away changes nothing. While a reply is running and nothing has arrived for 12 seconds, or the stream is down, the chat catches up from the transcript on its own, less often the longer it stays quiet. A reply that finished while the stream was silent (quiet for 12 seconds or more) is wrapped up straight away. Text the transcript has ahead of the stream is taken; text that is further along on screen is kept, so nothing is lost or doubled. One bad event no longer ends the stream. |
| Run settings | Chat → the tune pill under the message box | One sheet for everything that decides how the next turn runs: agent or permission mode, auto-accept, thinking level, fast mode and the system prompt preset. Each section shows only when the runtime offers it, and changes apply at once. The pill names the mode, thinking level and fast mode, and turns accented when anything differs from a fresh chat. |
| Starter prompts | A new, empty chat | Shows the agent's mark, "What should we work on?" and four starters (explain the project, fix a bug, write tests, review changes). Tapping one fills the message box without sending, so you can add the file or symptom first. |
| Changes card and review | Under any answer that edited files → **Review changes** | The card lists the files that answer changed. The review screen shows every file with its +/− counts (tap a file to fold it) and a line-numbered diff. Tap a line to leave a comment; **Send to agent** sends all comments to the chat as one message that names each file, line and the code on it. Needs a runtime that can produce diffs (a Git workspace). |
| Artifacts | Chat → ⋮ → **Artifacts**, or **Preview** on a file in the Changes card | Pages, drawings, documents and pictures the agent wrote (HTML, SVG, Markdown, PNG/JPG/GIF/WebP) open in a full-screen preview: pages run in a sandboxed web view, Markdown renders, pictures show; **Source** flips to the text and Share sends it to another app. Local servers the agent started or mentioned (`http://localhost:5173`) are listed too and open in the in-app browser, as do localhost links anywhere in the app. Pages have a **desktop layout** toggle (1280 px wide, zoomed out to fit) and pinch zoom; long Markdown documents and sources are shown whole, block by block and line by line. |
| Inbox | Drawer → **Inbox** (the row under the agents counts what waits on you and what is running) | Chats in one list: approvals with Reject / Allow once / Always allow on the card, running chats with their current step and **Stop**, and chats that finished unread with **Reply** and **Mark as read**. Running and finished chats come from every agent; approvals and inline replies are for the selected agent, whose events the app follows (another agent's chat opens instead). A chat waiting on a question shows as running. A reply goes out with the agent and model the chat last used. Tap a chat to open it. |
| Rewind (checkpoints) | Long-press one of your messages → **Rewind to here** (OpenCode chats) | Puts the files the agent changed from that message on back to how they were, hides that message and the ones after it, and returns its text to the message box to send again as is or changed. A banner offers **Undo** until the next message, which makes the rewind permanent. OpenCode restores from snapshots it keeps apart from the project's own git history; it needs a git project, and outside one only the conversation rewinds. Claude Code, Codex, Antigravity and chats on the newer OpenCode protocol have no rewind. |

## Workspace (on-device runtime)

These work on the runtime on this device, where AndCode can run `git` and write files itself. On a
remote PC runtime the buttons stay hidden.

| Feature | Where | Notes |
|---------|-------|-------|
| Code editor | Code viewer → pencil icon | Syntax colours for about 30 file types from a single-pass lexer (multi-line strings and comments, markup with embedded script and style, JSON and YAML keys, CSS properties, Markdown), in the palette of the chosen syntax theme with a matching surface and gutter. Colours as you type, line numbers that follow wrapped lines, current line and matching bracket marked, auto-indent on Enter. A **key bar** above the keyboard: indent and outdent, arrows, line start and end, paired brackets that you step over, symbols, comment toggle, duplicate, delete, move and select line. **Find and replace** inline with match case, whole word and regular expressions with group references, a match count and next/previous from the cursor. Go to line, text size, wrap, and a status bar with line, column, selection, language and indent. Each command is its own undo step; typing still groups by pauses. Save writes the file in place and keeps its mode, so an executable script stays executable. Back with unsaved edits asks first. Files up to 1 MB; the reader stays lazy for big files and colours them the same way. |
| Git panel | Workspace file explorer → the top bar | A full source-control screen: the branch with its upstream, ahead/behind counts, fetch, pull and push; changes split into staged and unstaged with per-file and bulk stage, unstage and discard, conflict markers and a diff viewer; a commit box with amend (with nothing staged, and not amending, it commits everything); paged history with refs and merge markers, and commit details with each file's diff; local and remote branches to switch, create and delete (forced if you choose); stashes to save with a message, pop, apply and drop. Every path, ref, hash and stash name is quoted or validated before it reaches git. |
| Commit | Workspace → Changes → **Commit** | Stages everything (except the `.andcode-attachments` copies AndCode makes for the agent) and commits with your message. A fresh runtime with no git identity commits as `AndCode <andcode@localhost>`. A configured identity always wins. The message is prefilled from the changed files ("Update Main.kt and 2 more files") and selected, so typing replaces it. |
| Discard a file | Workspace → Changes → ↶ on a file | Asks first. A tracked file goes back to its last commit; a new file is deleted. |
| Pull / Push / Stash | Workspace → Changes | Pull is fast-forward only. Push uses `-u origin HEAD`, so a new branch's first push just works. Git's own error text is shown when something fails. |
| Switch / create branch | Workspace → Changes → tap the branch name | This sheet existed before but was never connected. It now lists real branches and switches between them. |
| Recent commits | Workspace → Changes | The last 15 commits. |

The command runner now mounts `/workspace` the same way the agents see it. The terminal can reach
it too.

## Browser and DevTools

| Feature | Where | Notes |
|---------|-------|-------|
| Guest Browser | Settings → **Guest Browser**, a local server listed under Artifacts, or any localhost link | A browser for the pages the agent serves on the phone. An address pill on top shows the page's title and host (local, secure or not secure) and turns into an address field on tap; controls sit along the bottom within thumb reach; a failed load shows an error screen with Try again; the start screen lists the common dev-server ports one tap away. The ⋮ menu has **Desktop site** (desktop Chrome's user agent, laid out 1280 px wide), **Find in page** with a match count, zoom, reload without cache, copy link and open in the phone's browser. Typed addresses and find results survive page loads. |
| DevTools: Console | Browser → DevTools dock under the page (drag to resize) | Every console method, uncaught errors and rejections, levels, filter, collapsed repeats, groups, expandable object trees and stack traces; long-press to copy. A prompt runs JavaScript in the page (promises included) with command history. |
| DevTools: Network | same | `fetch`, XHR and WebSocket traffic with headers, payloads, responses (JSON pretty-printed and browsable) and frames, plus every other resource from resource timing. Type chips, failed-only, preserve log, a waterfall, and copy URL / as cURL / response. |
| DevTools: Elements | same | A lazily loaded DOM tree, tap-to-inspect on the page with a highlight overlay, matched CSS rules, computed styles, a box-model diagram and outer HTML; attributes can be edited and elements deleted. |
| DevTools: Storage, Sources, Performance | same | Local storage, session storage and cookies, editable. Every script and stylesheet, readable with line numbers. A load timeline, paint and load metrics, heap, DOM size, resource totals and the page's environment. |

A small page agent (`assets/devtools/andcode-devtools.js`) is installed as a document-start script
and reports to the app over a WebMessage listener, using `androidx.webkit` (Apache-2.0, listed in
the notices). DevTools only report on pages in the Guest Browser.

## Storage

| Feature | Where | Notes |
|---------|-------|-------|
| Storage manager | Settings → **Storage** | What AndCode uses against the device's free space, as one stacked bar, then a breakdown that adds up to the total without counting anything twice: projects, attachments, agents, agent data, the Linux environment, caches, logs, install leftovers, temporary files, the update backup, the voice model and everything else. **Clean up** frees caches, logs and the leftovers of unfinished installs, per area or all at once, after a confirmation; the update backup, agents, agent data and projects are never offered. A **folder browser** opens any folder sorted by size, with a Largest files list; projects and cleanable places can be deleted there after a confirmation, everything else shows as managed by the app. |

Cleaning is built not to hurt a working install: it refuses while a chat, a scheduled run, an install
or an update is at work (and checks again before each folder), never follows a symbolic link out of
the app's folders, keeps the `.l2s.*` files PRoot uses to emulate hard links (projects can point into
them), and inside the Linux environment only touches package caches known to copy rather than link
(`.npm/_cacache`, `.npm/_logs`, `.cache/pip`). Folders outside it are renamed into a trash folder
before they are deleted, so one an install creates again a moment later is left alone.

## Accounts

| Feature | Where | Notes |
|---------|-------|-------|
| GitHub personal access token | Settings → **GitHub / Git Operations** | A token is checked with GitHub before it is kept, and **Create a token** opens GitHub with the repo and workflow scopes ticked. A build without an OAuth client ID offers the token as the way in instead of a dead button; with one, both are there. A refused token is told apart from GitHub being unavailable. |

## Launcher and system

| Feature | Where | Notes |
|---------|-------|-------|
| App shortcuts | Long-press the app icon | Up to two pinned chats, then New chat, Workspaces and Schedules, cut to as many as the launcher shows. Ignored until onboarding is done. |
| Quick Settings tile | Edit Quick Settings → **AndCode prompt** | Opens the quick-prompt box from the home screen widget. Asks you to unlock first. |
| Schedule templates | Schedules → new schedule → **Start from a template** | Daily change summary, weekly dependency check, nightly test run, TODO sweep, monthly security review. Each one fills in the name, prompt and timing. Templates are only offered for a new schedule. |
| Live run notification | Notification shade, while a chat runs | One silent, ongoing notification: the chat's title, its agent and the step in flight ("bash · ./gradlew test"), a running clock and **Stop**. Several running chats share one notification that lists each. Subagent sessions are left out; their parent stands for them. Its own channel, **Running chats**, can be turned off in Android's notification settings. |
| Reply from a notification | "Chat finished" and "Chat failed" notices → **Reply** | Type the next instruction in the shade; it is sent to that chat and the live notification takes over. If sending fails, the notice comes back with Reply on it. Approval notices already have Allow once, Always allow and Reject. |
| App lock | Settings → Privacy → **Require unlock to open** | Off by default. Asks for the phone's fingerprint, face or screen lock (PIN, pattern, password) when the app opens and when it comes back after more than 30 seconds away, so the share sheet or a browser sign-in does not ask again. Turning it on does not lock what is already open, and rotating the screen never unlocks it. **Also require it to send a message** asks again before each message, review and Inbox reply goes out; cancelling keeps the text. The recents thumbnail is hidden while the lock is on (Android 13+). The voice assistant, which can open over the lock screen where no prompt can be shown, sends nothing while the phone itself is locked. On a phone with no screen lock the switch is greyed out with the reason. |

## Not done yet

Researched and ranked in the landscape doc but not built: per-workspace
rules, an AI-written commit message button, saved prompts stored as files in the repo, an
interactive PTY terminal, one worktree per chat, best-of-N attempts, E2E
relay pairing, spec-driven templates, a dev-server preview button and an
issue-to-chat entry point.
