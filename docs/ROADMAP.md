# Roadmap

The order work is planned in. Each item links to its plan under `docs/superpowers/plans/`, or says
that no plan exists yet. Status words: **Planned** (plan merged, not started), **Next** (the one to
start now), **Idea** (not planned), **Done**.

Update this file in the same PR that starts, finishes or drops an item.

## Next

1. **Git panel: stop cutting command output** (Planned).
   Plan: `2026-10-27-git-output-cap-and-duplicate-key-crash.md`.
   Fixes the crash on opening a large commit in History. Small and well-understood. Start here.

## Planned

2. **Thinking level control** (Planned).
   Plan: `2026-10-27-thinking-level-control.md`.
   Task 1 (check what each agent accepts) comes before any code. Claude Code's fixed list is the
   visible bug.

3. **Process supervisor for local servers, and the built-in agent prompt** (Planned).
   Plan: `2026-10-27-process-supervisor-and-agent-prompt.md`.
   Build order inside the plan: control command first, then UI, then the prompt. The prompt is only
   written once the command exists and its examples run.

## Before the next release

- **Device check on a phone.** Nothing merged since v1.2.28 has run on hardware. Use the checklist
  in `docs/DEVICE_VALIDATION.md`, plus the Git and thinking items once they land.
- **Release notes.** Each release needs `fastlane/metadata/android/*/changelogs/<versionCode>.txt`
  and a matching `ReleaseMetadataTest` value.

## Later ideas (not planned)

- Extend "approve from notification" beyond the cases it covers today.
- A per-model memory of the thinking level.
- Device-side test runs for the Git and chat fixes, so they stop depending on a manual check.

## Done

- Chat live-resume after returning to the app (#25).
- Git stash regex crash (#26).
- Docs, README and screenshots (#27).
- Release setup and v1.2.28 (#28, #29).
- Plans for the Git cap, supervisor and thinking control (#30, #31, #32).
