package com.yugahashimoto.andcode.core.diagnostics

import com.yugahashimoto.andcode.core.security.SecretRedaction

internal object CrashReportSanitizer {
    private const val MAX_LOG_CHARS = 1_000
    private const val MAX_KEY_CHARS = 40
    private const val MAX_VALUE_CHARS = 100
    private const val MAX_TYPE_CHARS = 64

    fun message(value: String): String = SecretRedaction.redact(value.replace(Regex("\\s+"), " ").trim()).take(MAX_LOG_CHARS)

    fun key(value: String): String = value.replace(Regex("[^A-Za-z0-9_]"), "_").take(MAX_KEY_CHARS).ifBlank { "key" }

    fun value(value: String): String = message(value).take(MAX_VALUE_CHARS)

    /**
     * A fixed, non-free-form failure category for a runtime error report.
     *
     * Only the runtime id and a charset-limited error type are kept. The runtime's own error text is
     * deliberately dropped: it can quote prompts, model output, file paths and hosts, none of which
     * belong in an off-device report.
     */
    fun errorCategory(
        runtimeId: String,
        errorType: String?,
    ): String {
        // The provider supplies the type, so treat it as untrusted: only an identifier-shaped value
        // survives. Anything carrying spaces, punctuation, paths or hosts collapses to "unknown"
        // instead of being reported.
        val type =
            errorType
                ?.take(MAX_TYPE_CHARS)
                ?.takeIf { SAFE_TYPE.matches(it) }
                ?: "unknown"
        val runtime = runtimeId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(MAX_KEY_CHARS).ifBlank { "runtime" }
        return "Runtime session error: $runtime/$type"
    }

    private val SAFE_TYPE = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
}
