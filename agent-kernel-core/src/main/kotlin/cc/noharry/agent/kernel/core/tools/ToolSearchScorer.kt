package cc.noharry.agent.kernel.core.tools
data class McpToolSearchHit(
    val tool: McpToolRef,
    val score: Double,
)

fun rankMcpTools(
    tools: List<McpToolRef>,
    query: String,
    maxResults: Int,
): List<McpToolSearchHit> {
    val queryTokens = tokenizeSearchQuery(query, characters = false)
    if (queryTokens.isEmpty()) return emptyList()
    val documents = tools.map { tokenizeSearchQuery(it.originalToolName + " " + it.descriptor.description, characters = true) }
    // A term in many tools (导航 across the map's tools) tells them apart less than one in few (空调).
    val documentFrequency = documents.flatMap { it.toSet() }.groupingBy { it }.eachCount()
    val averageLength = documents.map { it.size }.average().takeIf { it > 0.0 } ?: 1.0
    val normalizedQuery = query.trim().replace('.', '_')
    return tools.mapIndexedNotNull { index, tool ->
        val score = bm25Score(queryTokens, documents[index], tools.size, documentFrequency, averageLength)
        if (score > 0.0) McpToolSearchHit(tool, score) else null
    }.sortedWith(
        compareByDescending<McpToolSearchHit> {
            normalizedQuery.equals(it.tool.originalToolName, ignoreCase = true) ||
                normalizedQuery.equals(it.tool.qualifiedName, ignoreCase = true)
        }.thenByDescending { it.score },
    ).take(maxResults)
}

/**
 * The share of [query]'s terms found in [tool]'s name and description, from 0 to 1. Unlike a search score it does not
 * depend on the rest of the catalog, so it can serve as a fixed bar.
 */
fun queryCoverage(tool: McpToolRef, query: String): Double {
    val terms = tokenizeSearchQuery(query, characters = false).toSet()
    if (terms.isEmpty()) return 0.0
    val document = tokenizeSearchQuery(tool.originalToolName + " " + tool.descriptor.description, characters = true).toSet()
    return terms.count { it in document }.toDouble() / terms.size
}

/**
 * Tokenizes a search query or document for BM25 scoring.
 *
 * - Text in a script whose words run together or carry their particles (Chinese, Japanese, Korean, Thai and the like)
 *   gives pairs of adjacent characters, which match words (空调, 조명 in 조명을). A tool's text also gives every single
 *   character, so a one-character query (灯) still finds it inside a longer word; a query gives single characters only
 *   for a character standing alone, since 点 in 我有点冷 would otherwise find every 途经点.
 * - English identifiers are split on CamelCase boundaries AND underscores so
 *   that `PlaySearchMusic` and `Play_Search_Music` both yield tokens
 *   `play`, `search`, `music`.
 */
private fun tokenizeSearchQuery(text: String, characters: Boolean): List<String> {
    val tokens = mutableListOf<String>()
    val chars = text.toCharArray()
    var i = 0
    while (i < chars.size) {
        val ch = chars[i]
        if (ch.isPairedScript()) {
            val pairs = i + 1 < chars.size && chars[i + 1].isPairedScript()
            val alone = !pairs && (i == 0 || !chars[i - 1].isPairedScript())
            if (characters || alone) tokens.add(ch.toString())
            if (pairs) tokens.add(String(chars, i, 2))
            i++
        } else if (ch.isLetterOrDigit() || ch == '_') {
            val start = i
            while (i < chars.size && !chars[i].isPairedScript() &&
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

/**
 * A character of a script read in pairs, by its Unicode block (Character.UnicodeScript is not on Android before API 24),
 * marks and the long vowel ー included so they stay inside their word.
 */
private fun Char.isPairedScript(): Boolean = when (code) {
    // Sentence and section marks of these scripts end a word like spaces do.
    in PAIRED_SCRIPT_PUNCTUATION -> false
    in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF, 0x3005, 0x3006 -> true // Han, 々 〆
    in 0x3040..0x309F, in 0x30A1..0x30FA, in 0x30FC..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9F -> true // kana, not ゠ ・
    in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> true // Hangul
    in 0x0E00..0x0EFF, in 0x1780..0x17FF, in 0x1000..0x109F -> true // Thai, Lao, Khmer, Myanmar
    else -> false
}

private val PAIRED_SCRIPT_PUNCTUATION = setOf(0x0E4F, 0x0E5A, 0x0E5B, 0x104A, 0x104B, 0x104C, 0x104D, 0x104E, 0x104F) +
    (0x17D4..0x17DA)

private fun splitCamelCase(word: String): List<String> {
    if (word.isEmpty()) return emptyList()
    return word.replace(ACRONYM_BOUNDARY, "$1 $2").replace(WORD_BOUNDARY, "$1 $2").split(' ')
}

/** Okapi BM25: each term weighs by how few tools have it, against the catalog's average description length. */
private fun bm25Score(
    queryTokens: List<String>,
    docTokens: List<String>,
    totalDocs: Int,
    documentFrequency: Map<String, Int>,
    avgDocLength: Double,
): Double {
    if (queryTokens.isEmpty() || docTokens.isEmpty()) return 0.0
    val docFreqs = docTokens.groupingBy { it }.eachCount()
    val docLength = docTokens.size.toDouble()
    val k1 = 1.5
    // A light length penalty: the tools that matter most tend to have the longest descriptions (start_navigation's runs to
    // several sentences), and the usual 0.75 ranked a one-line stop_navigation above it for 「导航去公司」.
    val b = 0.3
    var score = 0.0
    for (queryToken in queryTokens.toSet()) {
        val tf = docFreqs[queryToken]?.toDouble() ?: continue
        val df = (documentFrequency[queryToken] ?: 1).toDouble()
        val idf = kotlin.math.ln(1.0 + (totalDocs.toDouble() - df + 0.5) / (df + 0.5))
        val denom = tf + k1 * (1.0 - b + b * docLength / avgDocLength)
        score += idf * (tf * (k1 + 1.0) / denom)
    }
    return score
}

private val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
private val WORD_BOUNDARY = Regex("([a-z0-9])([A-Z])")
