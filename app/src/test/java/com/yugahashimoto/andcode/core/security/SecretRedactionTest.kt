package com.yugahashimoto.andcode.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {
    @Test
    fun `redacts an Anthropic style api key`() {
        val message = "auth failed for sk-ant-api03-abcdefghijklmnop"
        val redacted = SecretRedaction.redact(message)
        assertFalse(redacted.contains("sk-ant-api03-abcdefghijklmnop"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `redacts a project-scoped OpenAI style api key with hyphens in the body`() {
        // sk-proj-/sk-svcacct-/sk-admin- keys are hyphenated, not the plain alphanumeric body an
        // [A-Za-z0-9]-only pattern would assume.
        val message = "invalid key sk-proj-A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5-P6Q7R8S9T0U1V2W3X4Y5Z6"
        val redacted = SecretRedaction.redact(message)
        assertFalse(redacted.contains("A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5-P6Q7R8S9T0U1V2W3X4Y5Z6"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `redacts a github token`() {
        val redacted = SecretRedaction.redact("token ghp_1234567890abcdefghijklmnopqrstuv leaked in output")
        assertFalse(redacted.contains("ghp_1234567890abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `redacts a bearer authorization header`() {
        val redacted = SecretRedaction.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `redacts a password field`() {
        val redacted = SecretRedaction.redact("connecting with password=Sup3rSecret!")
        assertFalse(redacted.contains("Sup3rSecret!"))
    }

    @Test
    fun `redacts the credential behind a Basic authorization header`() {
        val redacted = SecretRedaction.redact("Authorization: Basic dXNlcjpzZWNyZXQ=")
        assertFalse(redacted.contains("dXNlcjpzZWNyZXQ="))
    }

    @Test
    fun `redacts every pair of a cookie header`() {
        val redacted = SecretRedaction.redact("Cookie: a=1; session=abc123def456")
        assertFalse(redacted.contains("abc123def456"))
    }

    @Test
    fun `redacts a quoted json credential field`() {
        val redacted = SecretRedaction.redact("""{"api_key":"sk-live-0123456789"}""")
        assertFalse(redacted.contains("sk-live-0123456789"))
    }

    @Test
    fun `redacts env-style api key and refresh token names`() {
        val api = SecretRedaction.redact("ANTHROPIC_API_KEY=sk-ant-secret-value-123456")
        assertFalse(api.contains("sk-ant-secret-value-123456"))
        val token = SecretRedaction.redact("refresh_token=rt_0123456789abcdef")
        assertFalse(token.contains("rt_0123456789abcdef"))
    }

    @Test
    fun `redacts credentials embedded in a url userinfo`() {
        val redacted = SecretRedaction.redact("fetching https://alice:hunter2@example.com/callback")
        assertFalse(redacted.contains("hunter2"))
    }

    @Test
    fun `leaves ordinary text untouched`() {
        assertEquals("Runtime session failed", SecretRedaction.redact("Runtime session failed"))
    }

    @Test
    fun `strips oauth query parameters from urls before logging`() {
        val url = "https://claude.ai/oauth/authorize?code=abc123&state=xyz789"
        assertEquals("https://claude.ai/oauth/authorize", SecretRedaction.redactUrlQuery(url))
    }

    @Test
    fun `url without a query string is returned unchanged`() {
        val url = "https://claude.ai/oauth/authorize"
        assertEquals(url, SecretRedaction.redactUrlQuery(url))
    }

    @Test
    fun `redact also strips query strings from urls embedded in arbitrary text`() {
        // Covers callers that pass a message through redact() directly rather than routing a URL
        // through redactUrlQuery() explicitly first.
        val message = "session failed calling https://api.example.com/callback?code=abc123&state=xyz789 (timeout)"
        val redacted = SecretRedaction.redact(message)
        assertFalse(redacted.contains("code=abc123"))
        assertFalse(redacted.contains("state=xyz789"))
        assertTrue(redacted.contains("https://api.example.com/callback"))
    }
}
