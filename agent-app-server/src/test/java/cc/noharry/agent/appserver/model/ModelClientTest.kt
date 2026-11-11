@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.ModelWords
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.session.SESSION_QUERY_INDEX_PATH
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.model.ModelTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelClientTest {
    @Test
    fun planningTextWithChinesePlaceAndNumbersDoesNotBecomeFinish() = runBlocking {
        val delegate = CapturingModelClient(events = listOf(
            ModelEvent.TextDelta("All done. Now finish with a concise Chinese report covering 广州塔, 329.9 kilometers, played music."),
            ModelEvent.Completed,
        ))
        val events = AppModelClient(delegate).complete(postBusinessRequest(ALL_TODO_COMPLETED_SYSTEM)).toList()
        assertFalse(events.filterIsInstance<ModelEvent.ToolCall>().any { it.name == "finish" })
    }

    @Test
    fun completedReportRecoveryDoesNotTruncateRequestedResults() = runBlocking {
        val response = "已完成查询。" + "每个站点的剩余距离均已查询，".repeat(60) + "最后一站剩余1234米。"
        val events = AppModelClient(CapturingModelClient(listOf(ModelEvent.TextDelta(response), ModelEvent.Completed)))
            .complete(postBusinessRequest(ALL_TODO_COMPLETED_SYSTEM)).toList()
        val finish = events.filterIsInstance<ModelEvent.ToolCall>().single()
        assertTrue(finish.argumentsJson.contains("最后一站剩余1234米。"))
    }

    @Test
    fun businessRefusalCannotSupplySuccessfulEvidenceForFinalRecovery() = runBlocking {
        val request = postBusinessRequest(ALL_TODO_COMPLETED_SYSTEM).copy(messages = listOf(
            ModelMessage("user", "添加途经点"),
            ModelMessage("tool", """{"result":{"isError":true,"structuredContent":{"status":{"name":"INVALID_ARGUMENT"}}}}""",
                toolName = "mcp__provider__route", toolCallId = "failed"),
        ))
        val events = AppModelClient(CapturingModelClient(listOf(ModelEvent.TextDelta("已完成全部操作。"), ModelEvent.Completed)))
            .complete(request).toList()
        assertFalse(events.filterIsInstance<ModelEvent.ToolCall>().any { it.name == "finish" })
    }

    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun declaresSerializedConcurrency() {
        val client = AppModelClient(delegate = CapturingModelClient())
        assertEquals(ProviderConcurrencyDeclaration.serialized(), client.providerConcurrency)
    }

    @Test
    fun promptOpensWithTheHostsPersona() {
        assertTrue(appSystemPrompt("").startsWith(Persona.GENERIC.identity + "\n\nCurrent turn and continuity:"))
        AppHost.install(persona = Persona("Notes", "You are Notes, an assistant that keeps the user's notes on this device."))
        try {
            assertTrue(appSystemPrompt("").startsWith("You are Notes, an assistant that keeps the user's notes on this device.\n\nCurrent turn"))
        } finally {
            AppHost.install(persona = Persona.GENERIC)
        }
    }

    @Test
    fun promptRequiresMcpDiscoveryWithoutHardcodedFallbackTools() {
        val prompt = appSystemPrompt("")
        assertTrue(prompt.contains("/mcp/servers.json"))
        assertTrue(prompt.contains("/mcp/tools.index.json"))
        assertTrue(prompt.contains("/mcp/tools.json"))
        assertTrue(prompt.contains("/mcp/tools/<qualified-tool>/schema.json"))
        assertTrue(prompt.contains("Discover host capabilities progressively before calling a backend tool"))
        // Voice instructions now come only from the enabled APK skill snapshot.
        assertFalse(prompt.contains("进度播报"))
        assertFalse(prompt.contains("debug_voice_progress"))
        assertTrue(prompt.contains("Execute only intents present in the current user text"))
        assertTrue(prompt.contains("Do not continue previous-turn side intents"))
        assertTrue(prompt.contains("do not assume"))
        assertTrue(prompt.contains("fixed server names, tool names, or call orders"))
        assertFalse(prompt.contains("device navi"))
        assertFalse(prompt.contains("navi_ordered_flow"))
        assertFalse(prompt.contains("/.agents/skills/navi_ordered_flow/SKILL.md"))
        assertFalse(prompt.contains("command_navi_*"))
        assertTrue(prompt.contains("update_todo"))
        assertTrue(prompt.contains("pending, in_progress, completed"))
        assertTrue(prompt.contains("backend business tool"))
        assertTrue(prompt.contains("finish with outcome success closes the remaining checklist items"))
        assertFalse(prompt.contains("include update_todo and finish in the same tool_calls array"))
        assertTrue(prompt.contains("via call_tool(name, arguments)"))
        assertTrue(prompt.contains("including several reads after one action"))
        assertTrue(prompt.contains("finish with {\"response\":\"...\",\"outcome\":\"success\"}"))
        assertTrue(prompt.contains("outcome=\"partial\" or \"blocked\""))
        assertFalse(prompt.contains("finish later"))
        assertFalse(prompt.contains("Never"))
        assertFalse(prompt.contains("never"))
        assertFalse(prompt.contains("at most two short TTS"))
        assertTrue(prompt.contains("not proof that the new user request is already done"))
        assertTrue(prompt.contains(SESSION_QUERY_INDEX_PATH))
        assertTrue(prompt.contains("context-dependent"))
        assertTrue(prompt.contains("not resolvable from"))
        assertTrue(prompt.contains("actionDomains and lastAction"))
        assertTrue(prompt.contains("only relevant listed session summary"))
        assertFalse(prompt.contains("complaints such as"))
        assertFalse(prompt.contains("debug_echo"))
        assertFalse(prompt.contains("/mcp/" + "domains"))
    }

    @Test
    fun promptIncludesSameSessionContextForShortAffirmativeFollowUps() {
        val prompt = appSystemPrompt(
            "- user=还是有点热 | assistant=需要我为您打开空调设置页面吗？ | " +
                "runStatus=COMPLETED | completion=PARTIAL",
            words = ModelWords.CHINESE,
        )

        assertTrue(prompt.contains("Current debug session context"))
        assertTrue(prompt.contains("需要我为您打开空调设置页面吗？"))
        assertTrue(prompt.contains("completion=PARTIAL"))
        assertTrue(prompt.contains("affirmative or negative answer"))
        assertTrue(prompt.contains("immediately preceding assistant question"))
        assertTrue(prompt.contains("treat the offered action itself as the current-turn intent"))
        assertTrue(prompt.contains("discover and call the matching current"))
        assertTrue(prompt.contains("Do not repeat the question or merely acknowledge the answer"))
        assertTrue(prompt.contains("Then give only a concise Chinese report of its result; do not quote"))
        assertTrue(prompt.contains("response is concise Chinese, with no internal plans"))
        assertTrue(prompt.contains("short reply (可以、不要、继续、刚才那个)"))
        assertTrue(prompt.contains("do not quote the prior question or narrate internal reasoning"))
        assertTrue(prompt.contains("take no offered action"))
    }

    @Test
    fun promptAlwaysIncludesBoundedSameSessionContext() {
        val prompt = appSystemPrompt(
            "lastSuccessfulAction=mcp__device__command_navi_CalRoute\nrecentTurnsNewestFirst=old turn",
        )

        assertTrue(prompt.contains("Current debug session context for continuity:"))
        assertTrue(prompt.contains("mcp__device__command_navi_CalRoute"))
        assertTrue(prompt.startsWith(AppHost.persona.identity))
    }

    @Test
    fun promptKeepsStableProgressiveDiscoveryShapeWhenBackendToolsAreActivated() {
        val prompt = appSystemPrompt("")

        assertTrue(prompt.contains("Discover host capabilities progressively before calling a backend tool"))
        assertTrue(prompt.contains("Call the exact qualified backend tool from the catalog"))
        assertTrue(prompt.contains("Every successful run should use finish"))
    }

    @Test
    fun clientDoesNotMutateCanonicalModelRequestBeforeDelegate() = runBlocking {
        val delegate = CapturingModelClient()
        val client = AppModelClient(delegate = delegate)
        val request = ModelRequest(
            system = appSystemPrompt("assistant=需要我为您打开空调设置页面吗？"),
            messages = listOf(ModelMessage("user", "可以")),
        )

        client.complete(request).toList()

        assertEquals(request, delegate.lastRequest)
        assertEquals(listOf("user"), delegate.lastRequest.messages.map { it.role })
        assertTrue(delegate.lastRequest.system.orEmpty().contains("打开空调设置页面"))
    }

    @Test
    fun doesNotWrapPlainFinalTextAsFinishToolCall() = runBlocking {
        val delegate = CapturingModelClient(events = listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed))
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(ModelRequest(messages = listOf(ModelMessage("user", "hi")))).toList()

        val finishCount = events.filterIsInstance<ModelEvent.ToolCall>().count { it.name == "finish" }
        assertTrue(finishCount == 0)
    }

    @Test
    fun recoversPostBusinessFinalTextAsFinishToolCall() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.TextDelta("已完成导航规划，并播放周杰伦的歌。"),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(postBusinessRequest(system = ALL_TODO_COMPLETED_SYSTEM)).toList()
        val finish = events.filterIsInstance<ModelEvent.ToolCall>().single { it.name == "finish" }

        assertTrue(finish.argumentsJson.contains("已完成导航规划"))
        assertTrue(events.last() is ModelEvent.Completed)
    }

    @Test
    fun thinkingDoesNotBecomeSyntheticSuccessWithoutSkills() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.ThinkingDelta("All tasks are completed. Let me now call finish with a final response."),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(postBusinessRequest(system = BUDGET_WARNING_SYSTEM)).toList()
        assertFalse(events.filterIsInstance<ModelEvent.ToolCall>().any { it.name == "finish" })
        assertTrue(events.any { it is ModelEvent.ThinkingDelta })
    }

    @Test
    fun businessToolNamesCannotJustifySyntheticCompletion() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.ThinkingDelta("All tasks are completed. Let me now call finish with a final response."),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(
            ModelRequest(
                system = ALL_TODO_COMPLETED_SYSTEM,
                messages = listOf(
                    ModelMessage("user", "我想去深圳北站但是之前先去世界之窗顺便吃个肯德基"),
                    ModelMessage(
                        role = "tool",
                        content = """{"ok":true}""",
                        toolCallId = "tool-1",
                        toolName = "mcp__device__command_navi_CalRoute",
                    ),
                    ModelMessage(
                        role = "tool",
                        content = """{"ok":true}""",
                        toolCallId = "tool-2",
                        toolName = "mcp__device__command_music_PlaySearchMusic",
                    ),
                ),
                tools = listOf(ModelTool("finish", "{}")),
            ),
        ).toList()
        assertFalse(events.filterIsInstance<ModelEvent.ToolCall>().any { it.name == "finish" })
    }

    @Test
    fun workflowModeDoesNotInventFinishFromThinkingOrCompletedTodo() = runBlocking {
        val delegate = CapturingModelClient(events = listOf(
            ModelEvent.ThinkingDelta("All tasks are completed. Let me now call finish with a final response."),
            ModelEvent.ToolCall("todo", "update_todo", """{"todos":[{"content":"query","status":"completed"}]}"""),
            ModelEvent.Completed,
        ))
        val events = AppModelClient(delegate)
            .complete(postBusinessRequest(system = ALL_TODO_COMPLETED_SYSTEM)).toList()
        assertFalse(events.filterIsInstance<ModelEvent.ToolCall>().any { it.name == "finish" })
    }

    @Test
    fun workflowModePreservesModelSuppliedFinalResponse() = runBlocking {
        val delegate = CapturingModelClient(events = listOf(
            ModelEvent.ToolCall("final", "finish", """{"response":"媒体音量为12。"}"""), ModelEvent.Completed,
        ))
        val events = AppModelClient(delegate)
            .complete(postBusinessRequest(system = ALL_TODO_COMPLETED_SYSTEM)).toList()
        assertEquals(1, events.filterIsInstance<ModelEvent.ToolCall>().count { it.name == "finish" })
        assertTrue(events.filterIsInstance<ModelEvent.ToolCall>().single().argumentsJson.contains("12"))
    }

    @Test
    fun thinkingAndEmptyTodoDoNotInventFinish() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.ToolCall("tool-todo", "update_todo", """{"todos":[]}"""),
                ModelEvent.ThinkingDelta("All tasks are completed. Let me now call finish with a final response."),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(
            postBusinessRequest(
                system = ALL_TODO_COMPLETED_SYSTEM,
                tools = listOf(ModelTool("update_todo", "{}"), ModelTool("finish", "{}")),
            ),
        ).toList()
        val toolNames = events.filterIsInstance<ModelEvent.ToolCall>().map { it.name }
        assertEquals(listOf("update_todo"), toolNames)
    }

    @Test
    fun completedTodosAloneDoNotInventFinish() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.ToolCall(
                    "tool-todo",
                    "update_todo",
                    """{"todos":[{"content":"导航","status":"completed"},{"content":"音乐","status":"completed"}]}""",
                ),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(
            postBusinessRequest(
                system = TODO_IN_PROGRESS_SYSTEM,
                tools = listOf(ModelTool("update_todo", "{}"), ModelTool("finish", "{}")),
            ),
        ).toList()
        val toolNames = events.filterIsInstance<ModelEvent.ToolCall>().map { it.name }
        assertEquals(listOf("update_todo"), toolNames)
    }

    @Test
    fun recoversChineseCompletedReportAsFinishToolCall() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.TextDelta("下面是我的完整汇报，全部已执行完成：导航已开启，正在播放周杰伦的歌曲。"),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(postBusinessRequest(system = ALL_TODO_COMPLETED_SYSTEM)).toList()
        val finish = events.filterIsInstance<ModelEvent.ToolCall>().single { it.name == "finish" }

        assertTrue(finish.argumentsJson.contains("全部已执行完成"))
        assertTrue(finish.argumentsJson.contains("导航已开启"))
    }

    @Test
    fun doesNotRecoverPostBusinessExplorationTextWithoutFinalSignal() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(ModelEvent.ThinkingDelta("I need to list more tools."), ModelEvent.Completed),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(postBusinessRequest(system = TODO_IN_PROGRESS_SYSTEM)).toList()

        val finishCount = events.filterIsInstance<ModelEvent.ToolCall>().count { it.name == "finish" }
        assertTrue(finishCount == 0)
    }

    @Test
    fun doesNotWrapExplorationTextWhenResponseAlsoHasToolCalls() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.TextDelta("我先看一下能力。"),
                ModelEvent.ToolCall("tool-1", "command_ac_TemperatureAdjust", "{}"),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(ModelRequest(messages = listOf(ModelMessage("user", "再高一点")))).toList()

        val finishCount = events.filterIsInstance<ModelEvent.ToolCall>().count { it.name == "finish" }
        assertTrue(finishCount == 0)
    }

    @Test
    fun doesNotRecoverWhenCurrentRoundHasToolCalls() = runBlocking {
        val delegate = CapturingModelClient(
            events = listOf(
                ModelEvent.TextDelta("已完成查询。"),
                ModelEvent.ToolCall("tool-1", "mcp__device__command_navi_KeyWordSearch", "{}"),
                ModelEvent.Completed,
            ),
        )
        val client = AppModelClient(delegate = delegate)

        val events = client.complete(postBusinessRequest(system = ALL_TODO_COMPLETED_SYSTEM)).toList()

        val finishCount = events.filterIsInstance<ModelEvent.ToolCall>().count { it.name == "finish" }
        assertTrue(finishCount == 0)
    }

    private fun postBusinessRequest(
        system: String,
        tools: List<ModelTool> = listOf(ModelTool("finish", "{}")),
    ): ModelRequest =
        ModelRequest(
            system = system,
            messages = listOf(
                ModelMessage("user", "介绍下你自己，我想去广州塔，开始导航后放首周杰伦的歌"),
                ModelMessage(
                    role = "tool",
                    content = """{"ok":true,"items":[{"name":"广州塔"}]}""",
                    toolCallId = "tool-1",
                    toolName = "mcp__device__command_navi_KeyWordSearch",
                ),
                ModelMessage(
                    role = "tool",
                    content = """{"ok":true}""",
                    toolCallId = "tool-2",
                    toolName = "mcp__device__command_music_PlaySearchMusic",
                ),
            ),
            tools = tools,
        )
}

private val ALL_TODO_COMPLETED_SYSTEM = """
    Todo:
    - [x] 介绍自己
    - [x] 开始导航后播放周杰伦的歌
""".trimIndent()

private val BUDGET_WARNING_SYSTEM = """
    Todo:
    - [x] 介绍自己
    - [x] 开始导航后播放周杰伦的歌
    {"budgetWarning":{"roundsRemaining":0,"toolCallsRemaining":32}}
""".trimIndent()

private val TODO_IN_PROGRESS_SYSTEM = """
    Todo:
    - [~] 查找工具
    - [ ] 播放周杰伦的歌
""".trimIndent()

private class CapturingModelClient(
    private val events: List<ModelEvent> = listOf(ModelEvent.Completed),
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    lateinit var lastRequest: ModelRequest

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        lastRequest = request
        return flowOf(*events.toTypedArray())
    }
}

class ModelFirstEventTimeoutTest {
    @Test
    fun aStreamThatSendsNothingIsAskedAgain() = runBlocking {
        val silent = flow<ModelEvent> { delay(10_000) }

        try {
            silent.withFirstEventTimeout(50).toList()
            fail("a silent stream must fail")
        } catch (error: ModelClientException) {
            assertTrue(error.recoverable)
        }
    }

    @Test
    fun aStreamThatAnswersIsLeftAlone() = runBlocking {
        val slowAfterStart = flow {
            emit(ModelEvent.TextDelta("好"))
            delay(200)
            emit(ModelEvent.Completed)
        }

        assertEquals(listOf(ModelEvent.TextDelta("好"), ModelEvent.Completed), slowAfterStart.withFirstEventTimeout(50).toList())
        assertEquals(listOf(ModelEvent.Completed), flowOf<ModelEvent>(ModelEvent.Completed).withFirstEventTimeout(50).toList())
        assertTrue(flowOf<ModelEvent>().withFirstEventTimeout(50).toList().isEmpty())
    }
}
