package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.content.SharedPreferences
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.feature.chat.RestoreResult
import com.yugahashimoto.andcode.feature.chat.TurnSnapshotIndex
import com.yugahashimoto.andcode.feature.chat.TurnSnapshots

/** [TurnSnapshots] on [ShadowSnapshots], with each turn's snapshot id kept in [index]. */
class ShadowTurnSnapshots(
    private val shadow: ShadowSnapshots,
    private val index: TurnSnapshotIndex,
) : TurnSnapshots {
    override suspend fun record(
        sessionId: String,
        turn: Int,
        directory: String,
    ) {
        shadow.snapshot(directory, RECORD_LOCK_WAIT_MILLIS)?.let { index.put(sessionId, turn, it) }
    }

    override fun has(
        sessionId: String,
        turn: Int,
    ): Boolean = index.get(sessionId, turn) != null

    override suspend fun restore(
        sessionId: String,
        turn: Int,
        directory: String,
    ): RestoreResult = index.get(sessionId, turn)?.let { shadow.restoreChecked(directory, it) } ?: RestoreResult.Failed

    override suspend fun undoRestore(
        directory: String,
        token: String,
    ): Boolean = shadow.restore(directory, token) != null

    override suspend fun turnChanges(
        sessionId: String,
        turn: Int,
        directory: String,
    ): List<OpenCodeFileChange>? {
        val from = index.get(sessionId, turn) ?: return null
        val to = index.get(sessionId, turn + 1) ?: shadow.snapshot(directory) ?: return null
        return shadow.diff(directory, from, to)
    }

    private companion object {
        /** How long a message waits for a restore running elsewhere before going without a snapshot. */
        const val RECORD_LOCK_WAIT_MILLIS = 2_000L
    }
}

/**
 * [TurnSnapshotIndex] in shared preferences: one entry per chat, `turn=id` pairs, the newest
 * [MAX_TURNS] kept. Snapshot ids are not secrets, so plain preferences are enough.
 */
class SharedPreferencesTurnSnapshotIndex(
    private val preferences: SharedPreferences,
) : TurnSnapshotIndex {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    @Synchronized
    override fun get(
        sessionId: String,
        turn: Int,
    ): String? = read(sessionId)[turn]

    @Synchronized
    override fun put(
        sessionId: String,
        turn: Int,
        snapshot: String,
    ) {
        val turns = read(sessionId).toMutableMap().apply { put(turn, snapshot) }
        val kept = turns.toSortedMap().entries.toList().takeLast(MAX_TURNS)
        preferences.edit().putString(sessionId, kept.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    private fun read(sessionId: String): Map<Int, String> =
        preferences.getString(sessionId, null).orEmpty().split(';').mapNotNull { entry ->
            val turn = entry.substringBefore('=').toIntOrNull()
            val id = entry.substringAfter('=', missingDelimiterValue = "")
            if (turn == null || id.isEmpty()) null else turn to id
        }.toMap()

    private companion object {
        const val PREFS_NAME = "andcode_turn_snapshots"
        const val MAX_TURNS = 200
    }
}
