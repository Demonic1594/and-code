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
    val trimmed = remote.trim()
    val path =
        when {
            trimmed.startsWith("git@github.com:") -> trimmed.removePrefix("git@github.com:")
            else -> {
                val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
                if (scheme !in setOf("https", "http", "ssh", "git")) return null
                val rest = trimmed.substringAfter("://")
                val authority = rest.substringBefore('/')
                val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
                if (host != "github.com" && host != "www.github.com") return null
                rest.substringAfter('/', missingDelimiterValue = "")
            }
        }
    val ownerRepo = path.trimEnd('/').removeSuffix(".git")
    val match = OWNER_REPO.matchEntire(ownerRepo) ?: return null
    val repo = match.groupValues[2]
    if (repo == "." || repo == "..") return null
    return "https://github.com/${match.groupValues[1]}/$repo"
}
