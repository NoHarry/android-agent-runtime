package cc.noharry.agent.runtime.probe

/**
 * Stage catalog for envelope validation (SPEC-RPB-001).
 * P0 ships a minimal mandatory set; unknown stages still fail-open with error evidence.
 */
internal data class ProbeStageSpec(
    val stageId: String,
    val allowedAttrKeys: Set<String> = emptySet(),
    /** Higher value = drop later when attrs exceed budget. */
    val attrPriority: Map<String, Int> = emptyMap(),
)

internal class ProbeStageCatalog(
    private val stages: Map<String, ProbeStageSpec>,
) {
    fun isKnown(stageId: String): Boolean = stages.containsKey(stageId)

    fun spec(stageId: String): ProbeStageSpec? = stages[stageId]

    companion object {
        fun p0Minimal(): ProbeStageCatalog {
            val specs = p0CoreStages()
            return ProbeStageCatalog(specs.associateBy { it.stageId })
        }

        private fun p0CoreStages(): List<ProbeStageSpec> =
            listOf(
                ProbeStageSpec("run.start"),
                ProbeStageSpec("run.finish"),
                ProbeStageSpec(
                    "request.config",
                    allowedAttrKeys = setOf("controlFingerprint", "configFingerprint"),
                ),
                ProbeStageSpec("session.isolate", allowedAttrKeys = setOf("session", "memory", "cache")),
                ProbeStageSpec("model.round.start", allowedAttrKeys = setOf("roundIndex")),
                ProbeStageSpec("model.round.end", allowedAttrKeys = setOf("roundIndex", "status")),
                ProbeStageSpec("model.usage", allowedAttrKeys = setOf("inputTokens", "outputTokens")),
                ProbeStageSpec(
                    "tool.prepare",
                    allowedAttrKeys = setOf("toolName", "callId", "source", "roundIndex"),
                ),
                ProbeStageSpec(
                    "tool.authorize",
                    allowedAttrKeys = setOf("toolName", "callId", "decision", "source", "roundIndex"),
                ),
                ProbeStageSpec(
                    "tool.execute.start",
                    allowedAttrKeys =
                        setOf("toolName", "callId", "source", "roundIndex", "args", "argsDigest"),
                ),
                ProbeStageSpec(
                    "tool.execute.end",
                    allowedAttrKeys =
                        setOf("toolName", "callId", "ok", "code", "source", "roundIndex"),
                ),
                ProbeStageSpec("tool.batch.plan", allowedAttrKeys = setOf("width", "roundIndex")),
                ProbeStageSpec("search.hosted", allowedAttrKeys = setOf("path")),
                ProbeStageSpec("network.fetch", allowedAttrKeys = setOf("host")),
                ProbeStageSpec("empty_completion", allowedAttrKeys = setOf("reason")),
                ProbeStageSpec(
                    "run.terminal",
                    allowedAttrKeys = setOf("status", "incompleteReason"),
                ),
                ProbeStageSpec("case.bound", allowedAttrKeys = setOf("casePackId")),
                ProbeStageSpec("probe.error", allowedAttrKeys = setOf("code", "message", "stageId")),
                ProbeStageSpec(
                    "probe.completeness",
                    allowedAttrKeys = setOf("complete", "enqueued", "written"),
                ),
            )

    }
}
