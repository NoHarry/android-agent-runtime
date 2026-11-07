@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.mcp.McpClientProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.workspace.VirtualFileMaterializer
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import cc.noharry.agent.vfs.VfsFileSystem
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertTrue

internal fun assertCancelledToolResultClosed(
    messages: List<ModelMessage>,
    toolCallId: String,
    toolName: String,
) {
    val assistantIndex = messages.indexOfFirst {
        it.role == "assistant" && it.toolCallId == toolCallId && it.toolName == toolName
    }
    val toolIndex = messages.indexOfFirst {
        it.role == "tool" && it.toolCallId == toolCallId && it.toolName == toolName
    }

    assertTrue("assistant tool call $toolCallId should be present", assistantIndex >= 0)
    assertTrue("tool result $toolCallId should follow assistant tool call", toolIndex > assistantIndex)
    assertTrue(messages[toolIndex].content.contains(TOOL_CALL_CANCELLED_CODE))
}

internal class CancellingMcpProvider : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    val calledTools = mutableListOf<String>()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(mcpTool("set_temperature").copy(inputSchemaJson = """{"type":"object","additionalProperties":true}"""))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        calledTools += toolName
        throw CancellationException("backend cancelled")
    }
}

internal class RecoverableOnceModelClient(
    private val seenRequests: MutableList<ModelRequest>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private var calls = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        seenRequests.record(request)
        calls += 1
        if (calls == 1) {
            throw ModelClientException(
                RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED,
                recoverable = true,
                message = "transient provider failure",
            )
        }
        emit(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done after retry"}"""))
        emit(ModelEvent.Completed)
    }
}

internal class FallbackModelClient(
    private val seenRequests: MutableList<ModelRequest>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        seenRequests.record(request)
        if (request.modelRef?.modelId == "primary") {
            throw ModelClientException(
                RuntimeErrorCode.PROVIDER_RATE_LIMITED,
                recoverable = true,
                message = "primary rate limited",
            )
        }
        emit(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done on fallback"}"""))
        emit(ModelEvent.Completed)
    }
}

internal class ToolThenRecoverableFailureModelClient(
    private val seenRequests: MutableList<ModelRequest>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private var round = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        seenRequests.record(request)
        round += 1
        if (round == 1) {
            emit(setTemperatureSchemaCall())
            emit(ModelEvent.Completed)
            return@flow
        }
        emit(setTemperatureBackendCall())
        emit(ModelEvent.TextDelta("backend result observed"))
        throw ModelClientException(
            RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED,
            recoverable = true,
            message = "transport failed after backend result",
        )
    }
}

internal fun MutableList<ModelRequest>.record(request: ModelRequest) {
    this += request.copy(
        messages = request.messages.toList(),
        tools = request.tools.toList(),
    )
}

internal fun setTemperatureSchemaCall(): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        "schema-1",
        "read_file",
        """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
    )

internal fun setTemperatureBackendCall(): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        "backend-1",
        qualifiedMcpTool("set_temperature"),
        """{"degreesCelsius":22}""",
    )

internal fun finalCompletedTodoCall(): ModelEvent.ToolCall =
    ModelEvent.ToolCall("todo-final", "update_todo", """{"todos":[{"content":"调温","status":"completed"}]}""")
