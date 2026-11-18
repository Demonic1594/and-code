# Splitting ChatViewModel.kt (2026-11-14)

Status: **first half done** (steps 1 to 13 below); what is left is listed at the end.
`ChatViewModel.kt` was 3,985 lines, more than twice the next largest file, and is now 2,319.
Every chat feature lands in it and most PRs conflict there. The Nov 9 checkup
(`2026-11-09-app-checkup.md`, "Left on purpose") left it until a change needed it; this is that
change. It is a refactor with no change for the user: the screens keep the same API, the state
stays the same, and the existing tests pass unchanged.

This PR aims to cut the file roughly in half, not to finish the job. What is left is listed at the
end.

## Rules for every step

- **Moves, not rewrites.** Code moves with its comments, names and statement order. A view model
  method the screens call stays on the view model, as a one-line call to the helper that now holds
  the body.
- **Plain helpers.** Each piece becomes an `internal` class, or top-level functions, in
  `feature/chat`. A class takes what it needs: the backend, the view model's own
  `MutableStateFlow<ChatUiState>`, `viewModelScope`, and the callbacks it reports through. No
  inheritance, no new framework, no new dependencies. The helpers are built inside `ChatViewModel`
  from its own constructor parameters, so `AndCodeApp` and the Koin module are untouched.
- **Same scope, same dispatcher.** A helper launches on the `viewModelScope` it is given, never a
  scope of its own. Cancelling the view model's scope still cancels everything; the stall tests
  rely on this (`stopCatchingUp()` cancels `viewModelScope`).
- **Same update order.** A helper updates the shared `MutableStateFlow` in the same places, in the
  same order, as the code it came from, and the view model calls the helper at the same point the
  code used to run. Where `init` launched a collector, `init` still launches it at the same point,
  through the helper, so collectors still start in the same order.
- **Tests are the contract.** No assertion changes. Logic that had no direct test gets a focused
  one when it is extracted.
- **detekt.** A moved finding keeps its baseline entry under its new ID. New code is written
  within the limits and adds no entries. Entries for findings that are gone are removed.
- **Each commit is green** on spotless, the unit tests, lint and the detekt CLI step.

## What is in the file

Top level, outside the class (about 770 lines):

| Piece | Lines | Notes |
| --- | --- | --- |
| State types: `ChatUiState`, `ChatMessage`, `ChatPart`, `ToolStatus`, `TodoItem`, `FileRestore`, `PatchDiffState`, `ParentSessionRef`, `QueuedPromptView`, `QueuedPromptActions` | ~280 | Used by every screen. No logic beyond `visibleMessages` and `text`. |
| Transcript mapping: `OpenCodePart.toChatPart` and its JSON helpers, `extractReasoningText`, `imageMime`, `withMessageError` | ~180 | Pure. `imageMime` is also used by the Codex parser and the message views. |
| Transcript merging: `mergeReloadedMessages`, `fresherReply` | ~110 | Pure, tested directly. |
| Attachment index helpers: `previewIndexForAttachment`, `attachmentIndexForPreview` | ~25 | Pure, tested directly. |
| Question answer helpers: `PendingQuestionUi`, `updateQuestionAnswerSelection`, `sanitizeQuestionAnswer` | ~90 | Pure. |
| `codexServiceTier`, timing constants, `QueuedPrompt`, `ContextReading` | ~100 | |

Inside `ChatViewModel` (about 3,150 lines), by responsibility:

| Responsibility | Main members | Shared state it touches |
| --- | --- | --- |
| Wiring (`init`) | event collection, health probe, foreground re-read, catch-up loop, connection monitor, stall watchdog, run-state reporting, queue view | launches everything on `viewModelScope` |
| Sending | `send`, `sendSlashCommand`, the post-send poll, `matchSlashCommand`, `turnFinished` | almost everything: `runGeneration`, `streamedParts`, `transcriptGeneration`, queues, rewind, snapshots, model selection, drafts, context usage |
| Held prompts (#114) | `messageQueue`, `offlineMessageQueue`, `drainQueue`, `drainOfflineQueue`, `adoptHeldPrompts`, `dropNewChatPrompts`, send now / edit / remove | `sessionId`, `chatGeneration`, restore and rewind in flight, `sendBehavior` |
| Streaming and events | `handleEvent`, `handleSessionIdle`, `updateStreamingMessage`, `refreshMessages`, `toUiMessage` | `streamedParts`, `messageRoles`, `pendingInterrupts`, the duplicate-idle window, `transcriptGeneration` |
| Live resume (#25), catch-up and stalls | `resyncOpenChat`, `applyResync`, `maybeCatchUp`, `recordProgress`, `checkForStall`, `diagnoseSilentRun` | progress and catch-up clocks, `streamError`, `streamedParts`, `transcriptGeneration` |
| Permissions and questions | `respondToPermission`, `allowMcpServer`, auto-accept, `submitQuestion`, `cancelQuestion`, `dismissQuestion`, `refreshPendingQuestions` | `permissions`, `pendingQuestions`, `sessionDirectory`, `dismissedQuestionIds` (cleared on a chat switch) |
| File restore points | `restoreFilesTo`, `undoFileRestore`, `canRestoreFiles`, `recordTurnSnapshot`, `snapshotChanges`, `restoreJob`, turn numbering (`offlinePlaceholders`) | `fileRestore`, `isRestoringFiles`; `restoreInFlight` holds sends and queue drains |
| Rewind | `rewindTo`, `undoRewind`, `refreshRewind`, `keepRewind`, `discardedByRewind` | `rewoundFrom`, `isRewinding`, `editDraft`; `keepRewind` runs inside the send's state update |
| Previews | `openPatchDiff`, `openArtifact`, dismiss | `patchDiff`, `artifactPreview`; the diff falls back to snapshot changes |
| Context window | `refreshContextUsage`, `showContextBreakdown`, `compactContext` | `contextTokensUsed`, `contextWindow`, `sessionUsage`, `contextBreakdown` |
| Model and thinking selection | `selectConfiguration`, fast mode, ultracode, `serviceTierForPrompt`, `shouldSummarizeBeforePrompt` | its own fields; read by the send |
| Pull request badges | `trackPullRequests`, `dismissPullRequest`, `refreshPullRequests` | `pullRequests`; dismissals cleared on a chat switch |
| Session list and opening | `openSession`, `newSession`, subagent parent, rename, system prompt preset | resets nearly every other piece's per-chat state |
| Smaller pieces | drafts, attachments, voice, edit-last-message, errors and transient recovery | |

## Seams

- **State.** Every piece reads and writes the one `MutableStateFlow<ChatUiState>`. Passing that
  flow to a helper keeps the same object and the same `update` semantics.
- **Chat switches.** `openSession` and `newSession` clear per-chat sets (dismissed questions and
  pull requests, the stream cache, roles). After a move they call the helper's `forget...` at the
  same point.
- **Calls back into the view model.** Restore points and rewinds release held prompts when they
  land (`drainHeldPrompts`), and rewinds report errors through `reportError`, which may start the
  transient recovery. These are passed in as callbacks.
- **Calls into the helpers.** The send asks model selection for the service tier, ultracode and
  whether to summarize; restore points for the turn number, the snapshot and whether a restore is
  in flight; rewind for the transcript a new message follows; context usage for a refresh.
- **Not touched here.** The send and the post-send poll, held prompts, event handling and idle
  settling, live resume and catch-up, stalls, and transient recovery stay in the view model in
  this PR. They share `runGeneration`, `streamedParts`, `transcriptGeneration`, the interrupt
  window and the duplicate-idle window, and the race fixes in them (#76, #114, the duplicated idle,
  the poll that must not settle a newer turn) depend on that sharing. Splitting them needs its own
  review.
- **`afterTurn`.** The stale-record fix after an Antigravity turn is in
  `runtime/local/AntigravityRuntime.kt`, not here. The chat side of it is only `showRenamedTitle`,
  which stays as it is.

## Order

Least entangled first. One responsibility per commit.

1. **State types** to `ChatUiState.kt`, with the attachment index helpers. Pure move.
2. **Transcript mapping and merging** to `ChatTranscript.kt`. Pure functions. The pure half of
   `toUiMessage` becomes `toChatMessage(message)`, with a focused test; the view model
   keeps the rewind filter and the role bookkeeping.
3. **Streaming part merges** to `ChatStreaming.kt`: the part and delta merges from `handleEvent`
   and `updateStreamingMessage`'s body, as pure functions, with focused tests. The stream cache
   itself stays in the view model.
4. **Pull request badges** to `PullRequestBadges.kt`. Own state; cleared on a chat switch.
5. **Model and thinking selection** to `ChatModelSelection.kt`, with `codexServiceTier`.
6. **Context window** to `ChatContextUsage.kt`.
7. **Permissions and questions** to `ChatPermissions.kt` and `ChatQuestions.kt`.
8. **File restore points** to `ChatRestorePoints.kt`, with `snapshotCall` (focused test).
9. **Previews** (patch diff and artifacts) to `ChatPreviews.kt`.
10. **Rewind** to `ChatRewind.kt`.
11. **Dictation and read-aloud** to `ChatVoice.kt`.
12. **Edit and resend** of the last message to `ChatMessageEdit.kt`.
13. **The draft system prompt preset** to `SystemPromptDraft.kt`.

Steps 11 to 13 were added once 1 to 10 left the file at 2,467 lines: small, self-contained moves
that bring it closer to half without touching the shared state below.

Each step is checked with `spotlessCheck testGithubDebugUnitTest :app:lintGithubDebug` and the
detekt CLI step from `.github/workflows/android.yml`, and the baseline is compared against a
`--create-baseline` run.

## Behaviour that must not move

- **Scope and cancellation.** See the rules above.
- **`MutableStateFlow.update` order.** Where one call made several updates (`selectConfiguration`
  then `refreshFastMode` then `refreshUltracode`), the helper makes them in that order.
- **Live resume (#25).** `resyncOpenChat`, `applyResync`, the foreground collector and the catch-up
  loop stay in the view model, unchanged.
- **Held prompts per chat (#114, #76).** The queues and their drains stay in the view model.
  `restoreInFlight` and the rewind's `drainHeldPrompts` call keep their timing: the restore job
  still lets go of itself before it drains (`finishRestoreJob`).
- **Queued-prompt and status races.** `runGeneration`, the interrupt window and the duplicate-idle
  window stay together in the view model.
- **`keepRewind` side effect.** It records the rewound messages as discarded, including when the
  turn number is counted (`sentTurns`). It keeps doing so, from inside the same state update.

## Result

| File | Lines | Holds |
| --- | ---: | --- |
| `ChatViewModel.kt` | 2,319 | was 3,985 |
| `ChatUiState.kt` | 295 | state types, attachment index helpers |
| `ChatTranscript.kt` | 331 | transcript mapping and merging |
| `ChatStreaming.kt` | 113 | streaming part merges |
| `PullRequestBadges.kt` | 84 | pull request badges |
| `ChatModelSelection.kt` | 141 | model, fast mode, ultracode, summarize gate |
| `ChatContextUsage.kt` | 110 | context meter, sheet, compaction |
| `ChatPermissions.kt` | 111 | permission cards, auto-accept |
| `ChatQuestions.kt` | 339 | question cards and answers |
| `ChatRestorePoints.kt` | 165 | turn snapshots, restore and undo |
| `ChatPreviews.kt` | 98 | patch diff, artifact preview |
| `ChatRewind.kt` | 122 | runtime rewinds |
| `ChatVoice.kt` | 81 | dictation and read-aloud |
| `ChatMessageEdit.kt` | 97 | edit and resend |
| `SystemPromptDraft.kt` | 52 | the preset chosen before a session exists |

The tests passed unchanged after every step (2,374 before, 2,396 after with the focused tests for
`toChatMessage`, the streaming merges and `snapshotCall`). The detekt baseline went from 498 to
497 entries: five moved with their code, and `NestedBlockDepth` on `handleEvent` is gone.

## What remains

In rough order, least entangled first. Each needs its own review.

1. **Transient recovery** (`reportError`, `scheduleTransientRecovery`, `stillDisconnected`,
   `restoreConnection`, about 100 lines). `restoreConnection` clears the stream cache and bumps
   `transcriptGeneration`, so those need an owner first (see 4).
2. **Session opening** (`openSession`, `newSession`, the parent session, about 230 lines). Both
   reset most of the other pieces; with the helpers now holding their own state, this becomes a
   list of `forget...` calls plus the transcript load.
3. **Held prompts** (`messageQueue`, `offlineMessageQueue`, the drains, send now / edit / remove and
   the queue view collector in `init`, about 150 lines). Keep `holdsForRunningTurn`, the drain
   order and the restore and rewind gates exactly as they are (#76, #114).
4. **The stream cache and event handling** (`streamedParts`, `messageRoles`, `handleEvent`,
   `handleSessionIdle`, `refreshMessages`, the duplicate-idle window, the interrupt window). This is
   where the race fixes live; move them together, not one at a time.
5. **Live resume, catch-up and stalls** (#25: `resyncOpenChat`, `applyResync`, `maybeCatchUp`,
   `recordProgress`, `checkForStall`, `diagnoseSilentRun` and their loops in `init`). They share the
   progress clocks with event handling, so they follow 4.
6. **The send path** (`send`, `sendSlashCommand`, about 550 lines). The two carry near-identical
   post-send polls; folding them into one is a real change (the slash command passes no previews
   and merges inside its state update), so it should be its own PR with its own tests, before the
   send moves anywhere.

`LargeClass` and `TooManyFunctions` stay in the baseline for `ChatViewModel` until most of that is
done; `LongMethod` and `CyclomaticComplexMethod` for `send`, `sendSlashCommand` and `handleEvent`
go with 4 and 6.
