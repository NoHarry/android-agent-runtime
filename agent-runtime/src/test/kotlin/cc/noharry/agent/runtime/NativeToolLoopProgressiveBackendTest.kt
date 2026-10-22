@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import cc.noharry.agent.kernel.core.*

class NativeToolLoopProgressiveBackendTest {
    @Test
    fun tc201FirstRoundContainsOnlyNativeToolsAndDoesNotInspectBackend() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = manyTools())
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("mcp-progressive-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals((NATIVE_TOOLS - NATIVE_SUBAGENT_TOOLS.toSet() - NATIVE_SEARCH_TOOLS.toSet()).filterNot { it.name == "exec_js" }.map { it.name }.toSet(),
            seenRequests.single().tools.map { it.name }.toSet())
        assertFalse(seenRequests.single().tools.any { it.name.startsWith(BACKEND_TOOL_PREFIX) })
        assertFalse(seenRequests.single().system.orEmpty().contains(BACKEND_TOOL_PREFIX))
    }

    @Test
    fun tc202SchemaReadActivatesToolCacheHitDoesNotInspectAndUnknownsAreClassified() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            resultJson = """{"ok":true,"changed":true}""",
            exposedTools = listOf(
                mcpTool("set_temperature"),
                mcpTool("known_but_inactive"),
            ),
        )
        val workspaceRoot = Files.createTempDirectory("mcp-progressive-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(tc202Model(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        val fourthRoundMessages = seenRequests[4].messages.joinToString("\n") { it.content }
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertTrue(seenRequests[2].tools.any { it.name == qualifiedMcpTool("set_temperature") })
        // One backend added, absent exec omitted; subagent tools only with subagents configured.
        assertEquals(NATIVE_TOOLS.size - NATIVE_SUBAGENT_TOOLS.size - NATIVE_SEARCH_TOOLS.size, seenRequests[3].tools.size)
        assertFalse(seenRequests[3].tools.any { it.name == qualifiedMcpTool("known_but_inactive") || it.name == "missing_tool" })
        val cacheHitMessage = seenRequests[3].messages.last().content
        assertTrue(cacheHitMessage, cacheHitMessage.contains("cache_hit\":true"))
        assertTrue(fourthRoundMessages.contains("INACTIVE_BACKEND_TOOL"))
        assertTrue(fourthRoundMessages.contains("/mcp/tools/${qualifiedMcpTool("known_but_inactive")}/schema.json"))
        assertTrue(fourthRoundMessages.contains("UNKNOWN_BACKEND_TOOL"))
        val pendingPath = "memory/lessons/${encodeFileName("global")}/pending.json"
        assertFalse(workspaceRoot.resolve(pendingPath).exists())
    }

    @Test
    fun tc207ConcurrentRunsDoNotShareActivationAndTerminalCleanupRemovesActivation() = runBlocking {
        val model = ConcurrentProgressiveModel()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("mcp-progressive-").toFile())
            .toolProvider(backend)
            .modelClient(model)
            .build()

        val runA = async { runtime.run(request("run-a", "activate", sessionId = "session-a")).toList() }
        val runB = async { runtime.run(request("run-b", "finish-only", sessionId = "session-b")).toList() }
        runA.await()
        runB.await()
        runtime.run(request("run-c", "finish-only-after-cleanup", sessionId = "session-a")).toList()

        val runARequests = model.requestsFor("activate")
        val runBRequests = model.requestsFor("finish-only")
        val cleanupRequests = model.requestsFor("finish-only-after-cleanup")

        assertTrue(runARequests[1].tools.any { it.name == qualifiedMcpTool("set_temperature") })
        assertFalse(runBRequests.first().tools.any { it.name == qualifiedMcpTool("set_temperature") })
        assertFalse(cleanupRequests.first().tools.any { it.name == qualifiedMcpTool("set_temperature") })
    }

    @Test
    fun sessionActivationInheritanceMakesActivatedToolAvailableInNextRun() = runBlocking {
        val model = ConcurrentProgressiveModel()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("session-activation-").toFile())
            .toolProvider(backend)
            .modelClient(model)
            .sessionActivationInheritance()
            .build()

        runtime.run(request("run-a", "activate")).toList()
        runtime.run(request("run-b", "reuse")).toList()

        val reuseRequests = model.requestsFor("reuse")
        assertTrue(reuseRequests.first().tools.any { it.name == qualifiedMcpTool("set_temperature") })
    }

    @Test
    fun memoryHintActivationIsNotInheritedIntoUnrelatedNextRun() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("session-memory-isolation-").toFile()
        promoteMemory(workspaceRoot, "Observed capability set: ac set temperature", scene = "cabin")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(hvacBackend())
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(ModelEvent.ToolCall("finish-hot", "finish", """{"response":"done"}""")),
                        progressiveRound(ModelEvent.ToolCall("finish-tired", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .sessionActivationInheritance()
            .build()

        runtime.run(request("run-hot", "有点热", scene = "cabin")).toList()
        runtime.run(request("run-tired", "我有点困", scene = "cabin")).toList()

        assertTrue(seenRequests[0].tools.any { it.name == qualifiedMcpTool("command_ac_SetTemperature") })
        assertTrue(seenRequests[1].tools.none { it.name.startsWith("mcp__") })
        assertTrue(seenRequests[1].tools.any { it.name == "search_tools" })
    }

    @Test
    fun memoryPrewarmDoesNotEraseExistingSessionActivationContinuity() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("session-memory-overlap-").toFile()
        promoteMemory(workspaceRoot, "Observed capability set: ac set temperature", scene = "cabin")
        val model = ConcurrentProgressiveModel()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(
                LoopMcpProvider(
                    exposedTools = listOf(
                        mcpTool("set_temperature").copy(description = "cabin climate temperature control"),
                    ),
                ),
            )
            .modelClient(model)
            .sessionActivationInheritance()
            .build()

        runtime.run(request("run-a", "activate", scene = "cabin")).toList()
        runtime.run(request("run-b", "有点热", scene = "cabin")).toList()
        runtime.run(request("run-c", "reuse", scene = "cabin")).toList()

        val inheritedTool = qualifiedMcpTool("set_temperature")
        assertTrue(model.requestsFor("有点热").first().tools.any { it.name == inheritedTool })
        assertTrue(model.requestsFor("reuse").first().tools.any { it.name == inheritedTool })
    }

    @Test
    fun memoryHintsPreactivateGenericCapabilityMatchesWithoutIntakeSearch() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-memory-hints-").toFile()
        promoteRouteMemory(workspaceRoot)
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = routeBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(memoryGuidedRouteModel(seenRequests))
            .build()

        val input = "navigate to the tower"
        runtime.run(request("run-memory", input, scene = "navigation")).toList()

        val firstMessages = seenRequests.first().messages
        val firstRuntimeContext = firstMessages.joinToString("\n") { it.content }
        assertFalse(firstRuntimeContext.contains("Runtime memory search hints"))
        assertEquals(listOf(input), firstMessages.filter { it.role == "user" }.map { it.content })
        assertTrue(seenRequests.first().tools.any { it.name == qualifiedMcpTool("command_navi_KeyWordSearch") })
        assertEquals(listOf("command_navi_KeyWordSearch"), backend.calledTools)
    }

    @Test
    fun activatedBackendToolsFollowTheNativeToolsInActivationOrder() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-tool-order-").toFile()
        promoteRouteMemory(workspaceRoot)
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = routeBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(request("run-order", "navigate to the tower", scene = "navigation")).toList()

        // The native tools are the same every round and run, so they lead; tools found later are appended after them
        // and the provider's prompt cache over the earlier list survives.
        val toolNames = seenRequests.first().tools.map { it.name }
        val lastNativeIndex = listOf(toolNames.indexOf("update_todo"), toolNames.indexOf("finish")).maxOrNull()
            ?: error("Expected native tools in request.")
        val activatedBackendIndexes = toolNames.withIndex()
            .filter { it.value.startsWith("mcp__") }
            .map { it.index }

        assertTrue(activatedBackendIndexes.isNotEmpty())
        assertTrue(activatedBackendIndexes.all { it > lastNativeIndex })
    }

    @Test
    fun searchToolsRemainsAdvertisedAfterMemoryPrewarm() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-hidden-search-").toFile()
        promoteMemory(
            workspaceRoot,
            "Capability search hints: music play search music, navi along word search, navi around search, " +
                "navi cal route, navi key word search, navi navigation open, navi query current location",
        )
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = complexRouteBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(
                            ModelEvent.ToolCall("search-1", "search_tools", """{"query":"导航 目的地 规划路线"}"""),
                        ),
                        progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(request("run-hidden-search", "导航并播放音乐", scene = "navigation")).toList()

        val firstRoundTools = seenRequests.first().tools.map { it.name }
        assertTrue(firstRoundTools.contains("search_tools"))
        assertTrue(firstRoundTools.any { it.startsWith("mcp__") })
        val searchResult = seenRequests[1].messages.last { it.role == "tool" && it.toolName == "search_tools" }
        assertTrue(searchResult.content, searchResult.content.contains(""""ok":true"""))
        assertFalse(searchResult.content.contains("UNKNOWN_BACKEND_TOOL"))
    }

    @Test
    fun memoryHintPreactivatesAtMostOneTopToolPerRelevantQuery() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-memory-topk-").toFile()
        promoteMemory(workspaceRoot, "Capability search hints: cabin climate", scene = "cabin")
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = hvacBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(request("run-topk", "有点热", scene = "cabin")).toList()

        val firstRoundTools = seenRequests.first().tools.map { it.name }
        assertEquals(
            1,
            firstRoundTools.count {
                it in setOf(
                    qualifiedMcpTool("command_ac_SetTemperature"),
                    qualifiedMcpTool("command_ac_SetFanSpeed"),
                    qualifiedMcpTool("command_ac_SetMode"),
                )
            },
        )
        assertTrue(firstRoundTools.contains("search_tools"))
    }

    @Test
    fun rawIntakeDoesNotPreactivateBackendToolsWithoutPromotedMemory() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = routeBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("mcp-raw-intake-no-prewarm-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(
            request(
                requestId = "run-raw-intake",
                input = "destination POI keyword search music playback song search route preference",
                scene = "navigation",
            ),
        ).toList()

        val firstRoundTools = seenRequests.first().tools.map { it.name }
        assertFalse(firstRoundTools.contains(qualifiedMcpTool("command_navi_KeyWordSearch")))
        assertFalse(firstRoundTools.contains(qualifiedMcpTool("command_music_PlaySearchMusic")))
        assertEquals(emptyList<String>(), backend.calledTools)
    }

    @Test
    fun unrelatedShortRequestDoesNotReusePriorHvacOrMediaMemory() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-unrelated-memory-").toFile()
        promoteMemory(workspaceRoot, "Observed capability set: ac set temperature", scene = "cabin")
        promoteMemory(workspaceRoot, "Observed capability set: music play search music", scene = "cabin")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(hvacBackend())
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(request("run-unrelated", "我有点困", scene = "cabin")).toList()

        val firstRequest = seenRequests.single()
        assertEquals(listOf("我有点困"), firstRequest.messages.filter { it.role == "user" }.map { it.content })
        assertTrue(firstRequest.tools.none { it.name.startsWith("mcp__") })
        assertTrue(firstRequest.tools.any { it.name == "search_tools" })
    }

    @Test
    fun weakMemoryHintScoreDoesNotPreactivateBackendTools() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-weak-memory-hint-").toFile()
        promoteMemory(workspaceRoot, "Capability search hints: search")
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = routeBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(request("run-weak-hint", "navigate to the tower", scene = "navigation")).toList()

        val firstRoundTools = seenRequests.first().tools.map { it.name }
        assertFalse(firstRoundTools.contains(qualifiedMcpTool("command_navi_KeyWordSearch")))
        assertFalse(firstRoundTools.contains(qualifiedMcpTool("command_music_PlaySearchMusic")))
        assertEquals(emptyList<String>(), backend.calledTools)
    }

    @Test
    fun completeMemoryPrewarmKeepsSearchToolsAvailableWithoutForcingSearch() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("mcp-complete-memory-prewarm-").toFile()
        promoteMemory(
            workspaceRoot,
            "Capability search hints: navi around search, music play search music, navi cal route",
        )
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = complexRouteBackend()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .modelClient(CompletePrewarmModel(seenRequests))
            .build()

        val events = runtime.run(request("run-complete-prewarm", "导航并播放音乐", scene = "navigation")).toList()

        val firstRoundTools = seenRequests.single().tools.map { it.name }
        assertTrue(firstRoundTools.contains("search_tools"))
        assertTrue(firstRoundTools.contains(qualifiedMcpTool("command_navi_CalRoute")))
        assertTrue(firstRoundTools.contains(qualifiedMcpTool("command_music_PlaySearchMusic")))
        assertEquals(2, firstRoundTools.count { it.startsWith("mcp__") })
        assertFalse(events.filterIsInstance<AgentEvent.NativeToolCallStarted>().any { it.toolName == "search_tools" })
        assertEquals(
            setOf(
                "command_navi_CalRoute",
                "command_music_PlaySearchMusic",
            ),
            backend.calledTools.toSet(),
        )
    }

    private suspend fun promoteRouteMemory(workspaceRoot: java.io.File) {
        promoteMemory(
            workspaceRoot,
            "Capability search hints: destination POI, route preference, music playback, command_navi_KeyWordSearch",
        )
    }

    private suspend fun promoteMemory(workspaceRoot: java.io.File, summary: String, scene: String = "navigation") {
        val store = RuntimeMemoryStore(workspaceRoot.resolve("memory"))
        val candidate = RuntimeCandidate(
            candidateId = "candidate-route",
            runId = "prior-run",
            type = RuntimeCandidateType.MEMORY,
            summary = summary,
            confidence = "high",
        )
        repeat(3) { index ->
            store.promote(
                candidate.copy(candidateId = "candidate-route-$index", runId = "prior-run-$index"),
                scene = scene,
                mode = RuntimeLessonMode.PREWARM_ELIGIBLE,
            )
        }
    }

    private fun routeBackend(): LoopMcpProvider = LoopMcpProvider(
        exposedTools = listOf(
            mcpTool("command_navi_KeyWordSearch").copy(description = "destination POI keyword search"),
            mcpTool("command_music_PlaySearchMusic").copy(description = "music playback song search"),
        ),
    )

    private fun hvacBackend(): LoopMcpProvider = LoopMcpProvider(
        exposedTools = listOf(
            mcpTool("command_ac_SetTemperature").copy(description = "cabin climate temperature control"),
            mcpTool("command_ac_SetFanSpeed").copy(description = "cabin climate fan speed control"),
            mcpTool("command_ac_SetMode").copy(description = "cabin climate air mode control"),
            mcpTool("command_music_PlaySearchMusic").copy(description = "music playback song search"),
        ),
    )

    private fun complexRouteBackend(): LoopMcpProvider = LoopMcpProvider(
        exposedTools = listOf(
            mcpTool("command_navi_KeyWordSearch").copy(description = "用关键字搜索导航POI信息"),
            mcpTool("command_navi_QueryCurrentLocation").copy(description = "查询当前位置"),
            mcpTool("command_navi_AroundSearch").copy(description = "周边搜索导航POI信息"),
            mcpTool("command_navi_CalRoute").copy(description = "根据经纬度计算最优路线并导航到该坐标"),
            mcpTool("command_navi_NavigationOpen").copy(description = "打开导航"),
            mcpTool("command_navi_AlongWordSearch").copy(description = "沿途搜索poi信息"),
            mcpTool("command_music_PlaySearchMusic").copy(description = "搜索并播放歌曲"),
        ),
    )

    private fun memoryGuidedRouteModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                progressiveRound(
                    ModelEvent.ToolCall(
                        "tool-navi",
                        qualifiedMcpTool("command_navi_KeyWordSearch"),
                        """{"keyword":"tower"}""",
                    ),
                ),
                progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
            ),
            seenRequests = seenRequests,
        )

    private fun manyTools(): List<cc.noharry.agent.kernel.api.ToolDescriptor> =
        (1..TOOL_COUNT).map { mcpTool("$BACKEND_TOOL_PREFIX$it") }

    private fun request(
        requestId: String,
        input: String,
        scene: String? = null,
        sessionId: String = "session-1",
    ): AgentRequest = AgentRequest(
        requestId = requestId,
        sessionId = sessionId,
        input = input,
        scene = scene,
    )
}

private class CompletePrewarmModel(
    private val seenRequests: MutableList<ModelRequest>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
        seenRequests += request.copy(
            messages = request.messages.toList(),
            tools = request.tools.toList(),
        )
        val toolNames = request.tools.map { it.name }.toSet()
        val hasCompletePrewarm = setOf(
            qualifiedMcpTool("command_navi_CalRoute"),
            qualifiedMcpTool("command_music_PlaySearchMusic"),
        ).all(toolNames::contains)
        val events = if (!hasCompletePrewarm) {
            progressiveRound(
                ModelEvent.ToolCall(
                    "search-1",
                    "search_tools",
                    """{"query":"navi cal route parking around search"}""",
                ),
            )
        } else {
            progressiveRound(
                ModelEvent.ToolCall("route-1", qualifiedMcpTool("command_navi_CalRoute"), "{}"),
                ModelEvent.ToolCall("music-1", qualifiedMcpTool("command_music_PlaySearchMusic"), "{}"),
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
            )
        }
        return kotlinx.coroutines.flow.flowOf(*events.toTypedArray())
    }
}

private fun tc202Model(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            progressiveRound(ModelEvent.ToolCall("catalog", "read_file", """{"path":"/mcp/servers/default/tools.json"}""")),
            progressiveRound(ModelEvent.ToolCall("schema-1", "read_file", schemaPathArgs(qualifiedMcpTool("set_temperature")))),
            progressiveRound(ModelEvent.ToolCall("schema-2", "read_file", schemaPathArgs(qualifiedMcpTool("set_temperature")))),
            progressiveRound(
                ModelEvent.ToolCall("inactive", qualifiedMcpTool("known_but_inactive"), "{}"),
                ModelEvent.ToolCall("unknown", "missing_tool", "{}"),
                ModelEvent.ToolCall("active", qualifiedMcpTool("set_temperature"), """{"degreesCelsius":25}"""),
            ),
            progressiveRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
        ),
        seenRequests = seenRequests,
    )

private fun progressiveRound(vararg events: ModelEvent): List<ModelEvent> =
    events.toList() + ModelEvent.Completed

private fun schemaPathArgs(toolName: String): String =
    """{"path":"/mcp/tools/$toolName/schema.json"}"""

private class ConcurrentProgressiveModel : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private val roundByInput = ConcurrentHashMap<String, Int>()
    private val requestsByInput = ConcurrentHashMap<String, MutableList<ModelRequest>>()

    override fun complete(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
        val input = request.messages.first { it.role == "user" }.content
        requestsByInput.getOrPut(input) { mutableListOf() } += request
        val round = roundByInput.merge(input, 1, Int::plus) ?: 1
        val events = when {
            input == "activate" && round == 1 -> listOf(
                ModelEvent.ToolCall(
                    id = "schema",
                    name = "read_file",
                    argumentsJson = schemaPathArgs(qualifiedMcpTool("set_temperature")),
                ),
                ModelEvent.Completed,
            )
            else -> listOf(
                ModelEvent.ToolCall("finish-$round", "finish", """{"response":"$input"}"""),
                ModelEvent.Completed,
            )
        }
        return kotlinx.coroutines.flow.flowOf(*events.toTypedArray())
    }

    fun requestsFor(input: String): List<ModelRequest> =
        requestsByInput[input].orEmpty()
}

private const val TOOL_COUNT = 391
private const val BACKEND_TOOL_PREFIX = "fixture_tool_"
