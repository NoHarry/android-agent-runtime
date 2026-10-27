@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.core.loop.failed
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun backendThenFinishModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(progressiveSchemaRead("set_temperature"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("tool-1", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("tool-2", "finish", """{"response":"recovered"}"""), ModelEvent.Completed),
        ),
        seenRequests = seenRequests,
    )

internal fun backendOnlyModel(): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(progressiveSchemaRead("set_temperature"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("tool-1", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
        ),
    )

internal fun malformedBackendThenFinishModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(progressiveSchemaRead("set_temperature"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("tool-1", qualifiedMcpTool("set_temperature"), """{"value":"""), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("tool-2", "finish", """{"response":"recovered"}"""), ModelEvent.Completed),
        ),
        seenRequests = seenRequests,
    )

internal fun executePlanThenFinishModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    backendThenFinishModel(seenRequests)

internal fun writeThenFinishModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(
                ModelEvent.ToolCall(
                    "tool-1",
                    "write_file",
                    """{"path":"/memory/secret.txt","content":"blocked"}""",
                ),
                ModelEvent.Completed,
            ),
            listOf(ModelEvent.ToolCall("tool-2", "finish", """{"response":"recovered"}"""), ModelEvent.Completed),
        ),
        seenRequests = seenRequests,
    )

internal fun finishOnlyModel(): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(ModelEvent.ToolCall("tool-1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
        ),
    )

private fun progressiveSchemaRead(toolName: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall("schema-$toolName", "read_file", """{"path":"/mcp/tools/${qualifiedMcpTool(toolName)}/schema.json"}""")

internal fun approve(pendingId: String): ConfirmationDecision = ConfirmationDecision(
    pendingId = pendingId,
    requestId = "resume-1",
    choice = ConfirmationChoice.APPROVE,
)

internal fun denyTool(toolName: String): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (originalMcpToolName(call.toolName) == toolName) {
            HookDecision.Deny(reason = "Denied by test hook.")
        } else {
            HookDecision.Allow()
        }
}

internal fun confirmUntilApproved(): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (originalMcpToolName(call.toolName) != "set_temperature" || context.approvedConfirmation) {
            HookDecision.Allow()
        } else {
            HookDecision.RequireConfirmation("Needs approval", "Approve backend?")
        }
}

internal fun confirmWithPrompt(prompt: String): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (originalMcpToolName(call.toolName) != "set_temperature" || context.approvedConfirmation) {
            HookDecision.Allow()
        } else {
            HookDecision.RequireConfirmation("Needs approval", prompt)
        }
}

internal fun slowAllowHook(): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (originalMcpToolName(call.toolName) == "set_temperature") {
            delay(50)
        }
        return HookDecision.Allow()
    }
}

internal fun throwingHook(): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (originalMcpToolName(call.toolName) == "set_temperature") {
            throw IOException("hook io failed")
        }
        return HookDecision.Allow()
    }
}

internal fun cancellingHook(): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (originalMcpToolName(call.toolName) == "set_temperature") {
            throw CancellationException("cancel hook")
        }
        return HookDecision.Allow()
    }
}

internal fun lifecycleRecorder(events: MutableList<String>): RuntimeHook = object : RuntimeHook {
    override suspend fun onRunStarted(context: HookContext) {
        events += "started:${context.runId}"
    }

    override suspend fun onRunFinished(result: RuntimeResult, context: HookContext) {
        events += "finished:${context.runId}:${result.status}"
    }
}
