@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** A provider screenshot rides with its tool result into the next model request. */
class ToolResultImageLoopTest {
    @Test
    fun providerScreenshotReachesTheNextModelRequest() = runBlocking {
        val seen = mutableListOf<ModelRequest>()

        runtime(seen, imageInput = true).run(agentRequest()).toList()

        val toolMessage = seen[2].screenshotResult()
        assertEquals(listOf(SCREENSHOT_PATH), toolMessage.images.map { it.sourcePath })
        assertEquals(OBSERVATION_JSON, toolMessage.content)
    }

    @Test
    fun textOnlyModelGetsAPlaceholderInstead() = runBlocking {
        val seen = mutableListOf<ModelRequest>()

        runtime(seen, imageInput = false).run(agentRequest()).toList()

        val toolMessage = seen[2].screenshotResult()
        assertTrue(toolMessage.images.isEmpty())
        assertTrue(toolMessage.content.endsWith("[image omitted: this model accepts text only: $SCREENSHOT_PATH]"))
    }

    @Test
    fun aConfirmedActionsScreenshotReachesTheRequestAfterApproval() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val runtime = runtime(seen, imageInput = true, confirmation = ConfirmBackendCalls)

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>().single().pending.pendingId
        runtime.resumeConfirmation(approve(pending))

        val toolMessage = seen.last().screenshotResult()
        assertEquals(listOf(SCREENSHOT_PATH), toolMessage.images.map { it.sourcePath })
        assertEquals(OBSERVATION_JSON, toolMessage.content)
    }

    private fun runtime(seen: MutableList<ModelRequest>, imageInput: Boolean, confirmation: RuntimeHook? = null) =
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("tool-result-images-").toFile())
            .toolProvider(ScreenshotProvider())
            .imageInput(imageInput)
            .apply { if (confirmation != null) hook(confirmation) }
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaRead(TOOL_NAME), ModelEvent.Completed),
                        listOf(ModelEvent.ToolCall("tool-1", qualifiedMcpTool(TOOL_NAME), "{}"), ModelEvent.Completed),
                        listOf(ModelEvent.ToolCall("tool-2", "finish", """{"response":"seen"}"""), ModelEvent.Completed),
                    ),
                    seenRequests = seen,
                ),
            )
            .build()

    private fun ModelRequest.screenshotResult(): ModelMessage =
        messages.single { it.role == "tool" && it.toolName == qualifiedMcpTool(TOOL_NAME) }
}

/** Asks before every provider call, the way a host may gate screen actions. */
private object ConfirmBackendCalls : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (call.source != ToolSource.BACKEND || context.approvedConfirmation) {
            HookDecision.Allow()
        } else {
            HookDecision.RequireConfirmation("Screen action", "Operate the screen?")
        }
}

private class ScreenshotProvider : McpClientProvider {
    override val serverId: String = TEST_MCP_SERVER_ID
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = listOf(mcpTool(TOOL_NAME))

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
        ToolCallResult(
            success = true,
            resultJson = OBSERVATION_JSON,
            images = listOf(ModelImage(byteArrayOf(1, 2, 3), "image/jpeg", SCREENSHOT_PATH)),
        )
}

private const val TOOL_NAME = "android_observe"
private const val SCREENSHOT_PATH = "android-use/obs_1.jpg"
private const val OBSERVATION_JSON = """{"observationId":"obs_1"}"""
