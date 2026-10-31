package com.yugahashimoto.andcode.feature.git

private val OWNER_REPO = Regex("^([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)/([A-Za-z0-9._-]+)$")

/**
 * The `https://github.com/<owner>/<repo>` page of a git remote, or null when [remote] does not point
 * at a repository on github.com.
 *
 * Accepts the forms `git clone` leaves in `remote.origin.url`: `https://github.com/o/r(.git)`,
 * `http://`, an `https://user:token@github.com/...` URL (the credentials are dropped, never shown),
 * `git@github.com:o/r(.git)` and `ssh://git@github.com(:22)/o/r(.git)`. Anything else, including
 * GitHub Enterprise hosts and paths deeper than owner/repo, is null.
 */
fun gitHubRepositoryUrl(remote: String): String? {
    val ownerRepo = gitHubPath(remote.trim())?.trimEnd('/')?.removeSuffix(".git") ?: return null
    val match = OWNER_REPO.matchEntire(ownerRepo)?.takeIf { it.groupValues[2] != "." && it.groupValues[2] != ".." }
    return match?.let { "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}" }
}

/** The path after the host of a github.com remote (`owner/repo.git`), or null for any other host. */
private fun gitHubPath(remote: String): String? {
    if (remote.startsWith("git@github.com:")) return remote.removePrefix("git@github.com:")
    val scheme = remote.substringBefore("://", missingDelimiterValue = "")
    val rest = remote.substringAfter("://")
    val host = rest.substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
    val known = scheme in setOf("https", "http", "ssh", "git") && (host == "github.com" || host == "www.github.com")
    return if (known) rest.substringAfter('/', missingDelimiterValue = "") else null
}
