package com.yugahashimoto.andcode.data.security

import android.content.SharedPreferences

/**
 * A [SharedPreferences] that lives only in memory.
 *
 * It is the last resort when the device's Keystore cannot back encrypted preferences at all (see
 * [openWithRecovery]). Keeping secrets such as API keys and tokens in a plaintext file instead would
 * quietly weaken the guarantee the rest of the app relies on, so nothing is persisted: the app runs,
 * and settings are lost when the process ends.
 *
 * Change listeners are accepted and never called; nothing in the app observes preference changes.
 * Like the platform implementation, [edit] stages changes until [SharedPreferences.Editor.commit] or
 * [SharedPreferences.Editor.apply], and a `clear` is applied before the rest of the batch.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val store = HashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> =
        synchronized(store) {
            store.mapValuesTo(HashMap()) { (_, value) -> if (value is Set<*>) HashSet(value) else value }
        }

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = synchronized(store) { store[key] as String? } ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? =
        synchronized(store) {
            @Suppress("UNCHECKED_CAST")
            (store[key] as Set<String>?)?.let { HashSet(it) }
        } ?: defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = synchronized(store) { store[key] as Int? } ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = synchronized(store) { store[key] as Long? } ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = synchronized(store) { store[key] as Float? } ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = synchronized(store) { store[key] as Boolean? } ?: defValue

    override fun contains(key: String?): Boolean = synchronized(store) { store.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class InMemoryEditor : SharedPreferences.Editor {
        // A null value stages a removal, matching how the platform treats putString(key, null).
        private val staged = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = stage(key, values?.let { HashSet(it) })

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = stage(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            write()
            return true
        }

        override fun apply() = write()

        private fun stage(
            key: String?,
            value: Any?,
        ): SharedPreferences.Editor {
            if (key != null) staged[key] = value
            return this
        }

        private fun write() {
            synchronized(store) {
                if (clearRequested) store.clear()
                staged.forEach { (key, value) ->
                    if (value == null) store.remove(key) else store[key] = value
                }
            }
            staged.clear()
            clearRequested = false
        }
    }
}
