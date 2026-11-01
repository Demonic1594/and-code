# Follow-ups from the Oct 31 upstream sync (PR #52)

Status: **not started**. Raised during review of the upstream sync and deferred so the sync could
merge. Items 1-3 sit in code brought in from upstream unchanged; consider fixing them upstream.

1. **Schedules can come back in fallback mode.** When encrypted storage falls back to memory,
   `ScheduleRepository` keeps the plaintext legacy copy, and edits, disables and deletes only
   change memory. After a restart the old copy reloads and its alarm is armed again. Fix: write a
   durable "revoked" marker, or do not auto-arm schedules restored from the legacy copy until the
   user confirms them.
2. **Recovery deletes the file on any failure.** `openWithRecovery` deletes the preferences file
   after the first failed open, whatever the exception. Narrow the delete to the keyset-decryption
   failure; for Keystore or I/O errors, retry without deleting, then fall back. Confirm the exact
   Tink / security-crypto exception types on a device first.
3. **Recovery can delete the shared master key.** `EncryptedPreferences` discards the default
   master key when the first file to open fails twice, which makes every other encrypted file
   (schedules) unreadable. Coordinate key replacement across all encrypted stores, or give each
   store its own key alias.
4. **Runtime status race.** `LocalRuntimeManager.status()` reads the state, probes, then publishes
   without rechecking, so a probe can overwrite Starting/Installing and release the wake lock.
   Fix: publish with `compareAndSet` against the state read before probing. Add a unit test.
5. **Long lines in the code viewer.** `highlightRange` has no per-line cap: a minified file under
   250k characters can put tens of thousands of spans on one line, and every line scans all search
   matches. Cap token spans and search marks on lines over 2,000 characters, with tests at, and
   over, the limit. **Done**: the fork's tokens are computed once per file, so here the cost was the
   spans and a scan of every match on every line; a line over 2,000 characters gets no token spans,
   matches are found by binary search, and a long line shows at most 500 marks plus the current one.
6. **Drawer search field label.** The drawer search field has no Material label for TalkBack
   beyond its placeholder; give it a proper label without hiding the typed text (a
   `contentDescription` on the field does hide it, which was tried and reverted).

## Follow-ups found while building the remaining-work items

7. **Queued prompts have no chat.** `messageQueue` and `offlineMessageQueue` carry no session id
   and are not cleared on a chat switch, so a prompt queued in one chat can be sent into another
   by any drain (idle, reconnect, after a rewind or a file restore). Give `QueuedPrompt` the
   session it belongs to and drain only the open chat's prompts. Predates the restore feature.
8. **Snapshot storage.** File-restore snapshots keep every git object (gc is off so detached
   snapshots survive) and outlive a deleted workspace. Prune snapshots past the 200-turn index,
   and delete a workspace's history when the workspace is deleted.
9. **Restore refusal message.** A refused restore (an ignored file in the way) shows a generic
   failure; say that nothing changed and which path blocked it.
10. **Process screen (supervisor part 2).** The in-app list of supervised processes, the restart
   offer after the app was closed, the Artifacts integration, and a notice when an instructions
   file was customised and lacks the `andcode-run` section.
