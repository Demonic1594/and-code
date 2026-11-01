#!/bin/sh
# AndCode PermissionRequest hook for Claude Code.
# Reads hook JSON on stdin, asks the Android app via the file bridge, prints a decision.
#
# Claude Code reads a PermissionRequest answer only in this shape (checked against CLI 2.1.316):
#   {"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}
#   {"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"..."}}}
# The PreToolUse field "permissionDecision" fails its validation, the answer is dropped, and a
# session that cannot prompt then denies the call, so every approval made on the phone was lost.

set -eu

# Run as Claude Code's PreToolUse hook for Bash (with --pre-tool-use) it only refuses blocked
# commands, before every call and whatever the permission mode: a PermissionRequest hook runs only
# when Claude Code would ask, which the default mode skips for Bash and full access skips entirely.
MODE="${1:-}"

BRIDGE="${ANDCODE_CLAUDE_BRIDGE:-/root/.andcode/claude-bridge}"
PENDING="$BRIDGE/pending"
RESPONSES="$BRIDGE/responses"
ALWAYS="$BRIDGE/always-rules.json"
BLOCKED="$BRIDGE/block-rules.json"
CLASSES="$BRIDGE/class-rules.json"
TIMEOUT_SEC="${ANDCODE_PERMISSION_TIMEOUT_SEC:-300}"
# A question can legitimately wait much longer than a permission: the user may be away from the
# device and the turn is blocked until they answer. Keep it under the hook timeout Claude Code
# itself applies (see ClaudePermissionHooks), so this script still gets to deny gracefully.
QUESTION_TIMEOUT_SEC="${ANDCODE_QUESTION_TIMEOUT_SEC:-3540}"
SLEEP_SEC=0.25

ALLOW='{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
# What the agent reads when a call is refused: say it was the user, and steer it away from retrying
# the same call, which agents otherwise tend to do in a loop.
DECLINED="The user declined this action. Do not retry the same call; adjust your approach or ask the user what they want."
UNANSWERED="The user did not answer in time, so this action was not allowed. Do not retry it unchanged; continue another way or ask the user."
NO_JQ="AndCode cannot show this request on the phone because jq is missing in its Linux environment, so it was not allowed. Ask the user to install jq in the AndCode terminal, then retry."

# Prints a deny answer carrying [message] for the agent.
deny() {
  if command -v jq >/dev/null 2>&1; then
    jq -cn --arg message "$1" \
      '{hookSpecificOutput:{hookEventName:"PermissionRequest",decision:{behavior:"deny",message:$message}}}'
  else
    # Only reached for the fixed texts above, which have nothing to escape.
    printf '%s\n' "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\",\"decision\":{\"behavior\":\"deny\",\"message\":\"$1\"}}}"
  fi
}

# Before a tool call there is nothing to do without blocked commands: leave at once, as this runs
# before every Bash call.
if [ "$MODE" = "--pre-tool-use" ] && [ ! -f "$BLOCKED" ]; then
  cat >/dev/null
  exit 0
fi

mkdir -p "$PENDING" "$RESPONSES"

INPUT=$(cat)
if [ -z "$INPUT" ]; then
  exit 0
fi

# Without jq the request cannot be read whole, so the phone would be asked to approve a call it
# cannot show, and an approval would run it unseen. Refuse instead; the app's Linux environments
# come with jq.
if ! command -v jq >/dev/null 2>&1; then
  # Before a tool call the blocked commands cannot be checked, and in the modes that never ask
  # nothing else would stop one, so refuse (a fixed text, nothing to escape).
  if [ "$MODE" = "--pre-tool-use" ]; then
    printf '%s\n' "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"$NO_JQ\"}}"
    exit 0
  fi
  deny "$NO_JQ"
  exit 0
fi
TOOL_NAME=$(printf '%s' "$INPUT" | jq -r '.tool_name // .toolName // empty')
SESSION_ID=$(printf '%s' "$INPUT" | jq -r '.session_id // .sessionId // empty')
TOOL_INPUT=$(printf '%s' "$INPUT" | jq -c '.tool_input // .toolInput // {}')
# Where the call runs: an always-allow rule kept for a project applies only there (and below).
CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty' 2>/dev/null || true)

if [ -z "$TOOL_NAME" ]; then
  TOOL_NAME="Tool"
fi

# Refuse blocked commands first, before any always-allow rule and without waking the UI. Same rules
# as ShellCommandRules.blocks on the app side, and deliberately broader than allowing: any chained,
# piped or substituted part of the command that runs the rule's program (path ignored) with the
# rule's flags in any order (short ones also grouped: -rf is -r -f) and its other words in order,
# other words between them allowed, is refused. Line continuations, quotes and escapes are taken out.
# A rules file that cannot be read refuses every command rather than letting a blocked one through.
if [ -f "$BLOCKED" ]; then
  BLOCKED_CMD=$(printf '%s' "$TOOL_INPUT" | jq -r 'if type == "object" then .command // empty else empty end' 2>/dev/null || true)
  # An empty file is never written by the app (no rules deletes it), so it means a broken write.
  if [ ! -s "$BLOCKED" ] || ! BLOCKED_BY=$(jq -r --arg t "$TOOL_NAME" --arg c "$BLOCKED_CMD" '
    def words: [splits("\\s+")] | map(gsub("[\"\u0027\\\\]"; "")) | map(select(length > 0));
    def parts: gsub("\\\\\r?\n"; "") | [splits("[;&|`(){}<>!$\n\r]")] | map(words);
    def prog: split("/") | last // "";
    def flagsplit: reduce .[] as $x ({flags: [], plain: []};
      if ($x | startswith("--")) then .flags += [$x]
      elif ($x | length) > 1 and ($x | startswith("-")) then .flags += ($x[1:] | split("") | map("-" + .))
      else .plain += [$x] end);
    def inorder($a; $b):
      if ($a | length) == 0 then true
      elif ($b | length) == 0 then false
      elif $a[0] == $b[0] then inorder($a[1:]; $b[1:])
      else inorder($a; $b[1:]) end;
    ($c | parts) as $segments |
    .rules[]? | select(.toolName == $t) | ((.commandPrefix // "") | parts | add // []) as $r |
    select(($r | length) > 0) | ($r[1:] | flagsplit) as $rs |
    select(any($segments[] as $w | range(0; $w | length) as $i |
      ($w[$i] | prog) == ($r[0] | prog) and
        (($w[($i + 1):] | flagsplit) as $cs | ($rs.flags - $cs.flags | length) == 0 and inorder($rs.plain; $cs.plain)); .)) |
    ($r | join(" "))
  ' "$BLOCKED" 2>/dev/null); then
    REFUSAL="AndCode could not read the user's list of blocked commands, so this was not run. Ask the user to check Settings, Claude Code, Command rules."
  else
    BLOCKED_BY=$(printf '%s\n' "$BLOCKED_BY" | head -n1)
    REFUSAL=""
    if [ -n "$BLOCKED_BY" ]; then
      REFUSAL="The user blocked commands containing \"$BLOCKED_BY\" in AndCode, so this was not run. Do not retry it or work around the block; continue another way or ask the user."
    fi
  fi
  if [ -n "$REFUSAL" ]; then
    if [ "$MODE" = "--pre-tool-use" ]; then
      jq -cn --arg reason "$REFUSAL" \
        '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
    else
      deny "$REFUSAL"
    fi
    exit 0
  fi
fi
# Before a tool call only blocking applies; whether to ask is the permission step's business.
if [ "$MODE" = "--pre-tool-use" ]; then
  exit 0
fi

# Kinds of calls the user allows without asking (ToolClassRules): reading and editing files. Same
# tool lists as ToolClassRules.Kind; running commands is never allowed by kind.
if [ -f "$CLASSES" ]; then
  case "$TOOL_NAME" in
    Read|Grep|Glob|LS|NotebookRead|WebFetch|WebSearch) TOOL_KIND="read" ;;
    Write|Edit|MultiEdit|NotebookEdit) TOOL_KIND="edit" ;;
    *) TOOL_KIND="" ;;
  esac
  if [ -n "$TOOL_KIND" ] && jq -e --arg k "$TOOL_KIND" '(.allow // []) | index($k) != null' "$CLASSES" >/dev/null 2>&1; then
    printf '%s\n' "$ALLOW"
    exit 0
  fi
fi

# Auto-allow remembered rules without waking the UI.
if [ -f "$ALWAYS" ]; then
  CMD=$(printf '%s' "$TOOL_INPUT" | jq -r 'if type == "object" then .command // empty else empty end' 2>/dev/null || true)
  # Absent or null only: an explicit "command": "" is a command (an empty one), as on the app side.
  NO_CMD=$(printf '%s' "$TOOL_INPUT" | jq -r 'if type == "object" and .command != null then "no" else "yes" end' 2>/dev/null || echo yes)
  # Same rules as ShellCommandRules on the app side: a call without a command matches a tool-wide
  # rule; a command matches only a rule naming its first words exactly, and never when it chains,
  # pipes, substitutes or redirects, or starts with a program that runs other code (bash -c ...).
  MATCH=$(jq -r --arg t "$TOOL_NAME" --arg c "$CMD" --arg n "$NO_CMD" --arg d "$CWD" '
    def words: [splits("\\s+")] | map(select(length > 0));
    # Same as ClaudePermissionBridge.appliesIn: no project means everywhere; otherwise the call must
    # run in that directory or one inside it.
    ($d | sub("/+$"; "")) as $dir |
    def inproject: (.project // "") as $p | $p == "" or ($dir | startswith("/")) and (($dir | split("/") | index("..")) == null) and ($dir == $p or ($dir | startswith($p + "/")));
    def wrappers: ["bash", "sh", "zsh", "dash", "ash", "ksh", "fish", "busybox", "env", "xargs", "sudo",
      "su", "doas", "eval", "exec", "command", "time", "nohup", "timeout", "nice", "setsid", "stdbuf",
      "python", "node", "nodejs", "npx", "bun", "deno", "perl", "ruby", "php", "lua", "awk", "gawk"];
    def runsother: test("[=\"\u0027]") or (split("/") | last // "" | sub("[-.0-9]+$"; "") | IN(wrappers[]));
    ($c | test("[;&|`$<>()!\\\\\n\r]")) as $unsafe |
    (($c | words)[0] // "") as $first |
    .rules[]? | select(.toolName == $t) | select(inproject) |
    (.commandPrefix // "") as $p |
    if $n == "yes" then (if $p == "" then "yes" else empty end)
    elif $unsafe or ($first | runsother) or ($p | words | length) == 0 then empty
    else ($p | words) as $r | ($c | words) as $w |
      if ($w | length) >= ($r | length) and $w[0:($r | length)] == $r then "yes" else empty end
    end
  ' "$ALWAYS" 2>/dev/null | head -n1 || true)
  if [ "$MATCH" = "yes" ]; then
    printf '%s\n' "$ALLOW"
    exit 0
  fi
fi

KIND="permission"
if [ "$TOOL_NAME" = "AskUserQuestion" ]; then
  KIND="question"
fi

REQUEST_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N)
ANDROID_SESSION="${ANDCODE_ANDROID_SESSION_ID:-$SESSION_ID}"
if [ -z "$ANDROID_SESSION" ]; then
  ANDROID_SESSION="unknown"
fi

# The command is what runs, so the label always shows it: a description alone is the agent's own
# words and could say anything.
LABEL="$TOOL_NAME"
DESC=$(printf '%s' "$TOOL_INPUT" | jq -r '.description // empty' 2>/dev/null || true)
SHOWN_CMD=$(printf '%s' "$TOOL_INPUT" | jq -r '.command // empty' 2>/dev/null || true)
if [ -n "$DESC" ] && [ -n "$SHOWN_CMD" ]; then
  LABEL="$TOOL_NAME: $DESC — $SHOWN_CMD"
elif [ -n "$SHOWN_CMD" ]; then
  LABEL="$TOOL_NAME: $SHOWN_CMD"
elif [ -n "$DESC" ]; then
  LABEL="$TOOL_NAME: $DESC"
fi

REQUEST_FILE="$PENDING/$REQUEST_ID.json"
RESPONSE_FILE="$RESPONSES/$REQUEST_ID.json"

jq -n \
  --arg kind "$KIND" \
  --arg requestId "$REQUEST_ID" \
  --arg androidSessionId "$ANDROID_SESSION" \
  --arg claudeSessionId "$SESSION_ID" \
  --arg toolName "$TOOL_NAME" \
  --arg permissionLabel "$LABEL" \
  --argjson toolInput "$TOOL_INPUT" \
  --argjson createdAtMs "$(date +%s000)" \
  --arg cwd "$CWD" \
  '{v:1,kind:$kind,requestId:$requestId,androidSessionId:$androidSessionId,claudeSessionId:$claudeSessionId,toolName:$toolName,toolInput:$toolInput,permissionLabel:$permissionLabel,createdAtMs:$createdAtMs} + (if $cwd == "" then {} else {cwd:$cwd} end)' \
  >"$REQUEST_FILE"

if [ "$KIND" = "question" ]; then
  WAIT_SEC="$QUESTION_TIMEOUT_SEC"
else
  WAIT_SEC="$TIMEOUT_SEC"
fi
# Measure real seconds: the loop used to count iterations of a quarter-second sleep, which made
# the effective timeout a quarter of the configured one and expired questions far too early.
deadline=$(( $(date +%s) + WAIT_SEC ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if [ -f "$RESPONSE_FILE" ]; then
    DECISION=$(jq -r '.decision // "deny"' "$RESPONSE_FILE")
    MESSAGE=$(jq -r '.message // empty' "$RESPONSE_FILE")
    UPDATED=$(jq -c '.updatedInput // empty' "$RESPONSE_FILE")
    rm -f "$REQUEST_FILE" "$RESPONSE_FILE" 2>/dev/null || true
    if [ "$DECISION" = "allow" ]; then
      if [ -n "$UPDATED" ] && [ "$UPDATED" != "null" ]; then
        jq -cn --argjson updated "$UPDATED" \
          '{hookSpecificOutput:{hookEventName:"PermissionRequest",decision:{behavior:"allow",updatedInput:$updated}}}'
      else
        printf '%s\n' "$ALLOW"
      fi
      exit 0
    fi
    deny "${MESSAGE:-$DECLINED}"
    exit 0
  fi
  # shellcheck disable=SC2039
  sleep "$SLEEP_SEC" 2>/dev/null || sleep 1
done

rm -f "$REQUEST_FILE" 2>/dev/null || true
deny "$UNANSWERED"
exit 0
