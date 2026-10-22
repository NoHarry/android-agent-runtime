package cc.noharry.agent.kernel.api.mcp

import cc.noharry.agent.kernel.api.ToolProvider

/**
 * MCP transport adapter over the generic [ToolProvider] contract.
 *
 * Implementations may speak to a local fixture, HTTP MCP server, a vendor SDK, or any other transport.
 * The runtime consumes this provider through the normalized [ToolProvider] catalog and mounts it
 * under `/mcp`. This is not a parallel protocol, capability key, or second SPI.
 */
interface McpClientProvider : ToolProvider {
    /** Stable server identifier used to build model-visible qualified tool names. */
    val serverId: String

    override val providerId: String
        get() = serverId
}
