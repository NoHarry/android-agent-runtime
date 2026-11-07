package cc.noharry.agent.runtime

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi

import cc.noharry.agent.kernel.core.memory.*

import cc.noharry.agent.kernel.core.loop.RuntimeMemoryMatchSummary
/** A relevance-scored lesson selected for the current request. */
internal data class RuntimeMemoryMatch(
    val lesson: RuntimeLesson,
    val relevanceScore: Float,
    val matchedDomains: Set<String>,
)

/** Bounded memory output safe for optional current-catalog prewarming. */
internal data class RuntimeMemorySelection(
    val matches: List<RuntimeMemoryMatch>,
    val capabilityQueries: List<String>,
    val maxPreactivatedTools: Int,
) {
    val lessons: List<RuntimeLesson> = matches.map { it.lesson }

    fun loopMatchSummaries(): List<RuntimeMemoryMatchSummary> =
        matches.map { RuntimeMemoryMatchSummary(it.lesson.id, it.relevanceScore, it.matchedDomains.sorted()) }
}

/**
 * Selects only explicitly prewarm-eligible lessons that are relevant to the
 * current request. Legacy and query-only lessons deliberately return no match.
 */
@ExperimentalAgentRuntimeApi
class RuntimeMemoryRetriever @JvmOverloads constructor(
    /** How many lessons reach a run and which count, also for learning them ([RuntimeMemoryPolicy]). */
    val policy: RuntimeMemoryPolicy = RuntimeMemoryPolicy(),
    /** What requests and lessons are about ([MemoryTopic]); a request on none is matched by its words alone. */
    topics: List<MemoryTopic> = MemoryTopic.DEFAULTS,
) {
    private val topics: List<MemoryTopic> = topics.toList()

    internal fun retrieve(requestText: String, lessons: List<RuntimeLesson>): RuntimeMemorySelection {
        val request = retrievalProfile(requestText, topics)
        if (request.isEmpty() || policy.maxRetrievedLessons <= 0) return emptySelection()
        val ranked = lessons.asSequence()
            .filter { it.isPrewarmEligible() }
            .mapNotNull { lesson -> relevanceMatch(request, lesson) }
            .sortedWith(memoryMatchOrder())
            .toList()
        val selected = selectDomainDiverse(ranked)
        return RuntimeMemorySelection(
            matches = selected,
            capabilityQueries = relevantCapabilityQueries(request, selected),
            maxPreactivatedTools = policy.maxPreactivatedTools.coerceAtLeast(0),
        )
    }

    private fun RuntimeLesson.isPrewarmEligible(): Boolean =
        mode == RuntimeLessonMode.PREWARM_ELIGIBLE &&
            confidence >= policy.minPrewarmConfidence &&
            evidenceRunKeys.size >= policy.minPrewarmHitCount

    private fun relevanceMatch(request: RetrievalProfile, lesson: RuntimeLesson): RuntimeMemoryMatch? {
        val lessonProfile = retrievalProfile(lessonSearchText(lesson), topics)
        val domains = request.domains.intersect(lessonProfile.domains)
        val score = relevanceScore(request, lessonProfile, domains)
        if (score < policy.minRelevanceScore) return null
        return RuntimeMemoryMatch(
            lesson = lesson,
            relevanceScore = score,
            matchedDomains = domains.mapTo(linkedSetOf()) { it.name },
        )
    }

    private fun selectDomainDiverse(ranked: List<RuntimeMemoryMatch>): List<RuntimeMemoryMatch> {
        val selected = mutableListOf<RuntimeMemoryMatch>()
        val coveredDomains = linkedSetOf<String>()
        for (match in ranked) {
            if (selected.size >= policy.maxRetrievedLessons) break
            if (match.matchedDomains.isNotEmpty() && coveredDomains.containsAll(match.matchedDomains)) continue
            selected += match
            coveredDomains += match.matchedDomains
        }
        return selected
    }

    private fun relevantCapabilityQueries(
        request: RetrievalProfile,
        selected: List<RuntimeMemoryMatch>,
    ): List<String> {
        val ranked = selected.asSequence()
            .flatMap { memoryCapabilityQueries(listOf(it.lesson)).asSequence() }
            .distinct()
            .mapNotNull { query -> query.relevanceTo(request) }
            .sortedWith(scoredCapabilityQueryOrder())
            .toList()
        val queries = mutableListOf<String>()
        val coveredDomains = linkedSetOf<MemoryTopic>()
        for (candidate in ranked) {
            if (queries.size >= policy.maxPreactivatedTools.coerceAtLeast(0)) break
            if (candidate.matchedDomains.isNotEmpty() && coveredDomains.containsAll(candidate.matchedDomains)) continue
            queries += candidate.query
            coveredDomains += candidate.matchedDomains
        }
        return queries
    }

    private fun String.relevanceTo(request: RetrievalProfile): ScoredCapabilityQuery? {
        val profile = retrievalProfile(this, topics)
        val matchedDomains = request.domains.intersect(profile.domains)
        val score = relevanceScore(request, profile, matchedDomains)
        return takeIf { score >= policy.minRelevanceScore }
            ?.let {
                ScoredCapabilityQuery(
                    query = it,
                    score = score,
                    specificity = profile.tokens.size,
                    matchedDomains = matchedDomains,
                )
            }
    }

    private fun emptySelection(): RuntimeMemorySelection = RuntimeMemorySelection(
        matches = emptyList(),
        capabilityQueries = emptyList(),
        maxPreactivatedTools = policy.maxPreactivatedTools.coerceAtLeast(0),
    )
}

private data class ScoredCapabilityQuery(
    val query: String,
    val score: Float,
    val specificity: Int,
    val matchedDomains: Set<MemoryTopic>,
)

private fun scoredCapabilityQueryOrder(): Comparator<ScoredCapabilityQuery> =
    compareByDescending<ScoredCapabilityQuery> { it.score }
        .thenByDescending { it.specificity }

private data class RetrievalProfile(
    val tokens: Set<String>,
    val domains: Set<MemoryTopic>,
) {
    fun isEmpty(): Boolean = tokens.isEmpty() && domains.isEmpty()
}

/**
 * A topic requests and lessons are matched on: any of its strong terms in a text, or two of its weak terms, puts the
 * text on it, unless the term only appears inside one of its excluded phrases (「路线图」 is no route). Terms are read as
 * the text is (lower case, punctuation as spaces: "Wi-Fi" is "wi fi"); a term of ASCII letters and digits matches a whole
 * word, any other term anywhere in the text. [name] is how matches name the topic, in lower case.
 */
@ExperimentalAgentRuntimeApi
class MemoryTopic(
    name: String,
    strongTerms: Set<String>,
    weakTerms: Set<String> = emptySet(),
    excludedPhrases: Set<String> = emptySet(),
) {
    val name: String = name.trim().lowercase()
    val strongTerms: Set<String> = strongTerms.normalizedTerms()
    val weakTerms: Set<String> = weakTerms.normalizedTerms()
    val excludedPhrases: Set<String> = excludedPhrases.normalizedTerms()

    init {
        require(this.name.isNotEmpty()) { "A memory topic needs a name" }
    }

    internal fun matches(normalized: String, tokens: Set<String>): Boolean {
        if (strongTerms.any { normalized.containsDomainTerm(it, tokens, excludedPhrases) }) {
            return true
        }
        val weakMatches = weakTerms.count { normalized.containsDomainTerm(it, tokens, excludedPhrases) }
        return weakMatches >= MIN_WEAK_DOMAIN_TERM_MATCHES
    }

    companion object {
        /** No product-specific topics are enabled by default; hosts supply their own vocabulary. */
        val DEFAULTS: List<MemoryTopic> = emptyList()
    }
}

private fun retrievalProfile(value: String, topics: List<MemoryTopic>): RetrievalProfile {
    val normalized = normalizeRetrievalText(value)
    val tokens = RETRIEVAL_TOKEN_PATTERN.findAll(normalized)
        .map { it.value }
        .filterNot { it in RETRIEVAL_STOP_WORDS }
        .toSet()
    val domains = topics.filterTo(linkedSetOf()) { topic -> topic.matches(normalized, tokens) }
    return RetrievalProfile(tokens = tokens, domains = domains)
}

/** What a lesson did, in its capability words: its scene is the host's label for every request, not what this one asked. */
private fun lessonSearchText(lesson: RuntimeLesson): String = memoryCapabilityQueries(listOf(lesson)).joinToString(" ")

private fun relevanceScore(
    request: RetrievalProfile,
    lesson: RetrievalProfile,
    matchedDomains: Set<MemoryTopic>,
): Float {
    val lexical = lexicalOverlap(request.tokens, lesson.tokens)
    // A request outside the known topics: the words alone decide, and must mostly agree.
    if (request.domains.isEmpty()) {
        val shared = (request.tokens intersect lesson.tokens) - GENERIC_WORDS
        return if (lesson.domains.isEmpty() && shared.size >= MIN_SHARED_WORDS_WITHOUT_TOPIC) lexical else 0f
    }
    if (matchedDomains.isEmpty() || lesson.domains.isEmpty()) return 0f
    val coverage = matchedDomains.size.toFloat() / request.domains.size
    val precision = matchedDomains.size.toFloat() / lesson.domains.size
    return DOMAIN_COVERAGE_WEIGHT * coverage + DOMAIN_PRECISION_WEIGHT * precision + LEXICAL_WEIGHT * lexical
}

private fun lexicalOverlap(first: Set<String>, second: Set<String>): Float {
    if (first.isEmpty() || second.isEmpty()) return 0f
    val shared = first.intersect(second).size
    return shared.toFloat() / minOf(first.size, second.size)
}

private fun String.containsDomainTerm(
    term: String,
    tokens: Set<String>,
    excludedPhrases: Set<String>,
): Boolean =
    if (term.all { it.isLetterOrDigit() && it.code < ASCII_LIMIT }) {
        term in tokens
    } else {
        contains(term) && excludedPhrases.none { phrase -> phrase.contains(term) && contains(phrase) }
    }

private fun Set<String>.normalizedTerms(): Set<String> =
    mapNotNullTo(linkedSetOf()) { term -> normalizeRetrievalText(term).takeIf(String::isNotEmpty) }

private fun normalizeRetrievalText(value: String): String =
    CAMEL_CASE_RETRIEVAL_BOUNDARY.replace(value, "$1 $2")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun memoryMatchOrder(): Comparator<RuntimeMemoryMatch> =
    compareByDescending<RuntimeMemoryMatch> { it.relevanceScore }
        .thenByDescending { it.lesson.confidence }
        .thenByDescending { it.lesson.hitCount }
        .thenByDescending { it.lesson.lastValidatedAtEpochMillis }

/** A run of Han characters, or a word in any other script (the text holds letters, digits and spaces only by now). */
private val RETRIEVAL_TOKEN_PATTERN = Regex("""\p{IsHan}+|[^\p{IsHan}\s]+""")

/** Words too common in tool names and requests to tie a request to a lesson without a topic. */
private val GENERIC_WORDS = setOf(
    "turn", "on", "off", "get", "open", "close", "list", "show", "my", "me", "is", "it", "in", "at", "what", "how",
    "please", "can", "you", "i", "new", "all", "info", "data", "status", "check", "car", "app", "do", "make",
)
private val CAMEL_CASE_RETRIEVAL_BOUNDARY = Regex("""([a-z0-9])([A-Z])""")
private val RETRIEVAL_STOP_WORDS = setOf(
    "a", "an", "and", "capability", "efficient", "for", "hints", "observed", "of", "plan", "search", "set", "the", "to",
)
private const val DOMAIN_COVERAGE_WEIGHT = 0.5f
private const val DOMAIN_PRECISION_WEIGHT = 0.3f
private const val LEXICAL_WEIGHT = 0.2f
private const val ASCII_LIMIT = 128
private const val MIN_WEAK_DOMAIN_TERM_MATCHES = 2
private const val MIN_SHARED_WORDS_WITHOUT_TOPIC = 2
