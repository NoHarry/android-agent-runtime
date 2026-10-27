package cc.noharry.agent.kernel.core.memory
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.core.loop.NativeLoopSummary

/**
 * Extracts memory candidates from a terminal run summary (SPEC-CDR-005).
 *
 * Candidates are not durable lessons; they must pass the confidence gate in
 * [RuntimeMemoryStore] before becoming active (mem-005: Dream candidate is not
 * durable promotion).
 */
fun extractMemoryCandidates(
    summary: NativeLoopSummary,
    result: RuntimeResult,
    policy: RuntimeMemoryPolicy = RuntimeMemoryPolicy(),
): List<RuntimeCandidate> {
    val candidates = mutableListOf<RuntimeCandidate>()
    // Pattern 1: repeated tool failures (same failure >= 2 times)
    summary.toolRegistryFailures.groupingBy { it }.eachCount()
        .filter { it.value >= 2 }
        .forEach { (failure, count) ->
            candidates += RuntimeCandidate(
                candidateId = "cand-${result.runId}-fail-${failure.hashCode().and(0xFFFFFF)}",
                runId = result.runId,
                type = RuntimeCandidateType.TOOL_SCHEMA_FIX,
                summary = "Tool repeatedly failed (${count}x): $failure",
                confidence = if (RuntimeCompletionSummary.firstPartyPass(result.status, result.completion)) {
                    "medium"
                } else {
                    "high"
                },
                suggestedDestination = "/context/memory/tool-failures/",
            )
        }
    // Pattern 2: observed capabilities from a short, verified backend-success run.
    // Capabilities already supplied by memory are excluded to prevent circular reinforcement.
    val suppliedBackendTools = (summary.memoryHintTools + summary.sessionInheritedTools).toSet()
    val newBackendTools = summary.successfulBackendTools
        .distinct()
        .filterNot { it in suppliedBackendTools }
    if (isPromotionQualityRun(summary, result, newBackendTools, policy)) {
        efficientCapabilityLesson(newBackendTools)?.let { lesson ->
            candidates += RuntimeCandidate(
                candidateId = "cand-${result.runId}-path",
                runId = result.runId,
                type = RuntimeCandidateType.MEMORY,
                summary = lesson,
                confidence = "medium",
                suggestedDestination = PREWARM_ELIGIBLE_MEMORY_DESTINATION,
            )
        }
    }
    return candidates
}

private fun isPromotionQualityRun(
    summary: NativeLoopSummary,
    result: RuntimeResult,
    newBackendTools: List<String>,
    policy: RuntimeMemoryPolicy,
): Boolean = RuntimeCompletionSummary.firstPartyPass(result.status, result.completion) &&
    summary.modelRoundCount in 1..policy.maxCandidateModelRounds &&
    summary.attemptedTools.size <= policy.maxCandidateToolCalls &&
    summary.successfulBackendTools.isNotEmpty() &&
    summary.backendToolFailures.isEmpty() &&
    summary.toolRegistryFailures.isEmpty() &&
    newBackendTools.isNotEmpty()

fun RuntimeCandidate.lessonMode(): RuntimeLessonMode =
    if (type == RuntimeCandidateType.MEMORY && suggestedDestination == PREWARM_ELIGIBLE_MEMORY_DESTINATION) {
        RuntimeLessonMode.PREWARM_ELIGIBLE
    } else {
        RuntimeLessonMode.QUERY_ONLY
    }

const val PREWARM_ELIGIBLE_MEMORY_DESTINATION = "/context/memory/efficient-paths/"
