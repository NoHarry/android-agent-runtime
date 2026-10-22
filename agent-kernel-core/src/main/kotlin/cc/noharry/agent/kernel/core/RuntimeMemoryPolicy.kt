package cc.noharry.agent.kernel.core

/** Runtime-owned guardrails for memory retrieval and candidate promotion. */
data class RuntimeMemoryPolicy(
    val maxRetrievedLessons: Int = 2,
    val maxPreactivatedTools: Int = 2,
    val minRelevanceScore: Float = 0.55f,
    val minPrewarmConfidence: Float = 0.75f,
    val minPrewarmHitCount: Int = 3,
    val maxCandidateModelRounds: Int = 6,
    val maxCandidateToolCalls: Int = 8,
)

/**
 * QUERY_ONLY lessons remain inspectable through runtime context but cannot
 * influence the current tool list. PREWARM_ELIGIBLE is an explicit privilege.
 */
enum class RuntimeLessonMode {
    QUERY_ONLY,
    PREWARM_ELIGIBLE,
}

const val DEFAULT_LESSON_MATCH_JACCARD_THRESHOLD = 0.8f
