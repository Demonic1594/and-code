# Agent harness improvements (from a Cursor comparison)

Status: **in progress**. Item 3 (shadow snapshots) is done (#58); the rest is not started.

## Goal

Make agents run through AndCode more useful on a phone. Cursor's published harness was the
reference point. This doc lists what AndCode lacks, what is feasible with what we already have,
and an order to build in. Cursor's cloud agents, Bugbot, its own trained model and its OS sandbox
are out of scope: they need infrastructure AndCode does not have, and AndCode's PRoot rootfs
already isolates the agent.

## What AndCode already has

- Permission presets per agent (`ClaudePermissionMode`, Antigravity `--mode plan`) and a
  permission bridge hook (`ClaudePermissionHooks`) that lets a phone approve a tool call.
- Per-agent instruction files written by `AndCodeAgentContext` (CLAUDE.md, GEMINI.md, OpenCode
  instructions).
- A send behaviour setting (`sendBehavior`, default interrupt) for steering a running agent.
- Checkpoint and unrevert, but only through the OpenCode API.
- A context-used figure and `summarizeSession` (OpenCode only).
- `SubagentsTrack` in the chat UI.

## Gaps and proposed work

Ordered by value for the phone user, not by effort.

### 1. Command allowlist with "always allow"

Feasible, easy. The approval sheet gets an "Always allow this command" action that stores a
pattern (for example `git status`, `npm test`) per project. Matching calls skip the prompt.
Cursor's plain-English rules are not needed; prefix patterns cover most cases.

- Storage: per-project settings, not global.
- Mobile fit: very good; directly removes repeated approval taps.
- Test: pattern matching as a unit test, including prefix boundaries (`git status` must not
  match `git statusfoo`).

### 2. `@` mentions in the composer

Feasible, medium. An `@` button opens a picker with recent workspace files, the current git diff,
and the last terminal output. Chosen items are attached to the prompt as text or file references.

- Data: file listing from the runtime; diff from `GitClient`; terminal output from the existing
  terminal buffer.
- Mobile fit: good only as a picker. Typing paths on a phone is the problem being removed.

### 3. Undo for every agent (shadow snapshots) — done (#58)

Feasible, medium. Before each agent turn, snapshot the workspace into a private git repository
kept in the app's own directory (not the user's repo). "Undo to here" on a message restores the
snapshot. This extends undo to Claude Code, Codex and Antigravity, which currently have none.

- Risks: disk use on large repositories; snapshots must ignore `node_modules` and build output.
- Mobile fit: good, if it is a single action on the message.

### 4. Rules shortcut

Feasible, easy. A shortcut from the agent settings that opens `AGENTS.md` (or the active agent's
instruction file) in the workspace file editor.

- Nested rule files only help agents that read them. Confirm per agent before promising
  per-directory rules.

### 5. Long tool output written to a file

Partly feasible. Long shell and MCP output is stored as a file and the transcript shows a
truncated card with "Open full output", as the Git panel already does. The agent is told where the
file is.

- Benefit is mainly token cost. Users barely see it.
- Needs each agent to be told where to look; verify per CLI.

### 6. Context meter and compact action for every agent

Unclear. The meter only works where the CLI reports usage. "Compact" only works where the CLI has
a command for it. Neither is verified for Claude Code, Codex or Antigravity.

- Step one: check what each CLI reports and supports, then decide.

### 7. User hooks

Hard on mobile. Hooks are powerful (block a call, rewrite input, feed a follow-up message back
into the agent when it stops), but a raw hook editor is not usable on a phone.

- Ship as templates instead, for example "run tests after an edit" or "block `rm -rf`".
- Start with Claude Code only, which has its own hook system.

### 8. Additions from reading Operit (Android agent app, LGPL-3.0)

Read from its source at commit `dbf7191`, not built or run. Ideas only; its code must not be
copied (LGPL-3.0 against AndCode's MIT).

- **Allowlist: add Deny and a timeout (extends item 1).** Operit has allow, ask and forbid per
  tool, with an "Always allow" choice on the prompt and a 60-second timeout that counts as not
  granted. Ours should match command patterns, which is finer than its per-tool-name levels, and
  add a Deny list so `rm -rf` style commands are blocked outright. An unanswered prompt should
  time out as not granted.
- **Start a saved prompt from an intent or Tasker.** Operit accepts a broadcast intent with a
  message, a chat id, a new-chat flag, a timeout and a reply action. We already have schedules, a
  quick-settings tile and shortcuts; an intent that runs a saved prompt in a chosen project fits
  next to them. Do not copy its auth: its intent receiver is exported with no permission or token
  (`AndroidManifest.xml:385`), so any installed app can send the agent a prompt. Ours needs a
  signature permission or a per-user token, because our agents run shell commands.
- **Say why an MCP server did not start.** Operit reports a status such as `NODEJS_MISSING` or
  `BRIDGE_FAILED`. Show the specific reason in the MCP list.
- **Ollama and LM Studio presets.** A base-URL preset with a "test connection" button, if the
  custom provider settings accept a base URL (not yet checked).
- **One place to add MCP servers and skills.** A curated list with one-tap install, using
  `npx` or `uvx` inside the existing Linux environment. Medium effort; what MCP management exists
  today is not yet checked.
- **Reference for the guest-browser plan (PR #34).** Operit's browser tools are snapshot, click
  and type by element `ref`, fill form, press key, console messages, network requests, evaluate,
  file upload, resize and screenshot. It tags elements with an `aria-ref` attribute in the
  snapshot. That is the same design as the handle-based `browser_read` in the PR #34 review.

## Not planned

- Cursor's cloud agents, Bugbot and its trained model: need server infrastructure.
- Cursor's OS sandbox: AndCode's PRoot rootfs already isolates the agent, and Cursor itself says its
  classifier is not a security boundary.
- Semantic code search with an embedding index: heavy for a phone; rely on grep and ripgrep.
- From Operit: phone control through Accessibility, Shizuku or Root (large security surface and
  store-policy risk, and not about coding); a visual workflow builder (poor on a small screen);
  local models through llama.cpp or MNN; memory spaces, characters and avatars.

## Suggested order

1. Command allowlist.
2. `@` mentions.
3. Shadow snapshots.
4. Rules shortcut.
5. Verify the context meter and compact support per CLI (item 6), then decide.
6. Long tool output to file.
7. Hook templates for Claude Code.
8. Operit-derived items (section 8): the intent trigger with a token, MCP failure reasons,
   Ollama and LM Studio presets, then the MCP and skills list.

## Open questions

- Which CLIs read nested instruction files?
- Does each CLI accept a compact command, and what does it report for context usage?
- Snapshot storage limit and cleanup policy.
- Do the custom provider settings accept a base URL, and what MCP management exists today?
- Which authentication fits the intent trigger: a signature permission or a per-user token?

## Tests

- Allowlist pattern matching: unit test, including prefix boundaries.
- Snapshot restore: an instrumented test on a temporary repository.
- Everything else needs a device check. Verify by hand, per repo convention.
