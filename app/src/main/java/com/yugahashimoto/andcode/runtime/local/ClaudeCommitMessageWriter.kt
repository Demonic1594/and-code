package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.feature.git.CommitMessagePrompt
import com.yugahashimoto.andcode.feature.git.CommitMessageRequest
import com.yugahashimoto.andcode.feature.git.CommitMessageResult
import com.yugahashimoto.andcode.feature.git.CommitMessageWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes commit messages with Claude Code's own sign-in: AndCode holds no model keys, so the one
 * agent that can answer a single question cheaply ([ClaudeCodeRuntime.writeCommitMessage], no tools)
 * is asked. Without Claude Code installed there is no writer, and the screen says how to get one.
 */
class ClaudeCommitMessageWriter(
    private val runtime: ClaudeCodeRuntime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CommitMessageWriter {
    override suspend fun write(request: CommitMessageRequest): CommitMessageResult =
        withContext(ioDispatcher) {
            if (!runtime.canAskOnce()) return@withContext CommitMessageResult.Unavailable
            val reply = runtime.writeCommitMessage(CommitMessagePrompt.SYSTEM, CommitMessagePrompt.build(request))
            CommitMessagePrompt.clean(reply)?.let(CommitMessageResult::Written) ?: CommitMessageResult.Failed
        }
}
