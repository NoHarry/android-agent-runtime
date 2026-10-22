package cc.noharry.agent.kernel.core

data class McpToolSearchHit(
    val tool: McpToolRef,
    val score: Double,
)

fun rankMcpTools(
    tools: List<McpToolRef>,
    query: String,
    maxResults: Int,
): List<McpToolSearchHit> {
    val queryTokens = tokenizeSearchQuery(query)
    if (queryTokens.isEmpty()) return emptyList()
    val documents = tools.map { tokenizeSearchQuery(it.originalToolName + " " + it.descriptor.description) }
    val normalizedQuery = query.trim().replace('.', '_')
    return tools.mapIndexedNotNull { index, tool ->
        val score = bm25Score(queryTokens, documents[index], tools.size)
        if (score > 0.0) McpToolSearchHit(tool, score) else null
    }.sortedWith(
        compareByDescending<McpToolSearchHit> {
            normalizedQuery.equals(it.tool.originalToolName, ignoreCase = true) ||
                normalizedQuery.equals(it.tool.qualifiedName, ignoreCase = true)
        }.thenByDescending { it.score },
    ).take(maxResults)
}

/**
 * Tokenizes a search query or document for BM25 scoring.
 *
 * - Chinese text is split into character bigrams for keyword overlap.
 * - English identifiers are split on CamelCase boundaries AND underscores so
 *   that `PlaySearchMusic` and `Play_Search_Music` both yield tokens
 *   `play`, `search`, `music`.
 */
private fun tokenizeSearchQuery(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val chars = text.toCharArray()
    var i = 0
    while (i < chars.size) {
        val ch = chars[i]
        if (ch.code in 0x4E00..0x9FFF) {
            if (i + 1 < chars.size && chars[i + 1].code in 0x4E00..0x9FFF) {
                tokens.add(String(chars, i, 2))
            } else if (i == 0 || chars[i - 1].code !in 0x4E00..0x9FFF) {
                tokens.add(ch.toString())
            }
            i++
        } else if (ch.isLetterOrDigit() || ch == '_') {
            val start = i
            while (i < chars.size && chars[i].code !in 0x4E00..0x9FFF &&
                (chars[i].isLetterOrDigit() || chars[i] == '_')) i++
            val segment = String(chars, start, i - start)
            val words = segment.split('_').flatMap { splitCamelCase(it) }
            for (word in words) {
                val lower = word.lowercase()
                if (lower.length >= 2) tokens.add(lower)
            }
        } else {
            i++
        }
    }
    return tokens
}

private fun splitCamelCase(word: String): List<String> {
    if (word.isEmpty()) return emptyList()
    return word.replace(ACRONYM_BOUNDARY, "$1 $2").replace(WORD_BOUNDARY, "$1 $2").split(' ')
}

private fun bm25Score(
    queryTokens: List<String>,
    docTokens: List<String>,
    totalDocs: Int,
): Double {
    if (queryTokens.isEmpty() || docTokens.isEmpty()) return 0.0
    val docFreqs = docTokens.groupingBy { it }.eachCount()
    val docLength = docTokens.size.toDouble()
    // Keep the established score scale used by memory-prewarm thresholds.
    val avgDocLength = 15.0
    val k1 = 1.5
    val b = 0.75
    var score = 0.0
    for (queryToken in queryTokens.toSet()) {
        val tf = docFreqs[queryToken]?.toDouble() ?: continue
        val idf = kotlin.math.ln(1.0 + (totalDocs.toDouble() - 1.0 + 0.5) / (1.0 + 0.5))
        val denom = tf + k1 * (1.0 - b + b * docLength / avgDocLength)
        score += idf * (tf * (k1 + 1.0) / denom)
    }
    return score
}

private val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
private val WORD_BOUNDARY = Regex("([a-z0-9])([A-Z])")
