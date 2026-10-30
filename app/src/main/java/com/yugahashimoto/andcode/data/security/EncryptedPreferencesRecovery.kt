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
