package com.yugahashimoto.andcode.feature.browser

// Broad on purpose: the host check is done by isLoopbackPreviewUrl, so this only has to find
// candidate URLs and stop at punctuation that usually follows a link in prose.
private val URL_CANDIDATE_REGEX = Regex("""https?://[^\s<>"'`]+""")
private const val URL_TRAILING_PUNCTUATION = ".,;:!?)]}'\"`"

/** Loopback http(s) URLs mentioned in [text], newest-first callers decide order, order kept. */
fun loopbackUrlsIn(text: String): List<String> =
    URL_CANDIDATE_REGEX
        .findAll(text)
        .map { match -> trimProse(match.value) }
        .filter(::isLoopbackPreviewUrl)
        .distinct()
        .toList()

/**
 * [candidate] without the prose punctuation that follows a link: a closing `]` or `)` stays when the
 * URL opened it (`http://[::1]`, `/item(1)`), and goes when it closes the sentence around the link.
 */
private fun trimProse(candidate: String): String {
    var url = candidate
    while (url.isNotEmpty() && url.last() in URL_TRAILING_PUNCTUATION) {
        val last = url.last()
        val opener = BRACKET_PAIRS[last]
        if (opener != null && url.count { it == opener } >= url.count { it == last }) break
        url = url.dropLast(1)
    }
    return url
}

private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')
