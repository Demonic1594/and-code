# Thinking level control: Off, Model default, Low … Max, per model and provider

Status: **done, reduced in scope** after Task 1 (2026-10-31). What Task 1 found:

- The app already had "Model default" as a named entry and shared labels for every level
  (`thinkingLevelLabel`), so those parts were in place.
- Claude Code: every alias the app offers (`fable`, `opus`, `sonnet`, `haiku`, which resolve to
  Fable 5.1 and the 5.5 models on the Anthropic API) supports all of `low, medium, high, xhigh, max`
  per Claude Code's model-config documentation, and an unsupported level falls back to the highest
  supported level at or below it, not rejected. The fixed list is therefore correct today; no per-alias table was
  added. The CLI has no command that reports supported levels.
- Off: Claude Code's documentation states thinking cannot be turned off on Opus 5.5, Sonnet 5.5,
  Haiku 5.5 or the Fable models (`MAX_THINKING_TOKENS=0` is a no-op there), so no Off is offered for
  Claude Code. Codex already shows its own `none` level as "Off" when the server lists it.
- What was built: the options are sorted onto one scale (`orderThinkingLevels`: none, minimal, low,
  medium, high, xhigh, max, ultra; unknown levels kept at the end), so every agent lists them the
  same way regardless of the server's order.
- Not done, deliberately: the shared level type, option model and per-agent mapping layer (Tasks 2
  and 3; the existing variant keys and shared labels already cover the agents); a per-alias table
  for Claude Code (not needed while every alias supports every level); always showing "Default" on
  the composer chip; the cost hint on Extra high and Max; remembering the choice per model (Task 5);
  and the Task 1 check of whether `selectedVariant` is persisted.

## Goal

One thinking control in the chat that reads the same for every agent: **Off, Model default, Low,
Medium, High, Extra high, Max**. Each model shows only the levels it really supports, the list
changes when the model or provider changes, and the app maps each level to whatever that agent calls
it. Where a model has no such setting, the control is hidden or disabled instead of offering
something that does nothing.

Nothing here was tried on a device or against a live provider. What each provider accepts is taken
from this repository's code; the plan's first task is to confirm it.

## What exists today

The chat already has a "thinking" choice, built on a per-model `variants` map
(`OpenCodeModel.variants`). The composer's Run settings sheet offers it when the map is non-empty,
and the model picker marks such models with the `Psychology` icon. The selection is
`ChatUiState.selectedVariant`, cleared when the model changes, and sent as `variant` with each turn.
Each agent fills the map differently:

| Agent | Where the levels come from | What is sent |
|-------|---------------------------|--------------|
| OpenCode | Whatever the server returns in a model's variants (`OpenCodeV2ApiClient`), so it already follows the model and provider | `variant` in the request body |
| Claude Code | **A fixed list for every alias**: `low, medium, high, xhigh, max` (`ClaudeModels.EFFORT_LEVELS`), the same for `fable`, `opus`, `sonnet` and `haiku` | `--effort <level>` (`ClaudeCodeRuntime`); `cliEffort` drops anything not in the list |
| Codex | The server's own list per model (`supportedReasoningEfforts` in `CodexModels`) | `effort` on each turn, always sent, falling back to the model's `defaultReasoningEffort`, because the app-server otherwise inherits the previous turn's value |
| Antigravity | A suffix on the model id (`high`, `medium`, `low`; `EFFORT_SUFFIXES`) | effort derived from the variant or suffix |

Consequences worth fixing:

- There is **no Off** and **no explicit "Model default"** entry anywhere. "Model default" exists
  only implicitly (nothing selected).
- **Claude Code shows the same five levels for every model**, whether or not the model supports them
  (for example the smallest alias). The user's request, that the list follow the model, is not met
  there.
- The levels are whatever strings each source returns, in whatever order, with the agents' own
  names. There is no shared scale or labels, so "high" on one agent and "high" on another are not
  presented consistently.

## Design

### 1. A shared scale in the core, per-agent mapping at the edges

- A small type for the shared scale (Off, Default, Low, Medium, High, XHigh, Max) in `core`, free of
  Android and UI, following the module rules in `CLAUDE.md`/`CONTRIBUTING.md`.
- Each agent supplies, for a given model, an ordered list of **options**: the shared level, the
  agent's own value for it (for example Claude's `xhigh`, Codex's effort id, OpenCode's variant key),
  and a label. The UI renders options only; it never knows agent names.
- **Unknown values are kept.** A level the server returns that the scale does not know (a new
  provider level) is appended after the known ones under its own name, so nothing is silently lost.
- Existing stored selections (`selectedVariant`) keep working: a stored value that maps to an
  option stays selected, one that does not falls back to Model default.

### 2. Meaning of each entry

- **Model default**: the app sends nothing and the model uses its own setting. The exception is
  Codex, which must keep sending its explicit default every turn (the reason is in the table above);
  "Model default" there means "send `defaultReasoningEffort`". The mapping layer owns that
  difference so the UI does not.
- **Off**: shown only where the agent can turn thinking off for that model. It is never shown for
  a model that cannot (some reasoning models always think), and never shown for an agent with no
  off switch. How to turn it off per agent is an open question (below), not an assumption.
- **Low … Max**: shown only if the model's own list contains it. The labels are shared; the value
  sent is the agent's.

### 3. Where the list comes from, per agent

- **OpenCode**: the server's variants per model, as today, mapped onto the scale.
- **Codex**: `supportedReasoningEfforts` per model, as today, mapped onto the scale.
- **Claude Code**: replace the fixed list with a **per-alias table** of the levels each alias
  supports, kept in one place (`ClaudeModels`). It must be reviewed against Claude Code's current
  documentation when written and whenever the CLI is updated, since it is the one source that does
  not come from a server. If the installed CLI can report its supported levels, prefer that over a
  hard-coded table (Task 1 checks).
- **Antigravity**: the existing suffix levels, mapped onto the scale.
- **Gemini and any other agent**: no thinking control until an agent-specific mapping exists. The
  control stays hidden for them.

### 4. UI

- The Run settings sheet keeps one control, now showing the shared labels and only the supported
  options for the selected model. Switching model refreshes the list, and a selection the new model
  does not support falls back to Model default (today it clears, which is the same outcome, now
  visible as a named entry).
- The model picker's `Psychology` icon keeps meaning "this model has a thinking control".
- The composer chip shows the current level in words, including "Off" and "Default", so the state is
  readable without opening the sheet.
- All strings get English and Japanese entries (the repo checks i18n parity).
- Whether the choice is remembered **per model**, per chat, or globally is a decision for the build
  (open question); per model is the natural default.

### 5. Extra cost and side effects to state in the UI

- Higher levels use more tokens and time, and the Usage page already counts reasoning tokens
  (`UsageRepository.reasoning`). A one-line hint on Extra high and Max says so; no numbers are
  invented.
- "Off" can change behaviour (answers may be shorter or less careful); the sheet says it applies to
  the next message, matching how the setting is actually sent.

## Tasks

**Task 1 — investigation (no behaviour change)**
- For each agent, confirm what it accepts: Claude Code's `--effort` values per model, and whether any
  flag or setting turns thinking off; Codex's list including a "none"-like level; OpenCode's variant
  keys for the main providers; Antigravity's suffixes.
- Whether Claude Code can report the levels a model supports, to avoid a hard-coded table.
- What happens to an unsupported level when sent: an error, silently ignored, or clamped. This
  decides how strict the mapping must be.
- Which stored settings keys exist for the selection today (`selectedVariant` is not persisted past
  the chat state as far as the code read shows; confirm).

**Task 2 — core scale and mapping**: the shared type, the option model, and the per-agent mapping
functions, with table-driven unit tests for each agent (known levels, unknown level kept, missing
list, a model with no setting, a model that cannot turn thinking off). No UI.

**Task 3 — agents**: Claude Code per-alias table (or CLI-reported list), Codex and OpenCode and
Antigravity mapped onto the scale. Codex's "always send" rule kept and tested. Tests for the
arguments actually built (`--effort`, `effort`, `variant`), including that Model default sends
nothing for Claude Code and OpenCode.

**Task 4 — UI**: the Run settings sheet and the composer chip, strings in both languages, a render
for each state (no setting, default only, full list, Off available, unknown level) through the
existing screenshot harness.

**Task 5 — persistence** (if chosen): remember the choice per model; migration of any existing
stored value.

**Task 6 — checks**: `./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest`,
i18n parity, and the pre-PR review.

## Confirm on a device

1. Pick each agent in turn and open the sheet: the list matches what that model supports, and
   switching model changes it.
2. Send a message at Low and at Max on the same model: the arguments differ (check the process
   arguments or the server request), and the reasoning stream looks different.
3. Choose Off where offered: the reply carries no reasoning. Where Off is not offered, confirm the
   model really cannot turn it off.
4. Codex: choose Model default, send two messages, and confirm the second does not inherit the
   first turn's effort.
5. A model with no thinking setting: the control is hidden.

## Risks and open questions

- How each agent turns thinking **off** is unverified. For Claude Code it may be an environment
  variable or a settings key rather than a flag; if an agent has no reliable off switch, Off is not
  offered for it rather than faked.
- A hard-coded per-model table for Claude Code goes stale when models change. Preferring a
  CLI-reported list, with the table as fallback, limits that.
- Some providers take a token budget rather than named levels. Mapping a budget onto named levels
  is a product decision (fixed presets?), not an assumption; the first version can skip those
  providers.
- Sending an unsupported level may error or be ignored; Task 1 decides how strict the filtering is.
- Per-model remembered selection means more stored state and a migration.

## Out of scope

- Showing or hiding the reasoning text itself (the "auto-expand reasoning" setting already covers
  that).
- The Codex fast-tier switch, which stays a separate control.
- Per-message budgets in tokens.
