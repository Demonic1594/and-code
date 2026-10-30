package com.yugahashimoto.andcode.data.security

/**
 * Opens an encrypted store, escalating through the repairs a broken Keystore state needs.
 *
 * Opening the encrypted preferences runs inside [com.yugahashimoto.andcode.AndCodeApplication.onCreate],
 * so any exception that escapes here is a crash on every launch, with no screen to recover from.
 * Crashlytics showed three distinct ways the Keystore got there, each needing a bigger repair than
 * the last:
 *
 * 1. The stored keyset cannot be decrypted with the current master key (an OS update, or a backup
 *    restored without its device-bound key). Deleting the preferences file is enough.
 * 2. The master key itself is "permanently invalidated" or unusable. Only a freshly created preferences
 *    file and a new master key help, so the key is discarded - but only after the cheaper repair
 *    failed, because the key is shared by every encrypted file in the app.
 * 3. The Keystore cannot create a key at all. Nothing on the device can be repaired, so [fallback]
 *    keeps the app usable instead of bricking it.
 *
 * Only [Exception] is caught: an [Error] such as [OutOfMemoryError] is not a Keystore state and must
 * not be hidden behind a silent reset of the user's settings. A discard step that fails is ignored,
 * since the next open attempt is what decides whether the repair worked.
 */
internal fun <T> openWithRecovery(
    open: () -> T,
    discardStoredPreferences: () -> Unit,
    discardMasterKey: () -> Unit,
    fallback: () -> T,
): T {
    tryOpen(open).onSuccess { return it }

    ignoringFailure(discardStoredPreferences)
    tryOpen(open).onSuccess { return it }

    ignoringFailure(discardStoredPreferences)
    ignoringFailure(discardMasterKey)
    tryOpen(open).onSuccess { return it }

    return fallback()
}

/**
 * Opens the app's encrypted files through [openWithRecovery] and remembers what that learned.
 *
 * Two facts must outlive a single call, and both exist because the app builds more than one
 * repository on the same file (the Application and Koin each construct a settings repository):
 *
 * - A file that fell back keeps one shared fallback. Real preferences are shared per file, so two
 *   callers must see each other's writes even in the degraded mode; separate fallbacks would let
 *   one caller save a token the other never reads. It also means the repairs, which delete data,
 *   are not run a second time for a file already known to be unavailable.
 * - Once any file has opened, the master key is known to work, so a later failure is that file's own
 *   problem. The key is shared by every encrypted file, and replacing it would destroy the files
 *   that are fine.
 *
 * Calls are serialized so concurrent callers cannot repair the same file twice.
 */
internal class RecoveringOpener<T : Any>(
    private val createFallback: (name: String) -> T,
) {
    private val fallbacks = HashMap<String, T>()
    private var masterKeyProvenUsable = false

    @Synchronized
    fun open(
        name: String,
        create: () -> T,
        discardStoredPreferences: () -> Unit,
        discardMasterKey: () -> Unit,
    ): T {
        fallbacks[name]?.let { return it }
        return openWithRecovery(
            open = { create().also { masterKeyProvenUsable = true } },
            discardStoredPreferences = discardStoredPreferences,
            discardMasterKey = { if (!masterKeyProvenUsable) discardMasterKey() },
            fallback = { createFallback(name).also { fallbacks[name] = it } },
        )
    }
}

private fun <T> tryOpen(open: () -> T): Result<T> =
    try {
        Result.success(open())
    } catch (error: Exception) {
        Result.failure(error)
    }

private fun ignoringFailure(step: () -> Unit) {
    try {
        step()
    } catch (_: Exception) {
        // The following open attempt reports whether the repair worked.
    }
}
