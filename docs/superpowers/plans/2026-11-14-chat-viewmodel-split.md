# Splitting ChatViewModel.kt (2026-11-14)

Status: **in progress**. `ChatViewModel.kt` is 3,985 lines, more than twice the next largest file.
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
   `toUiMessage` becomes `OpenCodeMessage.toChatMessage()`, with a focused test; the view model
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

## What remains after this PR

To be filled in with the final line counts when the steps are done.
