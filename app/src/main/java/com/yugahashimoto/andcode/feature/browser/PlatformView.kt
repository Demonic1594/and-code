package com.yugahashimoto.andcode.feature.browser

/**
 * Builds a view the platform may refuse to build, returning null instead of throwing.
 *
 * WebView needs the system's WebView provider, and starting it can fail on a given device: on one
 * vivo phone the provider's resource registration threw `Resources.NotFoundException`, which
 * surfaced as an `AndroidRuntimeException` inside the Compose view factory and took the whole app
 * down. Nothing the app does can repair that, so the caller shows an explanation instead.
 *
 * Only [Exception] is caught: an [OutOfMemoryError] is not a missing provider, and hiding it would
 * leave the screen blaming WebView for a memory problem.
 */
internal fun <T : Any> viewOrNull(
    create: () -> T,
    onFailure: (Exception) -> Unit = {},
): T? =
    try {
        create()
    } catch (error: Exception) {
        onFailure(error)
        null
    }
