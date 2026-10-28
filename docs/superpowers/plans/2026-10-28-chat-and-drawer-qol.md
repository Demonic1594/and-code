# Four chat and drawer quality-of-life additions

Status: **not started**. Written down to build later; nothing in the app has been changed.

1. The chat overflow menu gains **Open repository on GitHub**, only when the chat's project was
   cloned from GitHub.
2. The drawer's **Projects** row moves its "Add project" button out of the scrollable chips onto
   the section header line, next to a new project-search toggle.
3. Tool cards render **Input** and **Output** as titled code blocks with a copy button each.
4. The changes review stops being limited to Git repositories: a shadow snapshot makes a diff
   possible in any on-device workspace.

## What exists today

- `ChatOverflowMenu.kt` is the chat's ⋮ menu: usage (a disabled row), Artifacts (only when the
  chat has some), Share chat, Copy as Markdown. There is no way to reach the repository a chat is
  working in.
- Repositories reach the device through `GithubCloneDialog` + `GitCloneRepository.clone`, which
  runs `git clone --depth 1 <url> <target>` inside the runtime. Nothing reads the origin URL back.
  `OpenCodeVcsInfo` carries only `branch` and `defaultBranch`, so the API cannot tell us the remote
  either.
- The chat knows its session's `directory` (the session record; `ChatViewModel` already passes it
  to `sessionDiff`), and on-device runtimes can run git in the workspace
  (`ClaudeWorkspaceGit` scripts via `runtime.runInWorkspace`; the OpenCode local runtime shares a
  command runner).
- `AppDrawerContent.kt:286` renders the drawer's Projects section: a `DrawerSectionHeader`, then
  `DrawerProjectChips` (`AppDrawerContent.kt:711`) — a horizontally scrolling row of workspace
  chips with an "Add project" pill as the **last item inside the scroll**. With more than a
  handful of projects the pill is off-screen to the right, and finding one project among many
  means reading every chip.
- `AssistantActivityRow.kt` `ToolCard` (input at :358, output at :377) expands to a plain `Text`
  under a small "Input"/"Output" label — monospace, but no block surface, no way to copy without
  long-pressing, and the label does not stand apart from the content.
-   `ChatDiffDialog` shows "Diff unavailable — this workspace isn't a Git repository"
  (`diff_unavailable`) whenever `PatchDiffState.Unavailable` is set. Two paths lead there
  (`openPatchDiff`, `ChatViewModel.kt:1598`): backends that are not `diffCapable` — Antigravity,
  and Codex, which leaves the capability unset — return early without a round trip; and a
  git-capable backend that comes back empty — OpenCode needs a git project, and
  `ClaudeCodeTarget` diffs the workspace's own git (`vcsDiff`), which has nothing to say outside
  one (Codex would also throw `unsupported` from the base default, but the early return means it
  never gets that far). A non-git workspace therefore gets a file list with no diff, on every
  on-device agent.
- Copy UI today is a plain `ContentCopy` icon with no feedback, among other styles: DevTools has
  three (`DevToolsSources.kt:179`, `DevToolsElements.kt:404` direct copies, and
  `DevToolsNetwork.kt:363`, which opens the Copy URL / as cURL / response menu),
  `RemoteConnectionScreen.kt:235` and `ProviderSettingsScreen.kt:262` copy with a toast, and
  copy-as-Markdown toasts from the chat menu. Nothing anywhere shows a copied confirmation
  inline, and there is no shared composable.

## Design

### 1. Open repository on GitHub (chat ⋮ menu)

- New menu item in `ChatOverflowMenu`, below Artifacts: "Open on GitHub" with a vector icon
  (Material `Icons.Outlined.Public` or similar — not an emoji).
- Shown only when the chat's project directory has a GitHub origin. Resolution, per session:
  run `git -C <directory> config --get remote.origin.url` in the runtime (the same
  `runInWorkspace` pattern `ClaudeWorkspaceGit` uses; the OpenCode local runtime goes through the
  shared command runner). Parse `github.com/<owner>/<repo>` out of the https, `git@github.com:`
  and `ssh://` forms, stripping `.git`. A non-GitHub remote or a missing one resolves to null and
  the item stays hidden.
- Only on-device runtimes. A remote OpenCode server has no shell to read git config through, so
  the item never appears there (Task 1 re-checks whether any server route exposes the remote).
- The result is cached per (runtime, directory) so opening the menu twice does not spawn git
  twice; the lookup is lazy — it starts when the session opens, so the menu rarely waits.
- Tapping opens `https://github.com/<owner>/<repo>` the same way a pull request badge tap does,
  so both live in one place and follow one setting.

### 2. Projects header: add-button and search

```
Projects                      + Add project  [search icon]
(workspace) (and-code) (solo-learning) (solo-learning-site) …
```

- `DrawerSectionHeader` grows an optional trailing slot. On the Projects header the slot holds:
  a compact "+ Add project" text button in `MaterialTheme.colorScheme.primary` (blue text, as
  requested) with the existing `cd_add_project` content description, and an `IconButton` with the
  Material `Search` vector icon.
- The "Add project" pill is removed from the scrolling chip row; the row then shows projects only.
- Tapping the search icon reveals a small filter field (same style as the drawer's chat search)
  between the header and the chips; typing filters chips by `WorkspaceRef.name`
  (case-insensitive substring). Clearing the text or tapping the icon again collapses it. The
  field starts empty and never filters until text is typed, so the drawer looks unchanged at a
  glance.
- New strings: the field's hint and the icon's contentDescription. All eight locales, per the
  repo's i18n parity check.

### 3. Input/Output as code blocks with copy

- In `ToolCard`, the Input and Output sections become: a title row — "Input" (existing
  `tool_input_label`) plus a copy `IconButton`; a code block — the content on a rounded
  `surfaceContainerHighest`-style surface (matching the theme's dark/light code surfaces),
  `CodeFontFamily`, same scroll behaviour as today (input scrolls horizontally, output scrolls
  both ways inside its 240 dp cap) and the same `TextDirection` fixes (LTR for input,
  content-resolved for output). The truncation note stays under Output.
- A shared `CopyButton` composable is introduced here: `Icons.Default.ContentCopy` →
  `Icons.Default.Check` for about 2 seconds after a tap, `ClipboardManager.setText`, a content
  description that also serves as the copied announcement, and a haptic tick when haptics are
  on. The direct DevTools copy buttons (`DevToolsSources`, `DevToolsElements`) switch to it in
  the same PR and gain the feedback they currently lack; the Network menu keeps its three
  options and only its items get the same check feedback. ToolCard's two new buttons make five
  uses in total — enough to justify one component.
- The error text under Output is out of scope (left as is).

### 4. Diff without a Git repository (shadow snapshots)

- Goal: when the backend's own `sessionDiff` returns nothing (Antigravity and Codex always;
  Claude Code and OpenCode outside a git project), the changes review still shows a real diff.
- Mechanism — shadow git, the approach the landscape doc already noted as cheap on-device because
  the runtime ships git: a per-workspace git directory the **app** owns, stored under the app's
  private storage (never inside the workspace, so no agent sees it), referenced with
  `--git-dir`/`--git-work-tree` against the mounted workspace path. `git add -A` plus a commit at
  the start of each turn snapshots the tree; at review time the diff is
  `git diff <pre-turn commit> -- <the turn's file list>`, unified format, feeding the existing
  `parseUnifiedDiff`/`PatchDiffState.Loaded` path unchanged.
- It kicks in only as a fallback, on **both** paths to `Unavailable`: the `diffCapable` early
  return (Antigravity, Codex) must be revisited — a shadow-diff-capable target goes to the
  snapshot instead of returning early — and a backend that came back empty falls through to the
  snapshot too. A backend that produced a diff is used as today. The reasoning card and
  everything else about the activity bar stay untouched.
- Guard rails: a size cap per workspace (measured; suggested 200 MB of tracked blobs) and a
  per-turn wall clock for the snapshot command; past either, the snapshot is skipped and the
  review falls back to today's file list, honestly. Binary files show as counts only (git's
  default). The snapshot store is counted by the Storage manager and offered under Clean up as
  its own area, since it is regenerable.
- Remotely-driven runtimes cannot be shadowed (no shell on the PC), so there the limitation
  stays and is stated as such.

## Tasks

**Task 1 — investigation (no behaviour change)**
- Confirm the workspace host path per on-device target for `--git-work-tree`, and whether any
  OpenCode server route exposes the origin URL (for menu item 1 on remote runtimes).
- Measure a shadow `git add -A` on the largest realistic workspace (this repository) inside
  PRoot: time and object-store size, first turn vs. later turns.
- Check the PR-badge tap path so item 1 opens links through the same route.

**Task 2 — menu item and drawer**
- `ChatOverflowMenu` + origin resolution + cache; `DrawerSectionHeader` trailing slot; move the
  add button; project filter field; strings in all locales.

**Task 3 — code blocks with copy**
- Introduce `CopyButton`; rework `ToolCard` Input/Output; switch the DevTools copy buttons to it
  (the Network menu keeps its options, its items get the check feedback).

**Task 4 — shadow snapshots**
- The snapshot store (git-dir layout, caps, prune of pre-previous commits), the turn-boundary
  hook, the fallback wiring into `openPatchDiff`, and the Storage manager area.

**Task 5 — tests and checks**
- Unit tests: origin-URL parsing table (https, ssh, `.git`, non-GitHub, junk); project filter;
  `CopyButton` state machine (copy → check → revert) with a test clock; shadow-store prune and
  cap logic with a fake runner; the fallback path of `openPatchDiff` returning
  `PatchDiffState.Loaded` from a shadow diff.
- Per repo convention: `./gradlew detekt spotlessCheck :app:lintGithubDebug
  :app:testGithubDebugUnitTest`, i18n parity, screenshots of the menu, drawer and tool card
  through the existing harness, and the pre-PR review.

## Confirm on a device

1. A chat whose project was cloned from GitHub shows the menu item and it opens the right
   repository; a project created locally does not show it.
2. The drawer header matches the sketch, the add button navigates to Workspaces, and search
   filters chips by name.
3. A bash tool call shows Input/Output blocks; copy fills the clipboard and flashes the check.
4. Changes review in a non-git workspace (create a folder with files, ask the agent to edit
   them) shows line diffs; a git project still uses the runtime's own diff; a workspace past the
   size cap falls back to the file list.

## Risks and open questions

- Snapshot cost on large workspaces: the first `git add -A` compresses everything; the cap and
  the measurement in Task 1 decide whether the default is on or opt-in.
- Shadow git in PRoot on a big tree may be slow enough to delay a turn's start; if Task 1
  measures that, the snapshot moves to turn end (diff against the previous turn's snapshot)
  which trades "diff of this exact turn" for speed — a decision Task 1 records.
- The origin lookup adds a git call per uncached directory; bounded by the per-directory cache.
- Deleted files in a shadow diff need `git add -A` to stage deletions — confirmed by design, but
  Task 1 verifies on device.

## Out of scope

- The reasoning card (explicitly left as is).
- Diffs on remote-PC runtimes.
- Any change to how OpenCode or Claude Code produce diffs when they already work.
- Link sharing or opening issues/PRs beyond the repository root.
