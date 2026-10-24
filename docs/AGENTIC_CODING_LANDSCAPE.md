# Agentic coding landscape (October 2026)

A survey of 25 agentic coding products, written to decide what AndCode should borrow next.
For each one: what it is, the features that set it apart, and what that means for a
touch-first Android client that runs agents on the phone or drives one on a PC.

Sources are listed at the end. Much of the coverage of these products comes from third-party
comparison blogs. Treat specific numbers (benchmarks, prices, limits) as approximate and check
the vendor's docs before relying on them.

## Contents

- [How to read this](#how-to-read-this)
- [Mobile-first and remote-control clients](#mobile-first-and-remote-control-clients)
- [Terminal / CLI agents](#terminal--cli-agents)
- [IDE agents](#ide-agents)
- [Cloud / autonomous agents](#cloud--autonomous-agents)
- [App builders](#app-builders)
- [Multi-agent orchestrators](#multi-agent-orchestrators)
- [Feature matrix](#feature-matrix)
- [What AndCode should add](#what-andcode-should-add)
- [Round 2: how our new features compare](#round-2-how-our-new-features-compare)
- [Sources](#sources)

## How to read this

AndCode already covers a lot of what this survey turns up: on-device runtimes for four agents,
remote OpenCode, a model picker with favourites, tool approvals, scheduled prompts, MCP
management, voice, a launcher widget, PR badges and handoff between runtimes. The sections below
call out only what AndCode does **not** have yet. The last section ranks those gaps, and
[`docs/FEATURES.md`](FEATURES.md) records which of them shipped.

## Mobile-first and remote-control clients

These compete with AndCode directly: they put a coding agent on a phone.

### 1. Claude Code Remote Control (Anthropic)
Links the Claude mobile app or claude.ai/code to a Claude Code session running on your
computer (`claude remote-control`, or `/rc` inside a session). Files, MCP servers and settings
stay on the host. The session survives the laptop sleeping and reconnects. Since August 2026 the
phone can also *start* a session: every machine running Remote Control shows up as a device card,
and you pick a working directory.
- **Takeaway:** "device cards" (one card per machine, tap to start a session there) are a
  clearer model than a connection list. AndCode's Workspaces screen could present local and
  remote runtimes the same way.

### 2. Cursor mobile / Cursor on Web
Cursor's mid-2026 mobile push covers its cloud agents (formerly "background agents"). You can
start agents from the phone, review diffs, merge PRs, and unblock stuck runs. It adds voice
input, slash commands, lock-screen Live Activities and push notifications when a run needs input
or finishes. Android gets a PWA.
- **Takeaway:** a *live, ongoing notification* for a running turn (Android's equivalent of a
  Live Activity), and "needs input" pushes. Coverage frames the mobile app as a *control panel
  for agents*, which is the right posture for a phone.

### 3. ChatGPT Codex mobile (OpenAI)
Start Codex tasks in the cloud or on a connected computer, then inspect responses, changed
files, diffs and test results from the phone. Reports from June 2026 add branch selection and
inline review comments on changed files.
- **Takeaway:** commenting on a single diff line and sending that comment back to the agent.

### 4. GitHub Mobile + Copilot coding agent
Assign an issue to Copilot from the phone. Agent-created PRs appear under "Agent Tasks" on the
Home tab. Follow-ups happen in PR comments.
- **Takeaway:** an **Agent tasks inbox** listing running and finished work across sessions.
  AndCode's Activity data and PR badges are already most of the way there.

### 5. Happy Coder
An open-source, end-to-end encrypted mobile client for Claude Code and Codex. You run `happy`
instead of `claude` on the computer and steer it from iOS, Android or the web.
- **Takeaway:** **E2E-encrypted relay pairing** (no LAN or Tailscale needed) would remove
  AndCode remote mode's biggest setup hurdle.

### 6. Omnara
A command centre for Claude Code and other agents. It streams progress, shows diffs, handles
approvals, and lets agents pause and ask the human a question. It also has an orchestrator mode
that spawns sub-agents, and hands a session off to the cloud when the local machine goes offline.
- **Takeaway:** AndCode already has question cards and handoff. Omnara's "ask the human" pattern
  argues for making a question notification answerable straight from the shade.

### 7. Termux-based setups (community)
People run Claude Code, Aider or OpenCode inside Termux. That gives full power but no GUI.
- **Takeaway:** AndCode's reason to exist. What Termux users miss most in AndCode is a real
  interactive terminal (see the ranking below).

## Terminal / CLI agents

### 8. Claude Code (CLI)
Subagents, hooks, skills, slash commands, plan mode, MCP, `CLAUDE.md` memory, background tasks,
checkpoints with `/rewind`.
- **Takeaway:** **rewind/checkpoints**: restore files and conversation to before a message.

### 9. OpenAI Codex CLI
`app-server` JSON-RPC (which AndCode already uses), per-turn reasoning effort, fast mode, cloud
delegation with `codex cloud exec --attempts N` (best-of-N in separate containers), and
`@codex review` on GitHub PRs.
- **Takeaway:** **best-of-N attempts**: run the same prompt two to four times and pick a winner.

### 10. Gemini CLI → Antigravity CLI (Google)
Automatic **checkpointing**: before a tool modifies files, a shadow-git snapshot and the
conversation are saved, and `/restore` rolls both back. One unverified report says Google is
moving the hosted Gemini CLI tiers to Antigravity CLI, which AndCode already supports.
- **Takeaway:** shadow-git snapshots are cheap to build on-device, because the runtime already
  ships git.

### 11. Aider
Repo map (a compact symbol graph sent as context), chat modes (`code`, `ask`, `architect`,
`help`), architect/editor two-model split, auto-commits with generated messages, voice input,
lint/test-after-edit loop.
- **Takeaway:** **AI-written commit messages** and **auto-run tests after each edit**.

### 12. Goose (Block)
MCP-first open-source agent with a desktop app and **recipes**: shareable YAML files bundling a
prompt, extensions and parameters.
- **Takeaway:** recipes are the shareable version of AndCode's new saved prompts. Saved prompts
  could later be exported and imported as files.

### 13. Amp (spun out of Sourcegraph)
Editor extension, CLI, web, macOS and iOS apps. **Shareable threads** that teams read as
execution records. A **Thread Map** visualises how threads hand off to each other.
- **Takeaway:** **export or share a chat** (shipped as a Markdown export) and, later, a
  thread-relationship view for handoffs and subagents.

### 14. Warp (Agentic Development Environment)
Terminal, editor and agents in one app. Agent multithreading, vertical tabs labelled with
branch, worktree and PR, a unified notification centre, and **Warp Drive**: a shared store for
prompts, rules and environment variables.
- **Takeaway:** labelling each session with branch and PR metadata, and a single notification
  centre.

### 15. JetBrains Junie
Agent in JetBrains IDEs plus a CLI. Startup **hooks** run shell commands before the agent starts
(proxies, credential refresh). Project hook files are ignored unless trusted.
- **Takeaway:** AndCode already has startup commands. The "untrusted project hooks are inert by
  default" rule is a good safety pattern to copy.

## IDE agents

### 16. Cursor (desktop)
Agent mode, deep repo indexing, parallel subagents, rules files, bugbot PR review, cloud agents.
- **Takeaway:** **rules** (always-on instructions per project). AndCode has a global system
  prompt but nothing per workspace.

### 17. Windsurf (Cognition)
Cascade agent with **memories** (auto-captured facts), **workflows** (saved multi-step
procedures invoked as slash commands), several parallel agents on Pro, and Devin embedded since
Windsurf 2.0.
- **Takeaway:** workflows-as-slash-commands is exactly the saved-prompts feature.

### 18. Cline
Plan/Act toggle, local **checkpoints** to undo agent work, Memory Bank (markdown files that
persist project context), MCP marketplace.
- **Takeaway:** a visible **Plan/Act switch** in the composer. AndCode exposes permission modes
  per agent, but not as one consistent control.

### 19. Roo Code / Kilo Code
Cline forks. Roo adds **custom modes** (Architect, Code, Ask, Debug and user-defined ones). Kilo
adds an **orchestrator** that splits work across mode-specific agents, a shared Memory Bank, and
IDE + CLI + cloud agents with unified state.
- **Takeaway:** mode presets (prompt + permission mode + model) that you pick from one chip.

### 20. Kiro (AWS)
**Spec-driven development**: requirements (in EARS notation), then design, then a task list,
with each task linked to the requirement it serves. **Steering files** with inclusion modes
(always, fileMatch, manual, auto). **Agent hooks** that run on file save or creation, e.g. to
update tests or docs.
- **Takeaway:** a "spec" schedule or prompt template that asks the agent to write
  `requirements.md`, `design.md` and `tasks.md` first.

### 21. Augment Code
**Context Engine**: a live semantic index of the codebase that respects access controls, and is
also offered to other agents over MCP. Remote agents. The Auggie CLI.
- **Takeaway:** AndCode could surface a codebase index as an MCP server preset rather than
  building one.

### 22. Zed (agent panel)
Agent threads beside the editor, search inside terminal threads, threads renamed from the
sidebar, many threads across projects and worktrees, and ACP for plugging in external agents.
- **Takeaway:** **search across chats** and **rename from the sidebar**. Search shipped; rename
  already existed.

## Cloud / autonomous agents

### 23. Devin (Cognition)
Fully autonomous agent in a cloud VM. **Playbooks** (reusable task instructions with success
criteria), **Knowledge** (team tips and docs), **machine snapshots**, triggers from Slack,
GitHub and Linear, and DeepWiki.
- **Takeaway:** playbooks with *success criteria* are a stronger form of saved prompts and
  schedule templates.

### 24. Google Jules
Async agent on GitHub repos. **Scheduled tasks** with **templates** (performance, design,
security). **Suggested tasks** scanned from `#todo` comments. A Render integration proposes a fix
PR when a deploy fails.
- **Takeaway:** **schedule templates** (shipped) and a "suggest tasks from TODOs" schedule.

### 25. OpenHands / Factory / Copilot Workspace (grouped)
OpenHands: an open-source, self-hostable autonomous agent running in Docker. Factory "Droids":
autonomous agents wired into tickets and CI. All three take the "ticket in, PR out" approach.
- **Takeaway:** an "issue to PR" entry point: paste a GitHub issue URL and start a chat seeded
  with it.

## App builders

Replit Agent, Bolt.new, Lovable and v0 target app generation more than repo work. Replit plans,
writes, installs, runs and hosts. Lovable and Bolt generate full-stack React apps. v0 focuses on
UI with one-click Vercel deploy.
- **Takeaway:** the **live preview** loop. AndCode has a guest browser. A "Preview" button that
  opens the dev server port the agent just started would close that loop on-device.

## Multi-agent orchestrators

Conductor (a Mac app for parallel Claude Code agents in worktrees), Vibe Kanban (a kanban board
of agent tasks, now community-maintained), Crystal (deprecated in February 2026) and Terragon
(defunct) all run several agents at once, each in its own git worktree.
- **Takeaway:** **one worktree per chat** isolates parallel sessions on the same repo. It is the
  natural next step after AndCode's per-session runtime picker.

## Feature matrix

✓ = has it, ◐ = partial, blank = no. The AndCode column is after this PR.

| Feature | Claude Code | Codex | Cursor | Windsurf | Cline/Roo | Aider | Jules | Devin | Copilot | Amp | Warp | **AndCode** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Phone client | ✓ | ✓ | ◐ | | | | ◐ | ◐ | ✓ | ✓ | | ✓ |
| Agent runs on the phone | | | | | | | | | | | | ✓ |
| Search across chats | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Pin chats | | | ✓ | | | | | | | ✓ | ✓ | ✓ |
| Saved prompts / workflows | ✓ | ✓ | ✓ | ✓ | ✓ | | ◐ | ✓ | ✓ | | ✓ | ✓ |
| Export / share chat | ✓ | ✓ | ◐ | | ✓ | ✓ | | ✓ | | ✓ | ✓ | ✓ |
| Token / cost readout | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | ◐ | | ✓ | ✓ | ✓ |
| Edit files by hand | ✓¹ | ✓¹ | ✓ | ✓ | ✓ | ✓¹ | | ✓ | ✓ | ✓ | ✓ | ✓ |
| Commit / push from the UI | ◐ | ✓ | ✓ | ✓ | ◐ | ✓ | ✓ | ✓ | ✓ | | ✓ | ✓ |
| Scheduled tasks | ◐ | | | | | | ✓ | ✓ | | | | ✓ |
| Schedule templates | | | | | | | ✓ | ◐ | | | | ✓ |
| Checkpoints / rewind | ✓ | ◐ | ✓ | ✓ | ✓ | ✓ | | ✓ | | | | |
| Best-of-N attempts | | ✓ | ✓ | | | | | | | | | |
| Per-project rules | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ◐ | ✓ | ✓ | ✓ | ✓ | |
| Worktree per session | ◐ | ✓ | ✓ | ✓ | | | ✓ | ✓ | ✓ | | ✓ | |
| Interactive PTY terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | | ✓ | ✓ | |
| Live preview | | | ◐ | ✓ | ✓ | | | ✓ | | | | ◐ |

¹ via the user's own editor, not inside the agent's UI.

## What AndCode should add

Ranked by value to a phone user against the cost of building it. The **Status** column links to
[`docs/FEATURES.md`](FEATURES.md).

| # | Feature | Inspired by | Status |
|---|---------|-------------|--------|
| 1 | Search across chats in the drawer | Zed, Amp, everyone | Shipped |
| 2 | Pin chats to the top of the drawer | Amp, Warp, Cursor | Shipped |
| 3 | Saved prompts that run as slash commands | Windsurf workflows, Goose recipes, Devin playbooks | Shipped |
| 4 | Export / share a chat as Markdown | Amp threads, Aider history | Shipped |
| 5 | Session token and cost readout | Claude Code `/cost`, Cline | Shipped |
| 6 | Edit and save files in the code viewer | every IDE agent | Shipped (on-device runtime) |
| 7 | Commit, push, pull, stash from the Changes tab | Aider, Codex, Copilot | Shipped (on-device runtime) |
| 8 | Schedule templates | Jules templated tasks | Shipped |
| 9 | Material You dynamic colour, bundled code font, haptics, motion | Android platform norms | Shipped |
| 10 | Launcher shortcuts and a Quick Settings tile | GitHub Mobile, Android norms | Shipped |
| 11 | Checkpoints / rewind via shadow git | Gemini CLI, Cline, Claude Code | Next |
| 12 | Per-workspace rules file (AGENTS.md picker / editor) | Cursor rules, Kiro steering | Next |
| 13 | AI-written commit message button | Aider | Next |
| 14 | Ongoing run notification + "needs input" push with reply | Cursor Live Activities, Omnara | Next |
| 15 | Interactive PTY terminal with an extra-keys row | Termux, Warp | Later |
| 16 | Worktree per chat for parallel sessions | Conductor, Codex, Cursor | Later |
| 17 | Best-of-N attempts with side-by-side diffs | Codex cloud, Cursor | Later |
| 18 | Inline diff-line comments sent back to the agent | Codex mobile | Later |
| 19 | E2E relay pairing for remote mode | Happy Coder | Later |
| 20 | Spec-driven templates (requirements → design → tasks) | Kiro | Later |
| 21 | Agent tasks inbox across sessions | GitHub Mobile, Jules | Later |
| 22 | Preview button for dev servers started by the agent | Replit, Bolt, Lovable | Later |
| 23 | Issue URL → chat entry point | Copilot, Factory, OpenHands | Later |

## Round 2: how our new features compare

After the first batch shipped, each new feature was checked against how the tools that do it best
implement it. "Closed" means the gap was fixed in this fork.

| Our feature | Compared with | What they do that we didn't | Status |
|---|---|---|---|
| Saved prompts | Claude Code custom commands, Windsurf workflows | Claude Code passes arguments: `$ARGUMENTS` takes everything typed after the command, `$1`, `$2`… one word each. Windsurf keeps workflows as files in the repo. | Closed: `$ARGUMENTS` and `$1`–`$9`, and arguments are appended when a prompt has no placeholders. Repo-stored prompts: later |
| Pinned chats | Cline (favourite tasks), ChatGPT (pins, projects) | Cline protects starred tasks from bulk deletion. ChatGPT pins sit beside projects. | Closed: pinned chats are never auto-archived and count toward the cap. Pins are dropped when a chat is deleted. Pinned chats also appear as launcher shortcuts |
| Chat search | Cline (fuzzy history search, sort by cost), Cursor (titles only, via community tools) | Cline also sorts by cost and tokens. | Ours matches every word against title and folder. Search inside message text: later (needs an index) |
| Share / copy chat | Cline (Markdown export), Cursor (community exporters), Amp (shareable threads) | Amp shares a link. | Markdown export is on par. Link sharing needs a server: not planned |
| Usage readout | Cline task header (tokens, cost, cache), Claude Code status line (`.cost`, `.context_window`) | Cline shows cache reads apart from fresh input. | Closed: cache reads are shown on their own line |
| File editing | Acode (Android editor), VS Code | Undo/redo and find & replace are table stakes. | Closed: grouped undo/redo (200 steps) and literal find & replace-all with an ignore-case option |
| Git actions | VS Code Source Control, GitHub Desktop, Kilo Code | Per-file discard, a suggested commit message, AI-written messages. | Closed: per-file discard with confirmation (restores tracked files, deletes new ones) and a prefilled message ("Update A and 2 more files"). AI-written message: later |
| Schedule templates | Jules templated tasks | – | On par |
| Launcher shortcuts | Termux:Widget, GitHub Mobile | Shortcuts to specific items, not just screens. | Closed: up to two pinned chats as shortcuts, each opening its chat directly |
| Token efficiency | Claude Code status line, Cline auto-compact | – | See [`TOKEN_EFFICIENCY.md`](TOKEN_EFFICIENCY.md) |

Round 2 sources:
- Claude Code custom commands: [docs](https://docs.claude.com/en/docs/claude-code/slash-commands), [PromptLayer glossary](https://www.promptlayer.com/glossary/claude-code-custom-commands)
- Windsurf workflows: [docs](https://docs.windsurf.com/windsurf/cascade/workflows)
- Cline task management: [docs](https://docs.cline.bot/core-workflows/task-management)
- Cursor history: [forum: Cursor Chronicle](https://forum.cursor.com/t/cursor-chronicle-search-export-and-analyze-your-cursor-chat-history/153309), [forum: smarter search request](https://forum.cursor.com/t/allow-smart-searching-through-conversation-history/138941)
- ChatGPT projects: [OpenAI help](https://help.openai.com/en/articles/10169521-projects-in-chatgpt)
- Claude Code status line: [docs](https://code.claude.com/docs/en/statusline)
- VS Code / Kilo commit messages: [Kilo Code docs](https://kilocode.ai/docs/basic-usage/git-commit-generation), [Medium walkthrough](https://medium.com/@yanirmanor/how-to-generate-commit-messages-with-ai-in-visual-studio-code-4b7367bbafe3)
- Acode and Termux:Widget: [Acode on IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.foxdebug.acode?repo=main), [Termux:Widget on F-Droid](https://f-droid.org/de/packages/com.termux.widget/)

## Sources

- Anthropic Remote Control: [VentureBeat](https://venturebeat.com/ai/anthropic-just-released-a-mobile-version-of-claude-code-called-remote), [TechRadar](https://www.techradar.com/pro/anthropic-reveals-remote-control-a-mobile-version-of-claude-code-to-keep-you-productive-on-the-move), [Gigazine](https://gigazine.net/gsc_news/en/20260225-claude-code-remote-control), [phone-started sessions](https://pasqualepillitteri.it/en/news/12249/claude-code-start-session-from-phone)
- Cursor mobile: [The Next Web](https://thenextweb.com/news/cursor-mobile-app-coding-agents-phone), [eesel.ai](https://www.eesel.ai/blog/cursor-ios-app), [Thurrott](https://www.thurrott.com/?p=322781)
- Codex: [OpenAI Codex mobile docs](https://learn.chatgpt.com/codex/mobile), [cloud delegation](https://codex.danielvaughan.com/2026/05/19/codex-cli-cloud-delegation-workflows-plan-locally-execute-remotely-apply-diffs/), [mobile review](https://codex.danielvaughan.com/2026/06/12/codex-mobile-chatgpt-ios-branch-worktree-goal-inline-review-cli-companion/)
- Copilot coding agent on mobile: [GitHub changelog](https://github.blog/changelog/2025-06-03-github-copilot-coding-agent-now-available-on-github-mobile), [GitHub docs](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/create-a-pr)
- Happy Coder: [App Store](https://apps.apple.com/app/id6748571505), [docs](https://www.mintlify.com/slopus/happy/quickstart)
- Omnara: [App Store](https://apps.apple.com/qa/app/omnara-ai-command-center/id6748426727?l=ar)
- Gemini CLI checkpointing: [geminicli.com](https://geminicli.com/docs/cli/checkpointing/)
- Aider: [aider.chat](https://docsearch.algolia.com/mcp/docs/website/aider.chat), [chat modes](https://www.promptlayer.com/glossary/aider-chat-modes)
- Goose / Aider comparison: [aicoolies](https://aicoolies.com/comparisons/aider-vs-goose)
- Amp: [Thread Map](https://hackernoon.com/sourcegraph-spinoff-amp-rolls-out-thread-map-to-manage-agent-workflows?source=rss), [jimmysong.io](https://jimmysong.io/ai/ampcode)
- Warp: [Warp blog](https://warp.dev/blog/reimagining-coding-agentic-development-environment), [Warp docs](https://docs.warp.dev/agents/local-agents/overview.md)
- Junie hooks: [Junie docs](https://junie.jetbrains.com/docs/junie-cli-hooks.html)
- Cline / Roo / Kilo: [Cline Memory Bank](https://docs.cline.bot/improving-your-prompting-skills/cline-memory-bank), [Kilo vs Cline](https://www.morphllm.com/comparisons/kilo-code-vs-cline)
- Kiro: [SD Times](https://sdtimes.com/?p=57508), [Smile](https://smile.eu/en/publications-and-events/kiro-aws-s-agentic-ide-spec-driven-development)
- Augment Code: [Context Engine](https://www.augmentcode.com/context-engine), [The New Stack](https://thenewstack.io/augment-codes-remote-agents-code-in-the-cloud/)
- Zed: [agent settings](https://zed.dev/docs/ai/agent-settings), [AI overview](https://zed.dev/docs/preview/ai/overview.html)
- Devin: [SD Times](https://sdtimes.com/ai/devin-gets-a-slew-of-new-features-to-help-with-onboarding-it-into-dev-environments/), [Playbooks guide](https://fast.io/resources/devin-ai-playbook-guide/)
- Jules: [scheduled tasks docs](https://jules.google/docs/scheduled-tasks/), [Google blog](https://blog.google/technology/developers/jules-proactive-updates/)
- Overviews: [morphllm best coding agent](https://www.morphllm.com/best-coding-agent), [Scrimba](https://scrimba.com/articles/best-ai-coding-assistants-2026/), [app builders comparison](https://altar.io/lovable-vs-bolt-vs-v0-vs-replit-vs-base44/), [multi-agent tools](https://agentsroom.dev/blog/best-multi-agent-coding-tools), [Windsurf vs Trae](https://www.lowcode.agency/blog/windsurf-vs-trae), [Augment vs OpenHands](https://aiagentrank.io/compare/augment-code-vs-openhands)
