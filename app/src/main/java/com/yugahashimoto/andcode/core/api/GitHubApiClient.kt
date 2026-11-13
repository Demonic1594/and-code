package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.feature.chat.IssueComment
import com.yugahashimoto.andcode.feature.chat.IssueRef
import com.yugahashimoto.andcode.feature.chat.IssueThread
import com.yugahashimoto.andcode.feature.workspace.GitHubReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads GitHub over the REST API.
 *
 * The token is read per request rather than captured once, because it is stored encrypted and can
 * be connected, replaced, or disconnected while the app is running.
 */
class GitHubApiClient(
    private val token: () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.github.com",
) {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun getPullRequests(
        owner: String,
        repo: String,
        branch: String,
    ): List<GitHubReference> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    buildRequest(
                        "$baseUrl/repos/$owner/$repo/pulls".toHttpUrl().newBuilder()
                            .addQueryParameter("head", "$owner:$branch")
                            .build().toString(),
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GitHubPull>>(body)
                    items.map { pull ->
                        GitHubReference(
                            type = "PR",
                            number = pull.number,
                            title = pull.title,
                            url = pull.htmlUrl,
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * The live state of a single pull request, or null when GitHub cannot be reached, the pull
     * request is private to an account the app is not signed in to, or the response is unusable.
     * Callers keep whatever they showed before rather than blanking the badge on a failed refresh.
     */
    suspend fun getPullRequest(ref: PullRequestRef): PullRequestStatus? =
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest("$baseUrl/repos/${ref.owner}/${ref.repo}/pulls/${ref.number}")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val detail = json.decodeFromString<GitHubPullDetail>(response.body?.string().orEmpty())
                    PullRequestStatus(
                        ref = ref,
                        state =
                            pullRequestState(
                                state = detail.state,
                                draft = detail.draft,
                                merged = detail.merged,
                                mergeableState = detail.mergeableState,
                            ),
                        additions = detail.additions,
                        deletions = detail.deletions,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun searchIssues(
        owner: String,
        repo: String,
        query: String,
    ): List<GitHubReference> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    buildRequest(
                        "$baseUrl/search/issues".toHttpUrl().newBuilder()
                            .addQueryParameter("q", "repo:$owner/$repo $query")
                            .build().toString(),
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val result = json.decodeFromString<GitHubSearchResult>(body)
                    result.items.map { item ->
                        GitHubReference(
                            type = "Issue",
                            number = item.number,
                            title = item.title,
                            url = item.htmlUrl,
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * An issue and the comments under it (the first 100, oldest first), for starting a chat from it.
     * Null when GitHub cannot be reached, the issue is private to an account the app is not signed
     * in to, or the response is unusable; comments that cannot be read leave the issue without them.
     */
    suspend fun getIssueThread(ref: IssueRef): IssueThread? =
        withContext(Dispatchers.IO) {
            try {
                val base = "$baseUrl/repos/${ref.owner}/${ref.repo}/issues/${ref.number}"
                val issue =
                    client.newCall(buildRequest(base)).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        json.decodeFromString<GitHubIssueDetail>(response.body?.string().orEmpty())
                    }
                val comments =
                    runCatching {
                        client.newCall(buildRequest("$base/comments?per_page=100")).execute().use { response ->
                            if (!response.isSuccessful) {
                                emptyList()
                            } else {
                                json.decodeFromString<List<GitHubComment>>(response.body?.string().orEmpty())
                            }
                        }
                    }.getOrDefault(emptyList())
                IssueThread(
                    ref = ref,
                    title = issue.title,
                    author = issue.user?.login.orEmpty(),
                    body = issue.body.orEmpty(),
                    state = issue.state,
                    labels = issue.labels.map { it.name },
                    comments = comments.map { IssueComment(it.user?.login.orEmpty(), it.body.orEmpty()) },
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .apply {
                token()?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()

    @Serializable
    private data class GitHubPull(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    @Serializable
    private data class GitHubPullDetail(
        val state: String = "open",
        val draft: Boolean = false,
        val merged: Boolean = false,
        @SerialName("mergeable_state") val mergeableState: String? = null,
        val additions: Int = 0,
        val deletions: Int = 0,
    )

    @Serializable
    private data class GitHubSearchResult(
        val items: List<GitHubIssue> = emptyList(),
    )

    @Serializable
    private data class GitHubUser(
        val login: String = "",
    )

    @Serializable
    private data class GitHubLabel(
        val name: String = "",
    )

    @Serializable
    private data class GitHubIssueDetail(
        val title: String = "",
        val body: String? = null,
        val state: String = "open",
        val user: GitHubUser? = null,
        val labels: List<GitHubLabel> = emptyList(),
    )

    @Serializable
    private data class GitHubComment(
        val body: String? = null,
        val user: GitHubUser? = null,
    )

    @Serializable
    private data class GitHubIssue(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )
}
