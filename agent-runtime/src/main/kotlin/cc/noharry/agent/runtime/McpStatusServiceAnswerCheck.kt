package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.core.tools.isMcpBusinessResult
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ServiceAnswerCheck

/**
 * A [ServiceAnswerCheck] for MCP services that report an outcome in their own terms as
 * `structuredContent.status.name` (such as `NO_ROUTE`): a failed result carrying one is the service's answer. Not the
 * default; a host whose services answer this way sets it in [LoopSettings.serviceAnswer].
 */
@ExperimentalAgentRuntimeApi
object McpStatusServiceAnswerCheck : ServiceAnswerCheck {
    override fun isServiceAnswer(resultJson: String): Boolean = isMcpBusinessResult(resultJson)
}
