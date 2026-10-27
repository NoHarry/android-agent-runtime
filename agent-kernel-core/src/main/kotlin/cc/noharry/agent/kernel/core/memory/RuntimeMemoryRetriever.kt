package cc.noharry.agent.kernel.core.memory

import cc.noharry.agent.kernel.core.loop.RuntimeMemoryMatchSummary
/** A relevance-scored lesson selected for the current request. */
data class RuntimeMemoryMatch(
    val lesson: RuntimeLesson,
    val relevanceScore: Float,
    val matchedDomains: Set<String>,
)

/** Bounded memory output safe for optional current-catalog prewarming. */
data class RuntimeMemorySelection(
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
class RuntimeMemoryRetriever(
    private val policy: RuntimeMemoryPolicy = RuntimeMemoryPolicy(),
) {
    fun retrieve(requestText: String, lessons: List<RuntimeLesson>): RuntimeMemorySelection {
        val request = retrievalProfile(requestText)
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
        val lessonProfile = retrievalProfile(lessonSearchText(lesson))
        val domains = request.domains.intersect(lessonProfile.domains)
        val score = relevanceScore(request, lessonProfile, domains)
        if (score < policy.minRelevanceScore) return null
        return RuntimeMemoryMatch(
            lesson = lesson,
            relevanceScore = score,
            matchedDomains = domains.mapTo(linkedSetOf()) { it.name.lowercase() },
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
        val coveredDomains = linkedSetOf<MemoryDomain>()
        for (candidate in ranked) {
            if (queries.size >= policy.maxPreactivatedTools.coerceAtLeast(0)) break
            if (candidate.matchedDomains.isNotEmpty() && coveredDomains.containsAll(candidate.matchedDomains)) continue
            queries += candidate.query
            coveredDomains += candidate.matchedDomains
        }
        return queries
    }

    private fun String.relevanceTo(request: RetrievalProfile): ScoredCapabilityQuery? {
        val profile = retrievalProfile(this)
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
    val matchedDomains: Set<MemoryDomain>,
)

private fun scoredCapabilityQueryOrder(): Comparator<ScoredCapabilityQuery> =
    compareByDescending<ScoredCapabilityQuery> { it.score }
        .thenByDescending { it.specificity }

private data class RetrievalProfile(
    val tokens: Set<String>,
    val domains: Set<MemoryDomain>,
) {
    fun isEmpty(): Boolean = tokens.isEmpty() && domains.isEmpty()
}

private enum class MemoryDomain(
    val strongTerms: Set<String>,
    val weakTerms: Set<String> = emptySet(),
    val excludedPhrases: Set<String> = emptySet(),
) {
    HVAC(
        strongTerms = setOf(
            "hvac", "ac", "climate", "cooling", "heating", "air conditioner", "空调", "内循环", "外循环",
            "有点热", "太热", "很热", "好热", "热了", "屋里热", "室内热", "制冷", "降温", "凉快",
            "有点冷", "太冷", "很冷", "好冷", "冷了", "屋里冷", "室内冷", "制热", "升温", "暖和",
        ),
        weakTerms = setOf("temperature", "hot", "cold", "fan speed", "温度", "风量", "风速"),
        excludedPhrases = setOf("冷笑话", "温度计", "温度传感器", "风速仪"),
    ),
    NAVIGATION(
        strongTerms = setOf(
            "navigation", "navigate", "navi", "destination", "poi", "map", "导航", "目的地", "地图",
        ),
        weakTerms = setOf("route", "highway", "toll", "路线", "高速", "收费"),
        excludedPhrases = setOf("技术路线", "产品路线", "路线图", "导航栏", "高速缓存", "收费标准"),
    ),
    MEDIA(
        strongTerms = setOf("music", "audio", "song", "playlist", "volume", "音乐", "歌曲", "音量"),
        weakTerms = setOf("playback", "pause", "播放", "暂停"),
    ),
    WEB(
        strongTerms = setOf(
            "browser", "web", "url", "http", "fetch", "网页", "浏览器", "网址", "联网", "上网", "访问网页", "打开网页",
        ),
    ),
    DISPLAY(strongTerms = setOf("brightness", "screen", "display", "亮度", "屏幕")),
    PHONE(
        strongTerms = setOf("phone", "电话", "通话", "拨号"),
        weakTerms = setOf("call", "contact", "联系人"),
    ),
    ;

    fun matches(normalized: String, tokens: Set<String>): Boolean {
        if (strongTerms.any { normalized.containsDomainTerm(it, tokens, excludedPhrases) }) {
            return true
        }
        val weakMatches = weakTerms.count { normalized.containsDomainTerm(it, tokens, excludedPhrases) }
        return weakMatches >= MIN_WEAK_DOMAIN_TERM_MATCHES
    }
}

private fun retrievalProfile(value: String): RetrievalProfile {
    val normalized = normalizeRetrievalText(value)
    val tokens = RETRIEVAL_TOKEN_PATTERN.findAll(normalized)
        .map { it.value }
        .filterNot { it in RETRIEVAL_STOP_WORDS }
        .toSet()
    val domains = MemoryDomain.entries
        .filterTo(linkedSetOf()) { domain -> domain.matches(normalized, tokens) }
    return RetrievalProfile(tokens = tokens, domains = domains)
}

private fun lessonSearchText(lesson: RuntimeLesson): String = buildString {
    append(memoryCapabilityQueries(listOf(lesson)).joinToString(" "))
    lesson.scene?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
}

private fun relevanceScore(
    request: RetrievalProfile,
    lesson: RetrievalProfile,
    matchedDomains: Set<MemoryDomain>,
): Float {
    val lexical = lexicalOverlap(request.tokens, lesson.tokens)
    if (request.domains.isEmpty()) return 0f
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

private fun normalizeRetrievalText(value: String): String =
    CAMEL_CASE_RETRIEVAL_BOUNDARY.replace(value, "$1 $2")
        .lowercase()
        .replace(Regex("""[^a-z0-9\u4E00-\u9FFF]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun memoryMatchOrder(): Comparator<RuntimeMemoryMatch> =
    compareByDescending<RuntimeMemoryMatch> { it.relevanceScore }
        .thenByDescending { it.lesson.confidence }
        .thenByDescending { it.lesson.hitCount }
        .thenByDescending { it.lesson.lastValidatedAtEpochMillis }

private val RETRIEVAL_TOKEN_PATTERN = Regex("""[a-z0-9]+|[\u4E00-\u9FFF]+""")
private val CAMEL_CASE_RETRIEVAL_BOUNDARY = Regex("""([a-z0-9])([A-Z])""")
private val RETRIEVAL_STOP_WORDS = setOf(
    "a", "an", "and", "capability", "efficient", "for", "hints", "observed", "of", "plan", "search", "set", "the", "to",
)
private const val DOMAIN_COVERAGE_WEIGHT = 0.5f
private const val DOMAIN_PRECISION_WEIGHT = 0.3f
private const val LEXICAL_WEIGHT = 0.2f
private const val ASCII_LIMIT = 128
private const val MIN_WEAK_DOMAIN_TERM_MATCHES = 2
