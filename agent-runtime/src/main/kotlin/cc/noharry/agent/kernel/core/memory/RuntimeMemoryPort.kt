package cc.noharry.agent.kernel.core.memory

import cc.noharry.agent.runtime.RuntimeLessonMode
import cc.noharry.agent.spi.reliability.RuntimeCandidateType

/** Core-owned lesson store seam used by the protocol loop. */
internal interface RuntimeMemoryPort {
    suspend fun lessonsFor(scene: String?): List<RuntimeLesson>
}

internal data class RuntimeLesson(
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
