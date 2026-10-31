# Agent harness improvements (from a Cursor comparison)

Status: **in progress**. Item 3 (shadow snapshots) is done (#58); the rest is not started.

## Goal

Make agents run through AndCode more useful on a phone. Cursor's published harness was the
reference point. This doc lists what AndCode lacks, what is feasible with what we already have,
and an order to build in. Cursor's cloud agents, Bugbot, its own trained model and its OS sandbox
are out of scope: they need infrastructure AndCode does not have. AndCode's PRoot rootfs
isolates only the guest filesystem view; it does not stop agent commands from reaching the host
paths bound into the guest (`/dev`, `/proc`, `/sys`, `/system`, the workspace, the permission
bridge directory when Claude Code is launched, and device storage when all-files access is
granted).

## What AndCode already has

- Permission presets per agent (`ClaudePermissionMode`, Antigravity `--mode plan`) and a
  permission bridge hook (`ClaudePermissionHooks`) that lets a phone approve a tool call.
- Per-agent instruction files written by `AndCodeAgentContext` (CLAUDE.md, GEMINI.md, OpenCode
  instructions).
- A send behaviour setting (`sendBehavior`, default interrupt) for steering a running agent.
- Checkpoint and unrevert through the OpenCode API, and file restore to before a message for
  every other on-device agent through shadow snapshots (#58).
- A context-used figure and `summarizeSession` (OpenCode only).
- `SubagentsTrack` in the chat UI.

## Gaps and proposed work

Ordered by value for the phone user, not by effort.

### 1. Command allowlist with "always allow"

Matching made shell-safe (`ShellCommandRules`): a command that chains, pipes, substitutes or
redirects never matches a rule and is never remembered as one, and rule words must equal the
command's first words. Wiring the rules into the prompt bypass, per-project scope and Deny are
still to do.

Feasible, easy. The approval sheet gets an "Always allow this command" action that stores a
pattern (for example `git status`, `npm test`) per project. Matching calls skip the prompt.
Cursor's plain-English rules are not needed; prefix patterns cover most cases.

The existing `ClaudePermissionBridge.isAlwaysAllowed` matches with `startsWith` on the first two
tokens, so `git status; rm -rf x` would match an allowed `git status`. Nothing calls it today, but
before it is wired into the prompt bypass, match on parsed arguments or refuse any command that
contains shell operators (`;`, `&&`, `||`, `|`, newlines, `$(...)`, backticks).

- Storage: per-project settings, not global. `ClaudePermissionBridge` keeps `always-rules.json`
  under its host root and `AlwaysRule` carries no project, so scope the bridge by project (or
  persist the project in each rule and match on it) as a design step before reusing the rules.
- Mobile fit: very good; directly removes repeated approval taps.
- Test: pattern matching as a unit test, including prefix boundaries (`git status` must not
  match `git statusfoo`), and commands with `;`, `&&`, newlines, substitutions and alternate
  `rm` forms.

### 2. `@` mentions in the composer

Feasible, medium. An `@` button opens a picker with recent workspace files, the current git diff,
and the last terminal output. Chosen items are attached to the prompt as text or file references.

- Data: file listing from the runtime; diff from `GitClient`; terminal output from the existing
  terminal buffer.
- Mobile fit: good only as a picker. Typing paths on a phone is the problem being removed.

### 3. Undo for every agent (shadow snapshots) — done (#58)

Feasible, medium. Before each agent turn, snapshot the workspace into a private git repository
kept in the app's own directory (not the user's repo). "Undo to here" on a message restores the
snapshot. This extends undo to Claude Code, Codex and Antigravity. Built in #58.

- Risks: disk use on large repositories (`node_modules` and build output are already excluded);
  pruning is tracked as item 8 of `2026-10-31-upstream-sync-follow-ups.md`.
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
copied without a licence review (LGPL-3.0, while AndCode is MIT); record any notices that apply.

- **Allowlist: add Deny and a timeout (extends item 1).** Operit has allow, ask and forbid per
  tool, with an "Always allow" choice on the prompt and a 60-second timeout that counts as not
  granted. Ours should match command patterns, which is finer than its per-tool-name levels, and
  add a Deny list so `rm -rf` style commands are blocked outright. An unanswered prompt should
  time out as not granted.
- **Start a saved prompt from an intent or Tasker.** Operit accepts a broadcast intent with a
  message, a chat id, a new-chat flag, a timeout and a reply action. We already have schedules, a
  quick-settings tile and shortcuts; an intent that runs a saved prompt in a chosen project fits
  next to them. Do not copy its auth: its intent receiver is exported with no permission or token
  (the external chat receiver in its `AndroidManifest.xml`), so any installed app can send the agent a prompt. Ours needs a
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

### 9. Additions from an audit of another coding-agent harness

From an audit document the user shared (a desktop agent harness, read as notes, not code). Each
item is an idea to check against AndCode, not a claim about how AndCode works today.

- **Deny before ask.** Evaluate deny rules before a prompt reaches the phone, matched on the tool,
  its input and any network target. Extends item 1.
- **Tool risk classes.** Mark tools read-only, writing, executing or destructive; read-only calls
  never prompt, and "always allow" can attach to a class as well as to command words.
- **Deny feedback.** A rejected call should tell the agent that the user declined and that it should
  adjust rather than retry the same call, so agents do not loop on a refused command.
- **Writes outside the workspace.** Refuse in the bridge a write whose path resolves outside the
  workspace root (the guest also sees bound host paths; see the top of this doc).
- **Audit log.** Record each phone approval and each remembered rule (tool, input summary, decision,
  time) so a user can see and revoke what was allowed.
- **Section budgets for instructions.** Cap each section of the generated instruction files and MCP
  tool listings instead of truncating the whole prompt, with a note when something was cut.
- **Point-in-time context.** Inject git status as a labelled snapshot ("as of the start of this
  turn"), so an agent does not treat it as live.
- **Compaction triggers.** Where an agent supports compacting (item 6), trigger it from AndCode's usage
  figures with per-model thresholds, and also when the agent reports a context-exceeded error.
- **Tool output caps.** Cap what is shown on the phone and what goes back to the model per tool,
  keeping the head and tail (overlaps item 5).
- **Stop ends the whole tree.** Stop should end the agent and every child process it started; check
  this against the supervisor and `ProcessTree`.
- **Self-contained final messages.** Instruction-file line: the user reads results later on a phone,
  so the last message of a run has to stand on its own.

## Not planned

- Cursor's cloud agents, Bugbot and its trained model: need server infrastructure.
- Cursor's OS sandbox: a kernel-level sandbox is not planned here (PRoot only changes the guest's
  filesystem view, see the top of this doc), and Cursor itself says its
  classifier is not a security boundary.
- Semantic code search with an embedding index: heavy for a phone; rely on grep and ripgrep.
- From Operit: phone control through Accessibility, Shizuku or Root (large security surface and
  store-policy risk, and not about coding); a visual workflow builder (poor on a small screen);
  local models through llama.cpp or MNN; memory spaces, characters and avatars.

## Suggested order

1. Command allowlist.
2. `@` mentions.
3. Shadow snapshots (done, #58).
4. Rules shortcut.
5. Verify the context meter and compact support per CLI (item 6), then decide.
6. Long tool output to file.
7. Hook templates for Claude Code.
8. Operit-derived items (section 8): the intent trigger with a token, MCP failure reasons,
   Ollama and LM Studio presets, then the MCP and skills list.
9. Audit-derived items (section 9): deny before ask and deny feedback with the allowlist, then
   risk classes, the approval audit log and the workspace write check.

## Open questions

- Which CLIs read nested instruction files?
- Does each CLI accept a compact command, and what does it report for context usage?
- Snapshot storage limit and cleanup policy (see item 8 of the upstream-sync follow-ups).
- Do the custom provider settings accept a base URL, and what MCP management exists today?
- Which authentication fits the intent trigger: a signature permission or a per-user token?

## Tests

- Allowlist pattern matching: unit test, including prefix boundaries.
- Snapshot restore: an instrumented test on a temporary repository.
- Everything else needs a device check. Verify by hand, per repo convention.
