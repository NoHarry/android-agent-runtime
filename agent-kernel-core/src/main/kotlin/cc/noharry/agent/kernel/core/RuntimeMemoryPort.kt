package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType

/** Core-owned lesson store seam used by the protocol loop. */
interface RuntimeMemoryPort {
    suspend fun lessonsFor(scene: String?): List<RuntimeLesson>
}

data class RuntimeLesson(
    val id: String,
    val scene: String?,
    val type: RuntimeCandidateType,
    val content: String,
    val confidence: Float,
    val hitCount: Int,
    val createdAtEpochMillis: Long,
    val lastValidatedAtEpochMillis: Long,
    val lastDecayedAtEpochMillis: Long = lastValidatedAtEpochMillis,
    val mode: RuntimeLessonMode = RuntimeLessonMode.QUERY_ONLY,
    val evidenceRunKeys: Set<String> = emptySet(),
)
