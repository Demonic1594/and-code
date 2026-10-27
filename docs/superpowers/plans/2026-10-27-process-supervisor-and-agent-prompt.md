# Process supervisor ("run timer") for local servers, and a stronger built-in agent prompt

Status: **not started**. Written down to build later; nothing in the app has been changed.

## Goal

Local servers and other long-running commands started inside the PRoot runtime should not depend on
the agent keeping them alive. The app gets a dedicated supervisor that owns those processes, keeps
the device awake while they run, restarts them when asked, and shows them to the user. Agents are
told, in a detailed built-in prompt, to use it, and the existing artifacts guidance gets the same
treatment so agents follow it reliably.

Nothing here has been tried on a device. Android behaviour below is from the platform
documentation and from reading this repository, not from a test.

## What exists today

- `LocalRuntimeService` is a foreground service. It holds a wake lock while the runtime has active
  work (`syncWakeLock`, driven by `RuntimeWorkTracker.active`) and has a watchdog that restarts the
  runtime.
- `RuntimeWorkTracker` counts the work that must keep the device awake. Its reason for existing is
  that the agent runs as a PRoot child, which freezes the moment the device suspends.
- `CodexKeepAliveService` is a second foreground service for one agent.
- The Artifacts page lists local servers by `http://localhost:PORT`.
- `and-code-agent-context.md` (an asset) is written by `installAndCodeAgentContext` into
  `root/.claude/CLAUDE.md`, `root/.gemini/GEMINI.md` and
  `root/.config/opencode/and-code-context.md`. It rewrites a file only while that file still
  matches the hash of what the app last wrote; once the user has edited it, it is never updated
  again, even when the bundled text changes.
- Today's guidance on servers is one line: "keep it running in the background and print its local
  URL". That is exactly the `nohup … &` habit the supervisor replaces.

## Design

### 1. Supervisor

- A list of named processes, each with: name, command, working directory, optional port, restart
  policy (never / on crash / always), optional maximum run time, and a status (starting, running,
  crashed, restarting, stopped).
- Runs in the app's own service, not in the agent's tool shell, so a server survives the end of the
  agent's turn, closing the chat, or stopping the agent. Whether this is `LocalRuntimeService` or a
  new service is a Task 1 decision (see open questions).
- Each running process holds a `RuntimeWorkTracker` lease, so the wake lock stays on while anything
  runs and is released when the last one stops. The optional maximum run time is the same lease with
  a deadline: the "run timer".
- Each process has a rotating log file the user and the agent can read. A port is detected from the
  process (or given at start) and published to the Artifacts list.
- Definitions are stored, so the supervisor can offer to bring processes back after the app is
  restarted. Restarting never happens silently for a process whose policy is "never".
- A cap on concurrent processes (suggested 5, user adjustable), because Android may kill a process
  tree with too many children (below).

### 2. Control surface for agents

An agent must be able to do four things with a name: start, list, stop, read logs. The surface is a
small command available inside the guest shell (for example `andcode-run start|list|stop|logs`),
because every agent can already run shell commands; no agent-specific integration is needed.

- Starting a process asks the user to approve it the first time per command, the same way other
  permissioned actions do. The approval is shown in the notification and in the app.
- The command's output and **error messages teach**: if an agent is told a port is taken, or the
  supervisor is off, the message says what to do next. The prompt is not the only guard.
- A guard against the old habit: when practical, the supervisor tells the agent (in the command's
  output and in `list`) which processes it found running outside the supervisor on a known port, and
  points to the right command. Detecting stray `nohup` servers is best effort; the plan does not
  promise it.

### 3. User interface

- A persistent notification (required for a foreground service) that lists what is running, with
  **Stop** per process and **Stop all**.
- A screen listing processes with status, port, uptime, log tail, and Start / Stop / Restart.
- Permissions the feature uses are mostly granted already (`FOREGROUND_SERVICE_SPECIAL_USE`,
  `WAKE_LOCK`, `POST_NOTIFICATIONS`). Adding it likely still needs a manifest change; Task 1
  confirms which.
- Each process visibly shows that it is keeping the device awake, with its timeout, because a held
  wake lock costs battery.

### 4. Android limits and the Developer-options note

- The app cannot survive the user force-stopping it or Android killing it under memory pressure. It
  can offer to restart on the next launch. It cannot save a running server from a kill.
- Android 12 and later may kill a process tree it considers runaway (the "phantom process"
  limit). The user can stop this: on Android 14 and later, Developer options has "Disable child
  process restrictions"; on 12 and 13 it is an adb command and the app can only describe it.
  The app shows this as a **hint**, in the supervisor's settings and when a process dies without
  being stopped. It is the user's choice; the app cannot change it.
- Samsung's battery optimisation is aggressive and may need the app set to unrestricted battery. A
  hint again, not an automatic change.
- Exact menu names on a Samsung (One UI) device are not verified; the wording is checked on a
  device before it ships.

## Built-in agent prompt

This replaces "a short blurb" with a detailed, structured one. The reason is that a short prompt is
what gets skimmed or half-followed; the design below makes a long one hard to skim.

### Principles

1. **Fixed structure that can be scanned**: purpose in one line; a hard-rules block; the exact
   commands; worked examples; failure handling.
2. **Few, plain, emphasised rules.** A handful of MUST and NEVER lines, for example "NEVER start a
   server with `nohup`, `&`, `disown` or `setsid`; ALWAYS use the supervisor". If everything is
   emphasised, nothing is.
3. **The reason, in one or two sentences**: background processes die when the phone sleeps or
   Android kills them. Agents follow a rule more consistently when they know why it exists.
4. **Worked examples** for the common cases (start a Vite server, a Python server, stop it, read the
   log), copied from real command output. Examples are what agents copy most reliably.
5. **Failure cases**: port in use, the process crashes at once, the supervisor is off or not
   approved. The agent must report these to the user rather than quietly work around them.
6. **Verify, do not assume**: after `start`, run `list` and confirm the status is running and the
   URL answers, before telling the user it works.
7. **Only when enabled**: the supervisor section is included only when the feature is on, so the
   length costs nothing otherwise.
8. **Length budget**: about a page for the supervisor section, and a measured token count recorded
   in the plan's test notes, so growth is a decision rather than an accident.

### Same treatment for the artifacts guidance

The current artifacts text is three bullets. Rewrite it with the same structure:

- Hard rules: write a **single self-contained file** (inline CSS and JavaScript, images as inline
  SVG or `data:` URLs); a previewed HTML file is shown alone, so `./style.css` and `images/logo.png`
  do not load; CDN libraries load only while the phone is online; the file types that are previewed.
- The reason: why relative references fail (the preview is shown on its own).
- Worked examples: a small page, a chart as SVG, a Markdown report; and one wrong example next to
  the right one (a page that links `./app.js`).
- Verify: after writing the file, tell the user the path so they can open it from the chat; do not
  claim it renders without having the file on disk.
- Failure cases: a file too large to preview, an unsupported type, no network for a CDN.
- How it combines with servers: a full web app is a supervised server, shown in the browser; a
  single document is an artifact. Say which to use when.

### Delivery: how the text reaches each agent

The existing mechanism (`installAndCodeAgentContext`) is reused, and needs these changes:

- **One source text, small per-agent adjustments** only where an agent needs them (the file
  location and any format difference), not four copies that drift apart.
- **Codex is not in `AGENT_CONTEXT_PATHS`.** Today only Claude Code, Gemini and OpenCode receive
  the blurb. Task 1 must check how Codex reads instructions (for example an `AGENTS.md`) and add it,
  or confirm it already gets the text another way. This is the first thing to verify, because an
  agent that never receives the prompt will ignore the supervisor.
- **The text is no longer one static asset.** The supervisor section depends on whether the feature
  is on, so the installer builds the text from parts. The hash logic still applies: a file the user
  has edited is left alone. That has a consequence the plan must handle: a user who edited their
  `CLAUDE.md` never gets the supervisor prompt. The fix is to also install the supervisor section
  as its own file that the agent's instructions reference, or to tell the user once in the app that
  their instructions file is customised and does not include it. Task 1 picks one.
- **Verify delivery per agent**: for each agent, a check that the text is present in what the agent
  actually loads, not only that the file was written.

## Tasks

**Task 1 — investigation (no behaviour change)**
- Which service owns the supervisor; whether the manifest needs new entries.
- How Codex receives instructions; whether OpenCode and Gemini load the files listed above.
- How to detect a listening port from inside the guest (PRoot has no `/proc/net` guarantees; check).
- Whether `RuntimeWorkTracker` leases fit per-process lifetimes or need an extension.
- Measure what an Android 12+ device does with N child processes (needs a device).

**Task 2 — supervisor core** (`runtime/local`): process model, start/stop/restart policy, log
rotation, lease handling, concurrency cap, persistence. Unit tests with a fake process launcher.
Keep it free of UI; follow the module rules in `CLAUDE.md`/`CONTRIBUTING.md`.

**Task 3 — control command** inside the guest, the approval flow, and error messages that teach.

**Task 4 — UI**: notification with Stop/Stop all, the process screen, the battery and
child-process hints, i18n strings for English and Japanese (the repo checks i18n parity).

**Task 5 — Artifacts integration**: supervised ports appear in the Artifacts list with their name
and status.

**Task 6 — prompts**: write the detailed supervisor section and the rewritten artifacts section;
change the installer to build from parts and to cover Codex; keep one source text.

**Task 7 — tests**: installer tests for the parts, the user-edited case and the Codex path; a test
that the generated text contains every hard rule; the repo's checks
(`./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest`); the pre-PR
review. Screenshots of the new screen through the existing harness.

## Confirm on a device

1. Start a server through the command, leave the app, lock the phone for ten minutes: it still
   answers and the notification is still there.
2. Kill the app from recents: what happens to the process, and does the restart offer appear.
3. Run five processes on Android 14 or later with and without "Disable child process restrictions".
4. Ask each agent (Claude Code, Codex, OpenCode, Gemini) to "run a dev server": each uses the
   command and none uses `nohup`. Ask for a chart: each writes one self-contained file.
5. Turn the feature off: the prompt section is gone from what the agents load.

## Risks and open questions

- Token cost of a page-long prompt on every session where the feature is on; the budget above
  makes it visible.
- Agents may still ignore the prompt. The error messages and the stray-process hint are the second
  line of defence; neither is a guarantee.
- A held wake lock for many hours drains the battery; the maximum run time and the visible
  indicator are the mitigation, and defaults need a decision.
- Whether one new service or the existing runtime service should own processes. A separate service
  isolates failures; the existing one already has the watchdog and lease plumbing.
- Security: the command lets an agent start long-lived processes and open ports. Approval, the
  concurrency cap, binding to localhost only by default, and the Stop all control are the limits;
  anything that opens a port beyond localhost needs its own explicit approval.

## Out of scope

- Keeping anything alive across a force-stop or a reboot.
- The Git output-cap fix (its own plan).
- Changing what the terminal prints.
