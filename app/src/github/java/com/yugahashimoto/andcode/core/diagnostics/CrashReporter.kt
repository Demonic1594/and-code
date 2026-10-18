package com.yugahashimoto.andcode.core.diagnostics

import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Best-effort bridge for non-fatal diagnostics.
 *
 * Fatal crashes are collected by Crashlytics automatically. This wrapper keeps ordinary error
 * reporting from making an already failing app fail again, and centralizes the small amount of
 * sanitization needed before sending messages and keys off-device.
 */
object CrashReporter {
    @Volatile
    private var crashlytics: FirebaseCrashlytics? = null

    /**
     * Initializes Crashlytics but leaves collection off until [setEnabled] opts it in.
     *
     * Crash reporting follows the same explicit user opt-in as Analytics: nothing leaves the device
     * until the user allows it. The manifest default is off too, so no report can escape before this
     * runs.
     */
    fun install() {
        val client =
            runCatching { FirebaseCrashlytics.getInstance() }
                .getOrNull()
                ?: return
        crashlytics = client
        runCatching {
            client.setCrashlyticsCollectionEnabled(false)
            client.setCustomKey("app_version", com.yugahashimoto.andcode.BuildConfig.VERSION_NAME)
            client.setCustomKey(
                "build_type",
                if (com.yugahashimoto.andcode.BuildConfig.DEBUG) "debug" else "release",
            )
            client.setCustomKey("os_version", Build.VERSION.RELEASE)
        }
    }

    /** Turns collection on or off with the user's analytics/crash-reporting opt-in. */
    fun setEnabled(enabled: Boolean) {
        runCatching {
            crashlytics?.setCrashlyticsCollectionEnabled(enabled && !com.yugahashimoto.andcode.BuildConfig.DEBUG)
        }
    }

    fun log(message: String) {
        crashlytics?.let { client ->
            runCatching { client.log(CrashReportSanitizer.message(message)) }
        }
    }

    fun recordException(
        error: Throwable,
        message: String? = null,
        customKeys: Map<String, String> = emptyMap(),
    ) {
        val client = crashlytics ?: return
        runCatching {
            message?.let { client.log(CrashReportSanitizer.message(it)) }
            customKeys.forEach { (key, value) ->
                client.setCustomKey(
                    CrashReportSanitizer.key(key),
                    CrashReportSanitizer.value(value),
                )
            }
            client.recordException(error)
        }
    }

    /**
     * Reports a runtime session failure as a fixed category only.
     *
     * The provider's raw error text is intentionally not sent: it is free-form and can carry prompt
     * content, model output, file paths and hosts. The on-device notification still shows it.
     */
    fun recordRuntimeSessionError(
        runtimeId: String,
        errorType: String?,
    ) {
        val client = crashlytics ?: return
        val category = CrashReportSanitizer.errorCategory(runtimeId, errorType)
        runCatching {
            client.log(category)
            client.recordException(IllegalStateException(category))
        }
    }
}
