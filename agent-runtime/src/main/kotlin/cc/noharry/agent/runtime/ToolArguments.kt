package cc.noharry.agent.runtime

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.model.ModelEvent

/**
 * What a tool receives of a call the model wrote, for a host that asks the model to fill fields no tool takes (a note
 * for the person on each call, say). The runtime keeps the call as the model wrote it: the session's event log records
 * it so, and the history its later requests send shows it so, in this run, the next and after a restart, so the model
 * reads its own calls back unchanged and the provider's prompt cache keeps matching. The call as it runs carries the
 * arguments [forTool] returns, and that is what the tool, hooks and approvals, argument checks, events and the checks
 * that compare calls read.
 *
 * The runtime asks once for each call it goes on to run, as it takes the call up: after the event log has recorded the
 * call as written, before the call's checks, hooks, events and tool. It may ask from several runs at once: keep it
 * quick and safe to call concurrently.
 */
@ExperimentalAgentRuntimeApi
fun interface ToolArguments {
    /**
     * The arguments [call]'s tool receives. [call] is the call the runtime runs: [written], the call as the model wrote
     * it, or for a call_tool call the tool it names with the arguments the model passed it. [schemaJson] is that tool's
     * own parameter schema as the runtime has it (a tool reached through call_tool included), or null for a call of no
     * tool the runtime knows; a property it declares is the tool's input, whatever its name.
     */
    fun forTool(call: ModelEvent.ToolCall, written: ModelEvent.ToolCall, schemaJson: String?): String

    companion object {
        /** Tools receive the arguments as the model wrote them. */
        val AS_WRITTEN: ToolArguments = ToolArguments { call, _, _ -> call.argumentsJson }
    }
}
