@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.runtime.RuntimeInspection
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import kotlinx.coroutines.CancellationException

/**
 * The server's own rule on asking: a call of a tool that does not say it only reads waits for
 * the person's yes first, whoever brought the tool, and the approval mode answers it as it answers any gate (an ordinary
 * `RequireConfirmation`): "ask every time" asks each time, "allow confirmed domains" once per tool in a conversation,
 * "allow all" runs it unasked and "deny all" refuses it.
 *
 * - Writing the workspace (`write_file`, `edit_file`) and scripts (`exec_js`) act.
 * - A tool of a tool provider (the host's, a configured MCP server's, a plugin's) acts unless the provider describes it
 *   `READ_ONLY` (an MCP server's `readOnlyHint`); `UNKNOWN`, the default, acts. One the run cannot find among
 *   [providers] counts as acting.
 * - Nothing else asks here: reading the workspace, `search_tools`, `finish`, `ask`, `ask_async`, `update_todo`, the
 *   subagent tools (a child's own calls meet this gate in the child), `web_search`, and `web_fetch`, which keeps the
 *   runtime's rule of asking per site.
 *
 * [unasked] are the full names of tools the host says need no asking although they act
 * ([cc.noharry.agent.appserver.server.AppServerConfig.unaskedTools]). The tool servers in [decidedElsewhere] are the
 * plugins' that ask every time ([PluginToolGate]): this gate leaves them to it. [pluginActs] decides the calls of a tool
 * server a running plugin names as its own ([cc.noharry.agent.appserver.server.PluginContext.ownToolServer]) by what
 * they do, null leaving one to its `effectKind`.
 *
 * [mode] is the approval mode the run's turn answers in, when the server knows it (the app's own turns and their
 * subagents; not an ACP session, whose editor sets its own): in "allow all" a call runs without the pause an approval
 * takes, as the mode would say yes anyway. In a run nobody can be asked in (another agent's task, which asks under its
 * own policy and ends at the first approval), [outsideAnswer], the person's mode, answers instead: "allow all" lets the
 * call run, "deny all" refuses it, and otherwise it asks.
 */
internal class ActingToolGate(
    private val providers: List<ToolProvider>,
    private val unasked: Set<String>,
    private val decidedElsewhere: Set<String> = emptySet(),
    private val mode: (() -> ConfirmationMode)? = null,
    private val outsideAnswer: ConfirmationMode? = null,
    private val pluginActs: (server: String, tool: String, arguments: String) -> Boolean? = { _, _, _ -> null },
) : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        val answer = outsideAnswer ?: mode?.invoke()
        if (context.approvedConfirmation || answer == ConfirmationMode.ALLOW_ALL_SESSION) return HookDecision.Allow()
        if (call.toolName in unasked || !acts(call)) return HookDecision.Allow()
        if (outsideAnswer == ConfirmationMode.DENY_ALL) {
            return HookDecision.Deny(reason = modelText("用户设成了全部拒绝：这个操作不做。", "The user denies every action that needs a yes: this one is not done."))
        }
        return HookDecision.RequireConfirmation(
            reason = ACTING_TOOL_REASON,
            prompt = modelText("工具 ${call.toolName} 要执行操作，需要用户同意。", "The tool ${call.toolName} acts: the user decides."),
        )
    }

    private suspend fun acts(call: ToolUse): Boolean = when (call.source) {
        ToolSource.NATIVE_WRITE, ToolSource.EXEC -> true
        ToolSource.BACKEND -> backendActs(call.toolName, call.argumentsJson)
        else -> false
    }

    private suspend fun backendActs(name: String, arguments: String): Boolean {
        for (provider in providers.filter { name.startsWith(serverPrefix(it.providerId)) }) {
            val tools = try {
                provider.listTools()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return true
            }
            val tool = tools.firstOrNull { RuntimeInspection.qualifiedToolName(provider.providerId, it.name) == name } ?: continue
            if (provider.providerId in decidedElsewhere) return false
            return pluginActs(provider.providerId, tool.name, arguments)
                ?: (tool.effectKind != RuntimeEffectKind.READ_ONLY.name)
        }
        return true
    }
}

/** `mcp__<id>__`: how the runtime starts the full names of [id]'s tools. */
private fun serverPrefix(id: String): String = RuntimeInspection.qualifiedToolName(id, PROBE).removeSuffix(PROBE)

/** Why a tool that acts waits for the person, as its approval names it. */
internal const val ACTING_TOOL_REASON = "TOOL_ACTS"
private const val PROBE = "t"
