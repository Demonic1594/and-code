package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.feature.chat.IssueComment
import com.yugahashimoto.andcode.feature.chat.IssueRef
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubIssueThreadTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun client(token: String? = "t0ken") = GitHubApiClient(token = { token }, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun serve(
        issue: MockResponse,
        comments: MockResponse,
    ) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/repos/o/r/issues/5" -> issue
                        "/repos/o/r/issues/5/comments?per_page=100" -> comments
                        else -> MockResponse().setResponseCode(404)
                    }
            }
    }

    @Test
    fun `an issue is read with its comments, signed in`() =
        runBlocking {
            serve(
                MockResponse().setBody(
                    """{"title":"Crash","body":"It crashes","state":"open","user":{"login":"alice"},"labels":[{"name":"bug"}],"extra":1}""",
                ),
                MockResponse().setBody("""[{"body":"Same","user":{"login":"bob"}},{"body":null,"user":null}]"""),
            )

            val thread = requireNotNull(client().getIssueThread(IssueRef("o", "r", 5)))

            assertEquals("Crash", thread.title)
            assertEquals("alice", thread.author)
            assertEquals(listOf("bug"), thread.labels)
            assertEquals(listOf(IssueComment("bob", "Same"), IssueComment("", "")), thread.comments)
            assertEquals("Bearer t0ken", server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `comments that cannot be read leave the issue without them, and a missing issue is null`() =
        runBlocking {
            serve(MockResponse().setBody("""{"title":"T","body":null}"""), MockResponse().setResponseCode(500))

            val thread = requireNotNull(client(token = null).getIssueThread(IssueRef("o", "r", 5)))
            assertEquals("", thread.body)
            assertEquals(emptyList<IssueComment>(), thread.comments)
            assertNull(client().getIssueThread(IssueRef("o", "r", 6)))
        }
}
