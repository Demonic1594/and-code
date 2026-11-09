# What AndCode takes from the Cursor reverse-engineering report

Status: **done.** Every "Adopt" row is built: 10 (#110), 1 and 4 (#111), 6 (#112), 5 (#113), 2
(#114), 7 (#115), 3 (#116), 8 and 11 (#117) and 9 (#118). Their "AndCode today" cells say what was
there before, and each "What to build" cell ends with what was built where it differs.

## Source and method

The user shared a static analysis of Cursor 3.27.22 (arm64 .deb): findings 01 to 10, an
independent architecture, an implementation roadmap and experiment templates. It was read as notes,
not code: nothing from it is copied, and its runtime experiments were not run (the report could not
run them either). Every mechanism below was checked against AndCode's code on `main` and, for
Claude Code, against the CLI itself (2.1.316 on the device, 2.1.319 here), before deciding.

The deciding fact is where the agent loop lives. In Cursor the loop, the prompt and the model
routing are Cursor's own (server side, or its local loop for users who bring their own API key). In
AndCode the loop belongs to the CLI it hosts (Claude Code, Codex, Antigravity, OpenCode). So what
AndCode can take is what lives around the loop: what the user sees on the phone, how approvals work,
how processes are started and ended, what the browser tools give the agent, and how secrets are
stored. Mechanisms inside the loop are either already done by the CLI or out of reach.

## Adopt

| # | Cursor mechanism and how it works | AndCode today | What to build |
|---|---|---|---|
| 1 | **Retry and fallback shown to the user.** One shared retry step backs off with jitter, honours `Retry-After`, and the UI says "Rate limited by model provider, retrying..."; a slow or degraded model is flagged on the stream. | Before #111: Claude Code already retries and says so on its stream (`system` lines `api_retry` with attempt, limit and delay; `status` while compacting; `compact_boundary` with tokens before and after; `model_fallback` with the reason). AndCode reads every `system` line as the session start and shows none of it, so a rate-limited turn looks like a hang. | Read those lines and show a status line under the running turn ("Rate limited, retrying in 8 s (2 of 10)", "Compacting the conversation", "Switched to X: Y is overloaded"), plus a note in the chat when a compaction or model switch happened. |
| 2 | **Queued turns you can see and change.** Its background service keeps queued turns with calls to cancel, reorder and edit them, and the composer offers "queue" or "send now at the next tool call". | Before #114: With send behaviour "queue" (and always for Antigravity) a prompt sent during a turn goes into `messageQueue`. The composer shows a "Queued" pill while a turn runs in queue mode, but not the held prompts themselves, which cannot be edited or taken back; when Antigravity forces the queue, or a review is sent from the diff screen, not even the pill shows. | Show held prompts above the composer, each with Edit (back into the composer), Remove and Send now (interrupts the running turn, as the "interrupt" behaviour does). Reorder is left out: Remove and send again covers it on a small screen. Built (#114) as planned, except that Send now is not offered where a turn cannot be interrupted (Antigravity, which always queues). |
| 3 | **Context window breakdown.** Each turn carries a `context_window_status` split into system prompt, tools, rules, skills, MCP, subagents and conversation. | Before #116: A total in `CompactContextMeter`. Claude Code answers a `get_context_usage` control request with the same split (categories, MCP tools by server, memory files, agents, skills, the auto-compact threshold). | For Claude Code, tapping the meter opens a sheet with that split, so a user sees for example that MCP tools take 30k tokens and can turn a server off. OpenCode reports no split and keeps its total. Codex reports token usage on its app-server, which AndCode does not read yet: its usage figure and compact action are a later item (harness item 6). Built (#116); the sheet also lists memory files, and its Manage MCP servers button opens Claude Code's MCP settings. |
| 4 | **Stop that really stops.** Turns end with an explicit abort and close; processes the agent started are owned and ended. | Before #111: `killProcessTree` existed and Antigravity used it, but `ClaudeCodeRuntime.stop`/`stopAll`, its one-shot helpers, and `CodexRuntime`'s server stop use `destroyForcibly()`, which per `ProcessTreeKill` ends only proot: the CLI and anything it started detach and keep running. | Use the tree kill on those paths (audit item "Stop ends the whole tree"). |
| 5 | **Browser logs kept for the agent, and the missing input tools.** Console logs are persisted per browser tab (`~/.cursor/browser-logs`); the 16 browser tools include press key and scroll. | Before #113: The Guest Browser tools (`andcode-browser-mcp.py`) navigate, read, click, type and screenshot. JavaScript errors and failed loads are invisible to the agent, and it cannot press Enter or scroll. | The app writes console messages and failed loads (`onConsoleMessage`, `onReceivedError`, `onReceivedHttpError`) to a capped file next to `browser-status.json`; new tools `browser_console`, `browser_press_key` and `browser_scroll`. Built (#113) without a file: the page agent keeps console messages, exceptions, resources that failed to load and failed or error-status fetch and XHR requests for each page in memory (a page the agent never ran in, such as the WebView's own error page, has no log), and `browser_console` reads them with a cursor that notices a new page; `browser_press_key` and `browser_scroll` as planned. |
| 6 | **Decrypt retries before a reset.** Stored tokens are decrypted up to three times before the app treats the user as signed out; it does not delete on the first failure. | Before #112: `openWithRecovery` deleted the encrypted file after the first failed open (upstream-sync follow-up 2). The shared master key is kept once any file has opened with it, but if the first file to open fails twice the key is replaced even when only that file was the problem (follow-up 3). In fallback mode, schedule edits and deletes are lost on restart (follow-up 1). | Retry once before any delete; replace the master key only when the key itself fails a test encryption; keep durable markers so a schedule deleted or turned off in fallback mode stays so. |
| 7 | **A decision log.** The sandbox writes each allow or deny to a log file, one JSON line each, with the rule that matched. | Before #115: Approvals made on the phone, and rules remembered from them, leave no history (audit item "Audit log"). | Record each phone decision (time, tool, short input, answer, rule saved) in a capped log, listed on the Command rules screen. Built (#115): the app and the hook each keep their own capped file, shown together in a History section on the Command rules screen. |
| 8 | **MCP allowlist patterns.** `permissions.json` allows `server:tool`, `server:*` and `*:tool`. | Before #117: "Always allow" for an MCP tool covers that one tool, so the eight browser tools take eight approvals. | Offer "Always allow every tool from this server" on an MCP approval; the hook matches `mcp__<server>__*`. Built (#117); the rule is kept for the project it was approved in, like other rules, and applies everywhere when the call ran in `/`, which is no project. |
| 9 | **Hooks.** `hooks.json` with before/after tool, before shell, after edit, subagent start/stop, before compact and stop events, including a veto. | Before #118: Claude Code has the same hook system; AndCode only installs its permission hooks. | As harness item 7: opt-in templates for Claude Code ("after an edit, run ...", "before finishing, run ..."). The finishing check gives up after one retry (Claude Code's `stop_hook_active`), because the report advises against a hard build-must-pass gate. Built (#118) as Settings, Claude Code, Checks: one command after each edit and one before finishing, run as hooks passed with `--settings` through the permission hook script's `--check` mode. |
| 10 | **Instructions for unattended runs.** Background agents are told they run alone and the user reads the result later. | Before #110: The agent context says the user is on a phone, not that the last message is read later on its own (audit item "Self-contained final messages"). | One line in `and-code-agent-context.md`. |
| 11 | **Edits stay in the workspace even in "Run Everything".** `yoloOutsideWorkspaceDisabled` defaults to on. | Before #117: Claude Code 2.1.314 added `permissions.blockWritesOutsideWorkingDirectories` (Write, Edit and NotebookEdit refuse paths outside the working directories in every permission mode). AndCode does not set it. | An opt-in "Keep file edits inside the project" switch in Claude Code's settings, passed through `--settings`. Off by default: agents write scratch files to `/tmp` and config under the home directory, and Bash can still write anywhere, so it prevents mistakes rather than adding a boundary. Built (#117) as planned. |

## Already there (in AndCode or in the CLI)

- **Checkpoints separate from the user's git**: shadow snapshots (#58), with pruning.
- **Plan mode, todos, asking the user**: the Plan permission preset, TodoWrite rendering, and
  AskUserQuestion answered through the permission hook.
- **Command classification, allowlist, deny before ask, deny feedback, risk classes, protected
  paths for edits**: #66, #73, #74, #89, #90 (edit-by-kind never covers `.git`, `.claude`,
  `.mcp.json` or the bridge directory).
- **Accessibility snapshot with element handles**: `browser_read` handles (#85).
- **Screenshot size cap**: the browser tool saves a file and returns its path; Claude Code's Read
  tool scales images itself.
- **Why an MCP server failed**: #78.
- **Speculative compaction**: Claude Code compacts on its own, including an idle compaction that
  prepares a summary before it is needed (seen in its `get_context_usage` schema). A threshold
  setting in AndCode would add nothing.
- **Point-in-time git status**: Claude Code already injects git status labelled as a snapshot from
  the start of the conversation. Audit item closed.
- **Large tool output to a file for the model**: Claude Code saves large outputs to a file and
  tells the model the path. On the phone, long lines and long output have their own viewer (#75, #86).
- **Prompt-cache-friendly prefix**: AndCode's instruction file holds no time, git or device state,
  so it does not break the CLI's prompt cache. Keep it that way: volatile facts belong in a message,
  not in the instruction file.

## Not adopted, and why

- **Server-side model catalog, Auto routing, per-task model configs, best-of-N with a judge,
  fast-apply model, cache-warming calls, per-model tool naming**: inside the loop, owned by each CLI
  and its provider. Best-of-N would also multiply token use, which matters most on a phone plan.
- **Semantic index with server embeddings, the "crepe" trigram index**: the CLIs search with
  ripgrep; an index would cost battery and storage for little gain on a phone (already in the
  harness plan's "Not planned").
- **Shadow workspace (hidden editor running real language servers on virtual edits)**: the most
  distinctive accuracy mechanism in the report, but it needs language servers kept running, which a
  phone cannot afford. Agents can run the project's own build and tests; hook templates (row 9) cover
  "check before finishing".
- **OS sandbox (namespaces, Landlock, seccomp) and a default-deny network proxy**: not available to
  an unrooted Android app under PRoot (already "Not planned"). Cursor's own docs say its classifier is
  not a security boundary; ours is not either.
- **Refusing Write/Edit outside the project in the bridge** (audit item): not built in the bridge,
  because Claude Code does it (row 11). It already asks before writing outside its working directory
  in the Ask and Accept-edits presets, and those prompts reach the phone.
- **Cloud, persistent and orchestrator agents, forks, worker pools, computer use**: need servers or
  desktop control; out of scope.
- **Codebase telemetry uploads, request attestation headers, a hot-updated harness**: Cursor
  infrastructure with no counterpart here; the app ships as one APK and each CLI updates itself.
- **Streaming text pacing** (smoothing token bursts per frame): not judged without a device check of
  how the transcript renders today; revisit only if it looks jumpy.

## Later

- **Worktree per chat** (Cursor isolates parallel agents only when asked): two chats on one project
  share its checkout and can overwrite each other. Useful, but a larger change to projects and chats.
- **Side question** (Cursor's side chats; Claude Code's `side_question` control request behind
  `/btw`): ask something without derailing the running turn.
- **Subagent files**: Claude Code reads `.claude/agents/*.md` itself; a list and editor in the app,
  like the rules shortcut, is left for later (the suggested MCP servers, #MCPPR, cover servers only).
- **One-tap MCP install** (Cursor's `mcp/install` deep link): only with a confirmation screen, as
  part of the harness plan's "one place to add MCP servers and skills". Built (#MCPPR) as suggested
  servers whose Add fills the add form for the user to confirm; no deep link is handled.

## Order

1. This document, the final-message line (row 10), and plan statuses brought up to date.
2. Rows 1 and 4: stream status for retries, compaction and fallback; the tree kill.
3. Row 2: visible queue.
4. Row 6: encrypted storage follow-ups.
5. Row 5: browser console, keys and scroll.
6. Row 3: context breakdown.
7. Rows 7, 8 and 11: decision log, server-wide MCP rules, and the edits-inside-the-project switch.
8. Row 9: hook templates.

## Tests

- Stream status: parser unit tests on recorded `system` lines from the CLI.
- Queue: view-model tests for edit, remove and send-now.
- Encrypted storage: `RecoveringOpener` and schedule marker unit tests (no device needed).
- Browser: the script test (`BrowserMcpScriptTest`) for the new tools' replies, and
  `BrowserAgentToolsTest` for the page agent's log; the WebView callbacks need a device check.
- Tree kill, context sheet, hooks: unit tests where pure, then a device check, per repo convention.
