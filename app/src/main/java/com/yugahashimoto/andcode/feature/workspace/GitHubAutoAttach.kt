package com.yugahashimoto.andcode.feature.workspace

/** An issue GitHub's search found for a repository. */
data class GitHubReference(
    val type: String,
    val number: Int,
    val title: String,
    val url: String,
)
