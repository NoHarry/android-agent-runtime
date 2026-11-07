@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.android

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import cc.noharry.agent.*
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.functionTools
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline device checks through the published Android builder and conversation API. */
@RunWith(AndroidJUnit4::class)
class ThreadControlsDeviceTest {
    @Test fun approvalContinuesTheOriginalStreamWithRealToolOutput(): Unit = runBlocking {
        withTimeout(15_000) {
            ControlDeviceStorage().use { storage ->
                val calls = AtomicInteger()
                storage.builder(DeviceControlModel(approvalRounds())).tools(controlTools(calls))
                    .hook(controlApproval()).build().use { runtime ->
                        val events = mutableListOf<ThreadEvent>()
                        runtime.startThread().runStreamed("set the test value to 7").collect { event ->
                            events += event
                            if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                                assertEquals(RespondResult.ACCEPTED, (event.item as Approval).approve())
                            }
                        }
                        assertEquals(1, calls.get())
                        assertEquals(1, events.count { it is ThreadEvent.TurnStarted })
                        val completed = events.last() as ThreadEvent.TurnCompleted
                        assertEquals("Value 7 applied.", completed.result.finalResponse)
                        val approvalEnd = events.indexOfFirst { it is ThreadEvent.ItemCompleted && it.item is Approval }
                        assertTrue(approvalEnd >= 0)
                        assertTrue(events.drop(approvalEnd + 1).any { it is ThreadEvent.ItemDelta && it.text.contains("Value 7") })
                        val tool = completed.result.items.filterIsInstance<ThreadItem.ToolCall>().single { it.tool == CONTROL_TOOL }
                        assertTrue(tool.arguments.contains("7"))
                        assertTrue(tool.output.orEmpty().contains("7"))
                        assertEquals(ItemStatus.COMPLETED, tool.status)
                    }
            }
        }
    }

    @Test fun denialDoesNotExecuteTheFunctionTool(): Unit = runBlocking {
        withTimeout(15_000) {
            ControlDeviceStorage().use { storage ->
                val calls = AtomicInteger()
                storage.builder(DeviceControlModel(approvalRounds())).tools(controlTools(calls))
                    .hook(controlApproval()).build().use { runtime ->
                        val events = mutableListOf<ThreadEvent>()
                        runtime.startThread().runStreamed("set the test value").collect { event ->
                            events += event
                            if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).deny()
                        }
                        assertEquals(0, calls.get())
                        assertEquals("CONFIRMATION_DENIED", (events.last() as ThreadEvent.TurnFailed).error.code)
                        val tool = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                            .filterIsInstance<ThreadItem.ToolCall>().single { it.tool == CONTROL_TOOL }
                        assertEquals(ItemStatus.DECLINED, tool.status)
                    }
            }
        }
    }

    @Test fun askEndsTheTurnAndExplicitAnswerReachesTheNextModelRequest(): Unit = runBlocking {
        withTimeout(15_000) {
            ControlDeviceStorage().use { storage ->
                val model = DeviceControlModel(listOf(
                    listOf(ModelEvent.ToolCall("ask-color", "ask", """{"question":"Which color?"}"""), ModelEvent.Completed),
                    finishRound("Blue selected.", "answer-finish"),
                ))
                storage.builder(model).build().use { runtime ->
                    val thread = runtime.startThread()
                    val asked = thread.run("choose a color")
                    assertEquals(TurnStatus.WAITING_FOR_ANSWER, asked.status)
                    val question = asked.pending as Question
                    val answered = thread.run(TurnInput(listOf(InputPart.Text("blue")), answering = question.id))
                    assertEquals(TurnStatus.COMPLETED, answered.status)
                    assertNotEquals(asked.turnId, answered.turnId)
                    val messages = model.requests.last().messages
                    assertTrue(messages.any { it.role == "tool" && it.toolName == "ask" && it.content.contains("waitingForUser") })
                    assertTrue(messages.last { it.role == "user" }.content.contains("Which color?"))
                    assertTrue(messages.last { it.role == "user" }.content.contains("blue"))
                    assertTrue(thread.pendingRequests().isEmpty())
                }
            }
        }
    }

    @Test fun steeringAnswersTheSelectedAsyncQuestionOnDevice(): Unit = runBlocking {
        withTimeout(15_000) {
            ControlDeviceStorage().use { storage ->
                val waiting = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val model = DeviceControlModel(asyncQuestionRounds()) { round ->
                    if (round == 1) { waiting.complete(Unit); release.await() }
                }
                storage.builder(model).build().use { runtime ->
                    val thread = runtime.startThread()
                    val collected = async { thread.runStreamed("plan a trip", TurnOptions(turnId = "device:question:42")).toList() }
                    waiting.await()
                    val questions = thread.pendingRequests().filterIsInstance<Question>()
                    assertEquals(3, questions.size)
                    assertEquals(SteerResult.ACCEPTED, thread.steer("Suzhou", questions.first().id))
                    release.complete(Unit)
                    val events = collected.await()
                    val reply = model.requests.last().messages.single { it.role == "user" && it.content.contains("Suzhou") }
                    assertTrue(reply.content.contains("Destination?"))
                    assertFalse(reply.content.contains("Budget?"))
                    val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Question>()
                    assertEquals(ItemStatus.COMPLETED, completed.single { it.id == questions.first().id }.status)
                    assertEquals(2, completed.count { it.status == ItemStatus.DECLINED })
                    assertEquals("Answer consumed.", (events.last() as ThreadEvent.TurnCompleted).result.finalResponse)
                }
            }
        }
    }

    @Test fun imageAndProviderHistorySurviveRuntimeRecreation(): Unit = runBlocking {
        withTimeout(15_000) {
            ControlDeviceStorage().use { storage ->
                val png = Base64.decode(TINY_PNG, Base64.DEFAULT)
                val original = DeviceControlModel(listOf(finishRound("A pixel.", "image-finish")))
                storage.builder(original).build().use { runtime ->
                    runtime.startThread(ThreadOptions(threadId = "image-history"))
                        .run(TurnInput.of(InputPart.Text("describe this picture"), InputPart.Image(png, "image/png")))
                    val image = original.requests.single().messages.first { it.role == "user" }.images.single()
                    assertEquals("image/png", image.mediaType)
                    assertArrayEquals(png, image.bytes)
                }
                val restored = DeviceControlModel(listOf(finishRound("The same pixel.", "restored-finish")))
                storage.builder(restored).build().use { runtime ->
                    val thread = runtime.resumeThread("image-history")
                    val input = thread.items().filterIsInstance<ThreadItem.UserMessage>().single().input
                    assertArrayEquals(png, input.filterIsInstance<InputPart.Image>().single().bytes)
                    assertEquals(TurnStatus.COMPLETED, thread.run("what did I show you?").status)
                    val history = restored.requests.single().messages
                    assertTrue(history.any { it.role == "user" && it.content == "describe this picture" })
                    assertTrue(history.any { it.role == "assistant" && it.content == "A pixel." })
                    assertArrayEquals(png, history.flatMap { it.images }.single().bytes)
                }
            }
        }
    }
}

private class ControlDeviceStorage : AutoCloseable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "controls_${UUID.randomUUID().toString().replace("-", "")}"
    fun builder(model: ModelClient): AgentRuntimeBuilder = AndroidAgentRuntime.builder(context, name).model(model)
    override fun close() {
        File(context.filesDir, "agent-runtime/runtimes/$name").deleteRecursively()
        File(context.noBackupFilesDir, "agent-runtime/runtimes/$name").deleteRecursively()
    }
}

private class DeviceControlModel(
    private val rounds: List<List<ModelEvent>>,
    private val beforeRound: suspend (Int) -> Unit = {},
) : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    val requests = mutableListOf<ModelRequest>()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val round = requests.size
        requests += request.copy(messages = request.messages.toList())
        beforeRound(round)
        checkNotNull(rounds.getOrNull(round)) { "Unexpected device model round $round" }.forEach { emit(it) }
    }
}

private fun controlTools(calls: AtomicInteger): ToolProvider = functionTools("device_control") {
    tool("set_value", "Sets the in-memory test value.") {
        calls.incrementAndGet()
        """{"ok":true,"value":7}"""
    }
}

private fun controlApproval(): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (call.toolName == CONTROL_TOOL && !context.approvedConfirmation) {
            HookDecision.RequireConfirmation("Test value change", "Apply the test value?")
        } else HookDecision.Allow()
}

private fun approvalRounds(): List<List<ModelEvent>> = listOf(
    listOf(ModelEvent.ToolCall("discover", "search_tools", """{"query":"set_value"}"""), ModelEvent.Completed),
    listOf(ModelEvent.ToolCall("set", CONTROL_TOOL, """{"value":7}"""), ModelEvent.Completed),
    listOf(ModelEvent.TextDelta("Value 7 applied.")) + finishRound("Value 7 applied.", "applied-finish"),
)

private fun asyncQuestionRounds(): List<List<ModelEvent>> = listOf(
    listOf(ModelEvent.ToolCall("questions", "ask_async", """{"questions":[{"title":"Destination?"},{"title":"Dates?"},{"title":"Budget?"}]}"""), ModelEvent.Completed),
    finishRound("Initial answer.", "initial-finish"),
    finishRound("Answer consumed.", "steered-finish"),
)

private fun finishRound(text: String, id: String): List<ModelEvent> =
    listOf(ModelEvent.ToolCall(id, "finish", """{"response":"$text"}"""), ModelEvent.Completed)

private const val CONTROL_TOOL = "mcp__device_control__set_value"
private const val TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1cAAAAASUVORK5CYII="
