package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import kotlinx.serialization.json.JsonPrimitive

/**
 * Models offered for Claude Code's `--model`.
 *
 * The CLI has no command that enumerates models, so the picker offers the aliases the CLI documents
 * as input — those are a stable contract, unlike model versions, and always resolve to the newest
 * matching model. No version is hardcoded here.
 *
 * The name shown for each alias is the model id Claude itself reports (`system/init` and every
 * assistant message carry the resolved id), remembered per alias once a session has used it. Until
 * then the alias stands in.
 */
object ClaudeModels {
    const val PROVIDER_ID = "claude-code"

    /** Used until the user picks one; the mid-range alias is the safest assumption. */
    const val DEFAULT_MODEL = "sonnet"

    /** Aliases the CLI accepts, in the order the picker should show them. */
    private val ALIASES = listOf("fable", "opus", DEFAULT_MODEL, "haiku")

    /** Values `--effort` accepts. Surfaced as model variants so the chat's thinking chip picks them up. */
    private val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

    /** CLI argument for [effort], or null to leave the model's own default in place. */
    fun cliEffort(effort: String?): String? = effort?.takeIf { it in EFFORT_LEVELS }

    /**
     * Models the CLI refuses ultracode for, as it decides it: the ones without the `xhigh` effort.
     * Newer models, the ones the aliases resolve to today included, support it.
     */
    private val ULTRACODE_UNSUPPORTED =
        setOf(
            "claude-opus-4-0",
            "claude-opus-4-1",
            "claude-opus-4-5",
            "claude-opus-4-6",
            "claude-sonnet-4-0",
            "claude-sonnet-4-5",
            "claude-sonnet-4-6",
            "claude-haiku-4-5",
        )

    /**
     * Whether [modelId] (an alias, or the id Claude reported for one) can run in ultracode. A dated
     * suffix ("claude-haiku-4-5-20251001") or a context suffix ("[1m]") does not change the answer.
     */
    fun supportsUltracode(modelId: String): Boolean {
        val id = modelId.lowercase().substringBefore('[').replace(DATE_SUFFIX, "")
        return !id.startsWith("claude-3-") && !id.startsWith("claude-3.") && id !in ULTRACODE_UNSUPPORTED
    }

    /**
     * `--settings` for a session in ultracode, or null for none. Claude Code reads ultracode as a
     * session setting; the CLI ignores it on an account or model without workflows.
     */
    fun cliSettings(ultracode: Boolean): String? = if (ultracode) "{\"ultracode\":true}" else null

    private val DATE_SUFFIX = Regex("-\\d{8}$")

    /**
     * @param resolved alias to the model id Claude reported for it, as learned from run output.
     * @param workflows whether the CLI offered its Workflow tool at the last session start, or null
     *   before any has started. False hides ultracode: the account cannot run it.
     */
    fun catalog(
        resolved: Map<String, String>,
        workflows: Boolean? = null,
    ): ProviderCatalog =
        ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = PROVIDER_ID,
                        name = "Claude Code",
                        models =
                            ALIASES.associateWith { alias ->
                                OpenCodeModel(
                                    id = alias,
                                    providerId = PROVIDER_ID,
                                    name = resolved[alias] ?: alias.replaceFirstChar(Char::titlecase),
                                    variants = EFFORT_LEVELS.associateWith { JsonPrimitive(it) },
                                    claudeUltracode = workflows != false && supportsUltracode(resolved[alias] ?: alias),
                                )
                            },
                    ),
                ),
            default = mapOf(PROVIDER_ID to DEFAULT_MODEL),
            connected = listOf(PROVIDER_ID),
        )

    /**
     * CLI argument for [modelId].
     *
     * An id this build does not know about is still passed through: the CLI validates it, and
     * refusing it here would strand a session that remembers a newer alias or a full model name.
     */
    fun cliModel(modelId: String?): String? = modelId?.takeIf(String::isNotBlank)
}
