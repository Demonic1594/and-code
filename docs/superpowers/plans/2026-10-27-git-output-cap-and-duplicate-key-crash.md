# Git panel: output cut at 4,000 characters crashes commit details, and cuts History and diffs

Status: **done** (Tasks 1–4). ADB keeps the old 4,000-character tail. Decided (2026-11-01): keep it. The
terminal no longer goes through this runner (it is an interactive session with its own scrollback), and
the ADB commands (pair, connect, get-state) print a line or two whose error is at the end.

## Symptom

Git panel → **History** → tap a commit → the app crashes:

```
java.lang.IllegalArgumentException: Key "A" was already used. If you are using LazyColumn/Row
please make sure you provide a unique key for each item.
```

Reported on build 1.2.27 (66), Android 16, Samsung SCG32, on the commit shown as "AndCode #29" in
the History list. Opening smaller commits works. That commit and its repository are not identified
(in this fork `#29` is a 74-character change that does not crash), so the reproduction below uses
two large commits of this repository; the failure is the same for any commit whose output is over
the cap.

## Cause (reproduced, not just read)

Every Git command in the panel goes through `app.commandRunner.runShell(...)`
(`WorkspaceNavGraph.kt:214`). That runner keeps only the **last 4,000 characters** of a command's
output:

```kotlin
// LocalRuntimeCommandRunner.kt
private val maxOutputCharacters: Int = 4_000,
...
output = outputFile.readText().takeLast(maxOutputCharacters)
```

It was written for short diagnostic commands. Git output for anything but a tiny repository is
longer, and `takeLast` throws away the **beginning**, not the end.

For the commit details, `git show --format= --name-status -M -z --first-parent <sha>` returns
`status NUL path NUL status NUL path …`. Cut from the front, the output starts in the middle of a
path. `parseNameStatus` then reads that fragment as a status, so everything after it is off by one:
every path is read as a status and every status letter (`A`, `M`, `D`) as a path. The details list
keys its rows by path (`GitScreen.kt`, `items(details.files, key = { it.path })`), two files that
were both "added" now both have the path `A`, and Compose throws.

Reproduced with the app's own command and parsing algorithm on this repository's own history:

| Commit | Full output | Parsed fully | After `takeLast(4000)` |
|--------|-------------|--------------|------------------------|
| a905389d (615 files) | 43,203 chars | 615 entries, no duplicate keys | 90 entries, key `A` ×90 |
| 3dd33012 (588 files) | 40,899 chars | 588 entries, no duplicate keys | 89 entries, key `A` ×89 |

Run on 700 other commits, uncut: no duplicates and no one- or two-letter paths, so the parser itself
is sound on whole output. The fault is the cap.

## Everything else the cap breaks

The same cut applies to every Git view; the crash is just where it is loudest:

- **History shows the wrong 18 of 60 commits.** One page is 60 commits, about 14,000 characters;
  after the cap 18 remain, and they are the **oldest** of the page, so the newest ~42 are missing.
  The first partial record is dropped by the parser (fewer than 8 fields), and "load more" never
  appears because `moreCommits = commits.size >= 60` is false. A repository with fewer than about 18
  commits looks fine, which is probably why this was not noticed.
- **Diffs show only their last part.** A diff of 41,488 characters (the chat fix, #25) keeps the last
  ~10%; the rest, including the file header and the first hunks, is gone. Only commits whose diff is
  under 4,000 characters are shown whole.
- **Status in a large working tree** loses its start, which includes the `## branch...upstream`
  header (branch name, ahead/behind) and the first files.
- **Anything else that prints a lot**: stash list, branch list (long names), and the Changes /
  Commit actions in `WorkspaceShell` (`WorkspaceNavGraph.kt:339`), which share the same runner.
- **Not Git, same cap:** the ADB commands share the app's runner instance. The terminal
  (`TerminalViewModel`) did too when this was written; it now runs an interactive session and does
  not use this runner (see Status). The Claude and Codex runtimes build their own `LocalRuntimeCommandRunner` for each
  command, and the dependency-injection module holds another instance, all with the same 4,000
  default, which is another reason to keep that default and add the per-call limit.

## Why the tests did not catch it

`GitClientTest` runs each command through the host's `sh` with no cap, so it exercises the parser on
whole output. Nothing tests the combination of the parser and the production runner's limit.

## Fix

**Task 1 — stop the crash whatever the cause** (defence in depth)
- Files: `feature/git/GitScreen.kt`.
- Give every lazy Git list a key that cannot collide: for the file list, the row index plus the path
  (`itemsIndexed`, key `"$index:${file.path}"`). Check the other lists' keys (staged and unstaged
  already carry a prefix; commits are keyed by hash and are deduplicated when pages are appended).

**Task 2 — stop cutting the output (the real fix)**
- Files: `runtime/local/LocalRuntimeCommandRunner.kt`, `feature/git/GitClient.kt`,
  `ui/navigation/WorkspaceNavGraph.kt`.
- Let the caller choose the limit: `runShell(command, timeoutSeconds, maxOutputCharacters)` with the
  current 4,000 as the default so nothing else changes. `GitClient` and `WorkspaceShell` take a
  `(command, timeout)` lambda, so either the lambda gains the limit or Git keeps the limit as its own
  constant and passes it through; the test stand-ins (`GitClientTest`, `GitViewModelTest`,
  `WorkspaceShellTest`) change with whichever is chosen. Git passes a large limit for the listing
  commands (log page, status, name-status, branches, stashes), sized from the worst realistic case
  (a 1,200-file commit is about 90,000 characters; a 60-commit page about 15,000), for example 1 MB,
  but not unbounded: the runner writes output to a temp file and then reads it whole into a string.
  The Git calls already pass their own timeout (30 s, 120 s for network commands), so raising the
  output limit does not change timing.
- For diffs, keep the **beginning** and say so: read up to a limit from the start, and carry a
  `truncated` flag so the view shows "Showing the first N KB of M" instead of silently showing the
  tail. This needs the runner to cut from the head for that call (a `keepStart` option), or Git to
  read the file itself.
- Make `WorkspaceShell` use the same larger limit for the commands it parses.
- Decide separately what ADB should keep (decided: keep the tail; see Status). The terminal no
  longer uses this runner.

**Task 3 — never mis-parse a cut stream**
- `parseNameStatus` accepts a status token only if it is a valid git status: one letter of
  `A C D M R T U X B`, or `R`/`C` plus digits for a rename or copy score. If the first token is not
  one, or a status has no path after it, the output is treated as incomplete instead of being read.
  A cut path that happens to start with `A` or `M` cannot be told apart this way, so this is a
  second line of defence behind Task 2, not a replacement for it. `GitCommitDetails` gets an `incomplete`
  flag and the screen says "The list of files is incomplete" instead of showing wrong rows.
- Same idea for `parseLog`: a first record that does not start with a full-length hex hash (40 digits, or 64 in a SHA-256 repository) means the
  page was cut; report that instead of silently dropping the record.

**Task 4 — tests**
- A capped stand-in runner in `GitClientTest` (cut to the last 4,000 characters) that **fails today**:
  commit details with hundreds of files (assert no duplicate paths, correct count once the cap is
  raised), a history page larger than the cap (assert all 60 commits), a diff longer than the cap
  (assert the head is kept and `truncated` is set).
- `parseNameStatus` table tests: input that starts mid-path, rename, copy, type change, unmerged, an
  extra leading empty field, and stray text such as git's "inexact rename detection was skipped"
  warning (the runner merges stderr into the output).
- A render of the commit details screen with a long file list.
- Per repo convention: `./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest`,
  i18n parity for any new strings, and the pre-PR review.
- Not covered by automated tests: the real PRoot shell and git on a phone. Check on a device with a
  repository that has a commit of several hundred files, and a history longer than 60 commits.

## Confirm on the device after the fix

1. History of a repository with more than 60 commits: the newest commit is at the top, **Load more**
   appears, and tapping the large commit opens its file list.
2. Open a large diff: the start of the file is shown, with a notice if it was cut.
3. Git panel's branch name and ahead/behind counts in a working tree with many changes.

## Out of scope

- Changing what the terminal prints, until it is decided on its own.
- The clone dialog: a separate report, a false alarm (the GitHub account needed signing in again).
