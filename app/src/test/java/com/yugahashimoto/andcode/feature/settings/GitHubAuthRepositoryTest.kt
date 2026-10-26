package com.yugahashimoto.andcode.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubAuthRepositoryTest {
    @Test
    fun `blank client id disables device flow`() {
        assertFalse(GitHubAuthRepositoryTestProbe.isConfigured(""))
        assertTrue(GitHubAuthRepositoryTestProbe.isConfigured("client-id"))
    }

    /**
     * Confirmed on a real device: connecting GitHub failed once with "Unable to resolve host
     * github.com" and succeeded moments later with no other change - a transient DNS blip, not a
     * real connectivity problem. retryOnUnknownHost is what absorbs that automatically instead of
     * making the user notice the failure and tap "Connect" again themselves.
     */
    @Test
    fun `retryOnUnknownHost absorbs a transient DNS failure and returns the eventual success`() =
        runTest {
            var attempts = 0
            val result =
                retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                    attempts++
                    if (attempts < 3) throw UnknownHostException("Unable to resolve host \"github.com\"")
                    "connected"
                }

            assertEquals("connected", result)
            assertEquals(3, attempts)
        }

    @Test
    fun `retryOnUnknownHost gives up once its attempts are exhausted`() =
        runTest {
            var attempts = 0

            assertThrows(UnknownHostException::class.java) {
                kotlinx.coroutines.runBlocking {
                    retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                        attempts++
                        throw UnknownHostException("Unable to resolve host \"github.com\"")
                    }
                }
            }

            assertEquals(3, attempts)
        }

    /** Only the confirmed DNS-blip exception is retried; any other failure must surface at once. */
    @Test
    fun `retryOnUnknownHost does not retry failures other than UnknownHostException`() =
        runTest {
            var attempts = 0

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                        attempts++
                        throw IOException("connection reset")
                    }
                }
            }

            assertEquals(1, attempts)
        }

    @Test
    fun `retryOnUnknownHost succeeds immediately without retrying when there is no failure`() =
        runTest {
            var attempts = 0
            val result =
                retryOnUnknownHost(attempts = 3, delayMillis = 10) {
                    attempts++
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(1, attempts)
        }

    @Test
    fun `a token GitHub accepts is kept with its login`() =
        runTest {
            val store = FakeAccountStore()
            val seen = mutableListOf<String>()
            val repo = GitHubAuthRepository(store, stubClient(200, """{"login":"octocat"}""", seen), clientId = "")

            val account = repo.signIn("  ghp_token  ")

            assertEquals("octocat", account.login)
            assertEquals("ghp_token", store.githubToken)
            assertEquals("octocat", store.githubLogin)
            assertEquals(listOf("Bearer ghp_token"), seen)
            // A new account's star state is not the old one's.
            assertEquals(null, store.githubStarredCache)
        }

    @Test
    fun `a token GitHub refuses leaves nothing behind`() =
        runTest {
            val store = FakeAccountStore()
            val repo = GitHubAuthRepository(store, stubClient(401, """{"message":"Bad credentials"}""", mutableListOf()), clientId = "")

            assertThrows(GitHubTokenRejectedException::class.java) { kotlinx.coroutines.runBlocking { repo.signIn("ghp_wrong") } }

            assertEquals(null, store.githubToken)
            assertEquals(null, store.githubLogin)
        }

    @Test
    fun `checking a token writes nothing`() =
        runTest {
            val store = FakeAccountStore()
            val repo = GitHubAuthRepository(store, stubClient(200, """{"login":"octocat"}""", mutableListOf()), clientId = "")

            assertEquals("octocat", repo.verifyAccount("ghp_token")?.login)
            assertEquals(null, store.githubToken)
            assertEquals(null, store.githubLogin)
        }
}

private class FakeAccountStore : GitHubAccountStore {
    override var githubToken: String? = null
    override var githubLogin: String? = null
    override var githubStarredCache: Boolean? = true
    override var githubStarStatusCheckedAt: Long = 42L
}

/** A client that answers every request with [code] and [body], counting what it was asked. */
private fun stubClient(
    code: Int,
    body: String,
    seenTokens: MutableList<String>,
) = OkHttpClient.Builder()
    .addInterceptor { chain ->
        seenTokens += chain.request().header("Authorization").orEmpty()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

private object GitHubAuthRepositoryTestProbe {
    fun isConfigured(clientId: String) = clientId.isNotBlank()
}
