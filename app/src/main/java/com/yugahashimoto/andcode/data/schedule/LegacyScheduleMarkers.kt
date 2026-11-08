package com.yugahashimoto.andcode.data.schedule

import android.content.SharedPreferences

/**
 * What the user removed or turned off while encrypted storage was unavailable, kept next to the
 * plaintext legacy copy of the schedules.
 *
 * In that fallback mode the encrypted store lives only in memory, and the legacy copy is the one
 * that survives a restart. Without these marks, a schedule deleted or turned off in that mode came
 * back armed after the next restart, still running its old prompt. Only schedule ids are written
 * here, nothing the encryption was protecting.
 *
 * An edit cannot be kept either (its new content would have to be written in plain text), so an
 * edited schedule comes back turned off, in its old form, for the user to check.
 */
internal class LegacyScheduleMarkers(
    private val preferences: SharedPreferences,
) {
    fun markDeleted(id: String) = update(KEY_DELETED) { it + id }

    fun markTurnedOff(id: String) = update(KEY_TURNED_OFF) { it + id }

    fun markTurnedOn(id: String) = update(KEY_TURNED_OFF) { it - id }

    /** Kept apart from turning off: turning an edited schedule back on must not bring back its old prompt armed. */
    fun markEdited(id: String) = update(KEY_EDITED) { it + id }

    /** [schedules] as the user left them: deleted ones gone, turned-off and edited ones off. */
    fun applyTo(schedules: List<Schedule>): List<Schedule> {
        val deleted = read(KEY_DELETED)
        val off = read(KEY_TURNED_OFF) + read(KEY_EDITED)
        return schedules
            .filterNot { it.id in deleted }
            .map { if (it.id in off) it.copy(enabled = false) else it }
    }

    /** Forgets every mark, once the legacy copy they apply to is gone. */
    fun clear() {
        preferences.edit().remove(KEY_DELETED).remove(KEY_TURNED_OFF).remove(KEY_EDITED).apply()
    }

    // A copy: the set getStringSet returns must not be changed in place.
    private fun read(key: String): Set<String> = preferences.getStringSet(key, null)?.toSet().orEmpty()

    private fun update(
        key: String,
        change: (Set<String>) -> Set<String>,
    ) {
        // commit, not apply: the point is that the mark is on disk before the app can be killed. It
        // blocks the caller briefly, but only in fallback mode, and only for a few ids.
        preferences.edit().putStringSet(key, change(read(key))).commit()
    }

    private companion object {
        const val KEY_DELETED = "fallback_deleted_schedule_ids"
        const val KEY_TURNED_OFF = "fallback_turned_off_schedule_ids"
        const val KEY_EDITED = "fallback_edited_schedule_ids"
    }
}
