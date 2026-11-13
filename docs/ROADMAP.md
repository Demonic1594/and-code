# Roadmap

The order work is planned in. Each item links to its plan under `docs/superpowers/plans/`, or says
that no plan exists yet. Status words: **Planned** (plan merged, not started), **Next** (the one to
start now), **Idea** (not planned), **Done**.

Update this file in the same PR that starts, finishes or drops an item.

## Next

1. **Device check, then release 1.2.30** (Next).
   Nothing merged since v1.2.29 (#107 onwards) has run on a phone. Go through
   `docs/DEVICE_VALIDATION.md` plus the device checks listed in each PR since then (approvals and
   checks, rename, Markdown in your own messages, the context window sheet, held prompts, the code
   block copy button). Then run **Fork release**: 1.2.30 is the first release that can be signed
   with the fork's own key (`docs/FORK.md`). Its changelog should also mention upstream's code block
   copy button, which came in with the #119 sync.

2. **Sync upstream once its open PRs land** (Planned, waiting on upstream).
   Upstream #422 (handing the Guest Browser to the user and back) also carries #421's inline live
   preview for local dev servers; #421 itself was closed into it. Both overlap the fork's Artifacts
   server list and agent-control indicator. Sync through the usual merge once upstream merges #422,
   not before.

## Before the next release

- **Release notes.** Each release needs `fastlane/metadata/android/*/changelogs/<versionCode>.txt`
  and a matching `ReleaseMetadataTest` value.

## Later ideas (not planned)

- Codex's usage figure and compact action, read from its app-server (harness plan, item 6).
- From the landscape doc: saved prompts kept as files in the
  repository, one worktree per chat, best-of-N attempts, E2E relay pairing, spec-driven templates
  and an issue-to-chat entry point.
- Extend "approve from notification" beyond the cases it covers today.
- A per-model memory of the thinking level.
- Device-side test runs for the Git and chat fixes, so they stop depending on a manual check.

## Done

- Chat live-resume after returning to the app (#25); Git stash regex crash (#26).
- Docs, README and screenshots (#27); release setup and v1.2.28 (#28, #29).
- Git output cap (#42), thinking levels on one scale (#56), the process supervisor and agent prompt
  (#59, #93, #97, #98), restore files to a message (#58).
- Server sharing by link (#61), app lock (#60), Ultracode (#68), Tasker triggers (#82).
- Approvals: safe always-allow (#66), the hook's answer shape (#73), blocked commands (#74),
  per-project rules (#87), risk on the card (#89), allow by kind (#90), history (#115), every tool
  of an MCP server and keeping edits in the project (#117), checks (#118).
- Chat: `@` mentions (#91), full-screen composer (#72), Markdown via commonmark-java (#102) and in
  your own messages (#121), Claude Code pictures (#101), retries and compaction (#111), held
  prompts (#114), context window sheet (#116), rename (#120).
- Browser: agent control in release builds (#71), the controlling indicator (#79), page handles
  (#85), console, keys and scroll (#113).
- v1.2.29 (#106); signed release setup for 1.2.30 (#107 to #109); upstream syncs (#38, #52, #119).
