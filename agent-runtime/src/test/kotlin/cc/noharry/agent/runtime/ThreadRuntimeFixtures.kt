@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*

internal fun threadRoot(): File = Files.createTempDirectory("thread-runtime-").toFile()

internal fun threadBuilder(model: ModelClient, root: File = threadRoot()): AgentRuntimeBuilder =
    AgentRuntimeBuilder.create().model(model).storage(File(root, "storage")).workspaceRoot(File(root, "workspace"))

internal fun approvalBuilder(root: File = threadRoot(), requests: MutableList<ModelRequest> = mutableListOf()): AgentRuntimeBuilder =
    threadBuilder(backendThenFinishModel(requests), root)
        .tools(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
        .hook(confirmUntilApproved())

internal fun assertTurnEvents(events: List<ThreadEvent>) {
    assertTrue(events.first() is ThreadEvent.TurnStarted)
    assertTrue(events.last() is ThreadEvent.TurnCompleted || events.last() is ThreadEvent.TurnFailed)
    assertEquals(1, events.count { it is ThreadEvent.TurnCompleted || it is ThreadEvent.TurnFailed })
    val open = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    events.forEach { event ->
        when (event) {
            is ThreadEvent.ItemStarted -> { assertTrue(seen.add(event.item.id)); assertTrue(open.add(event.item.id)) }
            is ThreadEvent.ItemDelta -> assertTrue(open.contains(event.itemId))
            is ThreadEvent.ItemUpdated -> assertTrue(open.contains(event.item.id))
            is ThreadEvent.ItemCompleted -> assertTrue(open.remove(event.item.id))
            else -> Unit
        }
    }
    assertTrue(open.isEmpty())
}

/** Drives the real approval card in its original turn and verifies that the fixture actually reached a gate. */
internal suspend fun AgentThread.collectApproving(
    input: String,
    options: TurnOptions = TurnOptions(),
    inspect: suspend (Approval) -> Unit = {},
): List<ThreadEvent> {
    val events = mutableListOf<ThreadEvent>()
    var approvals = 0
    runStreamed(input, options).collect { event ->
        events += event
        if (event is ThreadEvent.ItemStarted && event.item is Approval) {
            approvals++
            val pending = event.item as Approval
            inspect(pending)
            assertEquals(RespondResult.ACCEPTED, pending.approve())
        }
    }
    assertTrue("Expected an approval gate", approvals > 0)
    assertTurnEvents(events)
    return events
}

internal class ThreadScriptModel(
    private val scripts: List<List<ModelEvent>>,
    val requests: MutableList<ModelRequest> = mutableListOf(),
    private val images: Boolean = true,
) : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
    override fun supportsImageInput(model: ModelRef?) = images
    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        val index = requests.size
        requests += request.copy(messages = request.messages.toList())
        return flowOf(*scripts.getOrElse(index) { finishEvents("done") }.toTypedArray())
    }
}

internal fun finishEvents(text: String): List<ModelEvent> = listOf(
    ModelEvent.ToolCall("finish", "finish", """{"response":"$text"}"""), ModelEvent.Completed,
)
