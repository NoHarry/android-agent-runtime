package cc.noharry.agent.kernel.api

/** Exact run input and current provider catalog for advisory prefetch. */
@ExperimentalAgentRuntimeApi
data class ToolPrefetchRequest(
    val input: String,
    val tools: List<ToolDescriptor>,
    val context: ToolCallContext,
    val maxTools: Int,
)
