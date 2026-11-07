package cc.noharry.agent.spi

/** Exact run input and current provider catalog for advisory prefetch. */
data class ToolPrefetchRequest(
    val input: String,
    val tools: List<ToolDescriptor>,
    val context: ToolCallContext,
    val maxTools: Int,
)
