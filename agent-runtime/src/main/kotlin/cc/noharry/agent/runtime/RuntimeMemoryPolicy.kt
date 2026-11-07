package cc.noharry.agent.runtime

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi

import cc.noharry.agent.kernel.core.memory.*
/** Runtime-owned guardrails for memory retrieval and candidate promotion. */
@ExperimentalAgentRuntimeApi
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
internal enum class RuntimeLessonMode {
    QUERY_ONLY,
    PREWARM_ELIGIBLE,
}

internal const val DEFAULT_LESSON_MATCH_JACCARD_THRESHOLD = 0.8f
