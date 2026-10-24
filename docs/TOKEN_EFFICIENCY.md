# Token efficiency

Running an agent through AndCode should cost about the same as running the same CLI in a
terminal. This page records where AndCode used to add tokens on top of the agent's own usage, what
changed, and what is left as it is on purpose.

## What changed

### Claude Code: stopping a turn no longer kills the CLI

**Before:** the stop button killed the `claude` process. (Sending while a turn runs does not go
through this path for Claude Code: its runtime queues the new prompt on the running CLI.) The next
prompt relaunched the CLI with `--resume`, which:

- re-spawned the MCP servers and re-read the whole conversation from disk, and
- on CLI versions without system-prompt snapshots, rebuilt the system prompt with a fresh
  git-status section. Once the agent had edited files, that prompt no longer matched what the API
  had cached, so the **entire conversation was re-read at full price** (plus the 25% cache-write
  premium) on the next turn. On a long coding chat that is the single biggest multiplier.

**Now:** AndCode sends the CLI's own stream-json `interrupt` control request (the one the Claude
Agent SDK uses) and keeps the process, and with it the prompt cache, alive. If the turn has not
ended within 3 seconds (an old CLI that ignores the request, or a stuck one), the process is killed
as before. Verified against Claude Code 2.1.310, whose `print.ts` handles
`control_request` / `subtype: "interrupt"`.

### Claude Code: chat titles cost a few hundred tokens instead of a full agent prompt

**Before:** naming each new chat ran `claude --print --model haiku --tools ""`, which still sent
Claude Code's complete agent system prompt, connected both AndCode MCP servers and sent their tool
schemas, all uncached, once per chat.

**Now:** the title call passes `--system-prompt` (a one-line prompt) and `--strict-mcp-config`
(no MCP servers). It is also skipped when the opening message is already a short single line
(six words or fewer, up to 40 characters), because that line already is the title.

### Antigravity: no extra `agy` run for short opening messages

Same short-prompt rule as above. Antigravity's title call is a full `agy --print` run, so skipping
it when the prompt already is the title saves a whole agent turn per chat.

### All agents: photos are downscaled before sending

**Before:** a gallery image was sent at full camera resolution (12+ MP). Claude rescales anything
over ~1568 px itself, so for Claude this cost only upload time. Gemini (Antigravity) and many
OpenCode providers tile large images and bill per tile, and an image stays in the history for every
later turn.

**Now:** JPEG, PNG and WebP attachments are scaled to 1568 px on the long side before sending, with
EXIF rotation applied. PNG and transparent images stay lossless. Smaller images are sent untouched.
Nothing is lost for Claude: 1568 px is its own resize target.

## Checked and left as is

| Item | Size | Why it stays |
|------|------|--------------|
| AndCode context blurb in `CLAUDE.md` / `GEMINI.md` / OpenCode instructions | ~250 tokens | Tells the agent it is on Android/PRoot, which prevents wasted turns on systemd, Docker and similar. Cached. |
| Browser + schedule MCP servers (15 tools) | ~1.2k tokens | They power the guest browser and scheduled tasks. Sent once per request and cached. |
| Codex | – | Already uses persistent app-server threads and `turn/interrupt`; nothing to fix. |
| OpenCode | – | The server owns its sessions, titles and compaction. AndCode only compacts at 90% context. |
| Claude process restart on model / permission-mode / preset change | – | A model change invalidates the cache anyway. The CLI supports `set_model` and `set_permission_mode` control requests, which could avoid the restart. Left for a follow-up. |

## Tips for users

- **Start a new chat for a new task.** Every turn re-sends the whole history (mostly from cache), so
  one long chat that wanders across tasks costs more than several focused ones.
- **Pick the effort level per turn.** The thinking chip's `low`/`medium` levels cut
  thinking-token output a lot for routine edits.
