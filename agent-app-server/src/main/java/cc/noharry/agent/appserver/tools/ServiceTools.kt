package cc.noharry.agent.appserver.tools

import cc.noharry.agent.appserver.runtime.AppHost

/**
 * A tool of a service the agent called (a map, a shop, the host's devices): any MCP-style tool (`mcp__<server>__<tool>`)
 * but those of a running plugin's tool server that says it is no service (cards and options, operating the screen) or
 * is the main agent's alone (the plan tool, those that hand a task to an agent of the user's;
 * [cc.noharry.agent.appserver.server.PluginContext.ownToolServer]). The memory, page and media tools count as services.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun isServiceTool(toolName: String): Boolean {
    if (!toolName.startsWith(MCP_PREFIX)) return false
    return AppHost.additions.isService(toolName.removePrefix(MCP_PREFIX).substringBefore("__"))
}

private const val MCP_PREFIX = "mcp__"
