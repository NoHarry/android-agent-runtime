package cc.noharry.agent.kernel.core

/** Converts queryable lessons into bounded current-catalog prewarm queries. */
fun memoryCapabilityQueries(lessons: List<RuntimeLesson>): List<String> =
    lessons.flatMap { capabilityQueries(it.content) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_MEMORY_QUERIES)

fun efficientCapabilityLesson(
    attemptedTools: List<String>,
    excludedTools: Collection<String> = emptyList(),
): String? {
    val backendSteps = attemptedTools
        .filterNot { it in NATIVE_MEMORY_TOOL_NAMES }
        .filterNot { it in excludedTools }
        .map(::identifierWords)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .take(MAX_CAPABILITY_STEPS)
    if (backendSteps.isEmpty()) return null
    return "Observed capability set: ${backendSteps.joinToString("; ")}"
}

data class RuntimeMemoryLessonKey(
    val capabilityQueries: Set<String>,
    val fallback: String,
) {
    val hasCapabilities: Boolean = capabilityQueries.isNotEmpty()
}

fun runtimeMemoryLessonKey(content: String): RuntimeMemoryLessonKey {
    val queries = capabilityQueries(content)
        .map(::normalizeCapabilityQuery)
        .filter { it.isNotBlank() }
        .toSet()
    return RuntimeMemoryLessonKey(
        capabilityQueries = queries,
        fallback = normalizeCapabilityQuery(sanitizeHint(content)),
    )
}

fun runtimeMemoryLessonsMatch(first: String, second: String): Boolean {
    val firstKey = runtimeMemoryLessonKey(first)
    val secondKey = runtimeMemoryLessonKey(second)
    if (firstKey.hasCapabilities && secondKey.hasCapabilities) {
        return capabilityJaccard(firstKey.capabilityQueries, secondKey.capabilityQueries) >=
            DEFAULT_LESSON_MATCH_JACCARD_THRESHOLD
    }
    return firstKey.fallback.isNotBlank() && firstKey.fallback == secondKey.fallback
}

fun mergeRuntimeMemoryContent(first: String, second: String): String {
    val firstKey = runtimeMemoryLessonKey(first)
    val secondKey = runtimeMemoryLessonKey(second)
    if (!firstKey.hasCapabilities || !secondKey.hasCapabilities) {
        return first.ifBlank { second }
    }
    if (firstKey.capabilityQueries == secondKey.capabilityQueries) return first
    val mergedQueries = (firstKey.capabilityQueries + secondKey.capabilityQueries)
        .sorted()
        .take(MAX_CAPABILITY_STEPS)
    return "Capability search hints: ${mergedQueries.joinToString(", ")}"
}

private fun capabilityQueries(content: String): List<String> {
    val explicit = explicitCapabilityQueries(content)
    if (explicit.isNotEmpty()) return explicit
    if (content.startsWith(EFFICIENT_PATH_PREFIX, ignoreCase = true)) {
        return efficientPathQueries(content)
    }
    if (content.startsWith(EFFICIENT_CAPABILITY_PREFIX, ignoreCase = true)) {
        return splitQueries(content.substringAfter(":"))
    }
    if (content.startsWith(OBSERVED_CAPABILITY_PREFIX, ignoreCase = true)) {
        return splitQueries(content.substringAfter(":"))
    }
    return boundedHint(content)?.let(::listOf).orEmpty()
}

private fun capabilityJaccard(first: Set<String>, second: Set<String>): Float {
    if (first.isEmpty() || second.isEmpty()) return 0f
    val unionSize = (first + second).size
    return first.intersect(second).size.toFloat() / unionSize
}

private fun explicitCapabilityQueries(content: String): List<String> {
    val lower = content.lowercase()
    val marker = EXPLICIT_HINT_MARKERS.firstOrNull { lower.contains(it) } ?: return emptyList()
    return splitQueries(content.substring(lower.indexOf(marker) + marker.length))
}

private fun efficientPathQueries(content: String): List<String> =
    content.substringAfter(":")
        .split("->")
        .map(::identifierWords)
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_CAPABILITY_STEPS)

private fun splitQueries(value: String): List<String> =
    value.split(',', ';')
        .mapNotNull(::boundedHint)
        .take(MAX_CAPABILITY_STEPS)

private fun normalizeCapabilityQuery(value: String): String =
    sanitizeHint(value)
        .lowercase()
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun boundedHint(value: String): String? {
    val sanitized = sanitizeHint(value)
    if (sanitized.isBlank()) return null
    return if (sanitized.length <= MAX_MEMORY_HINT_CHARS) {
        sanitized
    } else {
        sanitized.take(MAX_MEMORY_HINT_CHARS).trimEnd() + "..."
    }
}

private fun sanitizeHint(value: String): String =
    value.replace(TOOL_IDENTIFIER_PATTERN) { match -> identifierWords(match.value) }
        .replace(Regex("""\s+"""), " ")
        .trim(' ', ':', '-', ';')

private fun identifierWords(value: String): String {
    val stripped = value.substringAfterLast("__")
        .removePrefix("cap_")
        .removePrefix("command_")
        .replace('_', ' ')
    return CAMEL_CASE_BOUNDARY.replace(stripped, "$1 $2")
        .lowercase()
        .replace(Regex("""[^a-z0-9\u4E00-\u9FFF]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private val NATIVE_MEMORY_TOOL_NAMES = setOf(
    "finish",
    "read_file",
    "list_dir",
    "grep",
    "search_tools",
    "update_todo",
    "write_file",
    "edit_file",
)
private val EXPLICIT_HINT_MARKERS = listOf("capability search hints:", "capability search:")
private val TOOL_IDENTIFIER_PATTERN = Regex("""\b(?:mcp__[A-Za-z0-9_]+__)?(?:cap_|command_)[A-Za-z0-9_]+\b""")
private val CAMEL_CASE_BOUNDARY = Regex("""([a-z0-9])([A-Z])""")
private const val EFFICIENT_PATH_PREFIX = "Efficient path:"
private const val EFFICIENT_CAPABILITY_PREFIX = "Efficient capability search plan:"
private const val OBSERVED_CAPABILITY_PREFIX = "Observed capability set:"
private const val MAX_MEMORY_QUERIES = 8
private const val MAX_CAPABILITY_STEPS = 8
private const val MAX_MEMORY_HINT_CHARS = 240
