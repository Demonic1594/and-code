package com.yugahashimoto.andcode.core.security

/**
 * Scrubs common credential/token shapes out of free-form text before it reaches a log line, crash
 * report, or any other place that can outlive the process - error messages surfaced by a provider or
 * CLI can otherwise echo back the secret that caused them.
 *
 * This is a best-effort, pattern-based scrub, not a guarantee that every possible credential shape
 * is caught: an unlabeled key, or a provider key format not covered by [PATTERNS], can still slip
 * through. Prefer not to put a provider's raw error text into a message that reaches a log or crash
 * report in the first place; treat this as defense in depth, not the only safeguard. See
 * `PRIVACY.md` for how this limitation is disclosed to users.
 */
object SecretRedaction {
    private val PATTERNS =
        listOf(
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}=*") to "Bearer <redacted>",
            // Matches the scheme *and* the credential after it, so `Authorization: Basic <base64>`
            // cannot leave the Base64 blob behind after only the scheme word is consumed.
            Regex("(?i)\\bauthorization\\s*[:=]\\s*[^\\s,;}]+(?:\\s+[^\\s,;}]+)?") to "authorization=<redacted>",
            // The whole Cookie line, not just the first pair: `a=1; session=...` would otherwise
            // keep the session cookie in the clear.
            Regex("(?i)\\bcookie\\s*[:=][^\\r\\n]*") to "cookie=<redacted>",
            Regex("sk-ant-[A-Za-z0-9_-]{10,}") to "sk-ant-<redacted>",
            // Covers plain OpenAI-style keys (sk-<random>) as well as the newer, hyphenated
            // sk-proj-/sk-svcacct-/sk-admin- project-scoped key formats, which a body limited to
            // [A-Za-z0-9] would otherwise leave almost entirely unredacted.
            Regex("sk-[A-Za-z0-9_-]{10,}") to "sk-<redacted>",
            Regex("gh[pousr]_[A-Za-z0-9]{20,}") to "gh_<redacted>",
            // Any `key=value` / `"key": "value"` shape whose key names a credential. The key prefix
            // allows `_`/`-` so env-style names (`ANTHROPIC_API_KEY`, `refresh_token`) match despite
            // the `\b` boundary an underscore would otherwise break, and the optional quotes cover
            // quoted JSON keys (`{"api_key":"..."}`) that the old unquoted patterns missed.
            Regex(
                "(?i)[A-Za-z0-9_-]*(?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|" +
                    "client[_-]?secret|secret|token|password)[\"']?\\s*[:=]\\s*[\"']?[^\\s,;}\"']+",
            ) to "<redacted>",
            // URL userinfo (`https://user:pass@host/...`) has to be stripped before the query rule,
            // which otherwise leaves the credentials sitting in front of the host.
            Regex("(https?://)[^/@\\s:]+:[^/@\\s]+@") to "$1<redacted>@",
            // Any http(s) URL with a query string, even one not routed through redactUrlQuery()
            // explicitly - the query is where an OAuth code/state/token parameter would live.
            Regex("(https?://[^\\s?#]+)\\?[^\\s]*") to "$1?<redacted>",
        )

    /**
     * Replaces anything that looks like a credential with a fixed placeholder. Best-effort: see the
     * class doc comment above.
     */
    fun redact(text: String): String = PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /**
     * Drops everything after `?` or `#`, which is where an OAuth `code`, `state`, or `token` query
     * parameter would live, keeping only the scheme, host, and path for diagnostics.
     */
    fun redactUrlQuery(url: String): String = url.substringBefore('?').substringBefore('#')
}
