@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime


import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsSourceResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import cc.noharry.agent.kernel.core.*

class ContextDrivenRuntimeTest {
    @Test
    fun virtualWorkspaceOnlyBuildStillExposesContextCatalog() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(VfsFileSystem.singleFileRoot(Files.createTempDirectory("context-vfs-only-").toFile()))
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(contextRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""))),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(contextRequest()).toList()

        val system = seenRequests.first().system.orEmpty()
        assertTrue(system.contains("Context paths"))
        assertTrue(system.contains("/AGENTS.md"))
        assertTrue(seenRequests.first().tools.any { it.name == "read_file" })
    }

    @Test
    fun virtualWorkspaceOnlyBuildDiscoversSkillsFromVfsRoot() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(vfsWithSkill())
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        contextRound(
                            ModelEvent.ToolCall(
                                "skill-1",
                                "read_file",
                                """{"path":"/.agents/skills/debug/SKILL.md"}""",
                            ),
                        ),
                        contextRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(contextRequest()).toList()

        val firstSystem = seenRequests.first().system.orEmpty()
        assertTrue(firstSystem.contains("debug: VFS provided debug skill."))
        assertTrue(seenRequests[1].messages.last().content.contains("VFS skill body"))
    }

    @Test
    fun virtualWorkspaceOnlyBuildKeepsTodoToolAndEventObservable() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(vfsWithSkill())
            .modelClient(todoModel(seenRequests))
            .build()

        val events = runtime.run(contextRequest()).toList()

        // Storeless update_todo still succeeds: the model sees the normalized echo...
        val secondMessages = seenRequests[1].messages.joinToString("\n") { it.content }
        assertTrue(secondMessages.contains(""""ok":true"""))
        assertTrue(secondMessages.contains("Check current AC"))
        assertTrue(!secondMessages.contains("TODO_STORE_NOT_CONFIGURED"))
        // ...and hosts see the full normalized todo list on the public finished event.
        val todoEvent = events.filterIsInstance<AgentEvent.NativeToolCallFinished>()
            .single { it.toolName == "update_todo" }
        assertTrue(todoEvent.result.contains(""""ok":true"""))
        assertTrue(todoEvent.result.contains("Raise fan"))
        assertTrue(todoEvent.result.contains("in_progress"))
    }

    @Test
    fun throwingTodoStoreMapsToWriteFailedAndKeepsRunAlive() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val loop = NativeToolLoop(
            modelClient = todoModel(seenRequests),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
            todoStore = ThrowingTodoStore(),
        )

        val outcome = loop.run(contextRequest(), contextRequest().requestId)

        val terminal = outcome as NativeLoopOutcome.Terminal
        val secondMessages = seenRequests[1].messages.joinToString("\n") { it.content }
        val todoStartedCount = outcome.events.filterIsInstance<AgentEvent.NativeToolCallStarted>()
            .count { it.toolName == "update_todo" }
        val todoFinished = outcome.events.filterIsInstance<AgentEvent.NativeToolCallFinished>()
            .single { it.toolName == "update_todo" }
        // The thrown store I/O error maps to the failure protocol: run survives to finish,
        // started/finished events stay paired, and the model sees the failure result.
        assertEquals("done", terminal.result.response)
        assertEquals(1, todoStartedCount)
        assertTrue(secondMessages.contains("TODO_STORE_WRITE_FAILED"))
        assertTrue(todoFinished.result.contains("TODO_STORE_WRITE_FAILED"))
    }

    @Test
    fun virtualWorkspaceOnlyBuildDoesNotWriteRuntimeStateToBusinessVfs() = runBlocking {
        val root = VfsMemorySource()
        val workspace = vfsWithRuntimeRoot(root)
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(workspace)
            .modelClient(todoModel(mutableListOf()))
            .build()

        runtime.run(contextRequest()).toList()

        val keys = root.snapshot().keys
        assertTrue("keys=$keys", keys.none { it.startsWith("runs/") })
        assertTrue("keys=$keys", keys.none { it.startsWith("sessions/") })
        assertTrue("keys=$keys", keys.none { it.startsWith("cache/") })
        assertTrue("keys=$keys", keys.none { it.startsWith("evidence/") })
        assertTrue("keys=$keys", keys.none { it.startsWith("memory/") })
    }

    @Test
    fun virtualWorkspaceBuilderPersistsTerminalMemoryCandidatesAndPromotesDistinctRuns() = runBlocking {
        val memory = VfsMemorySource()
        val workspace = vfsWithMemoryMount(memory)
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("command_ac_SetTemperature")))
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(workspace)
            .toolProvider(backend)
            .modelClient(FakeModelClient(eventsByRound = memoryPromotionRounds(runCount = 3)))
            .build()

        runtime.run(memoryRequest(index = 1)).toList()

        val pendingAfterFirstRun = memory.snapshot()
            .filterKeys { it.startsWith("lessons/") && it.endsWith("pending.json") }
            .values
            .joinToString("\n")
        assertTrue(pendingAfterFirstRun.contains("Observed capability set: ac set temperature"))

        runtime.run(memoryRequest(index = 2)).toList()
        runtime.run(memoryRequest(index = 3)).toList()

        val persistedLessons = memory.snapshot()
            .filterKeys { it.startsWith("lessons/") && it.endsWith("lessons.json") }
            .values
            .joinToString("\n")
        assertTrue(persistedLessons.contains("Observed capability set: ac set temperature"))
        assertTrue(persistedLessons.contains("\"hitCount\":3"))
        assertEquals(3, backend.calledTools.size)
    }

    @Test
    fun promptInjectsSceneSkillCatalogAndTodoUpdatesNextRound() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val builder = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("context-runtime-").toFile())
            .modelClient(todoModel(seenRequests))
        repeat(10) { index ->
            builder.skill(
                HostSkill(
                    "ac_skill_$index",
                    "AC skill $index.",
                    listOf("ac_control"),
                    "# AC Skill $index",
                ),
            )
        }
        val runtime = builder.build()

        runtime.run(contextRequest()).toList()

        val firstSystem = seenRequests.first().system.orEmpty()
        val secondSystem = seenRequests[1].system.orEmpty()
        val secondMessages = seenRequests[1].messages.joinToString("\n") { it.content }
        assertTrue(firstSystem.contains("Context paths"))
        assertTrue(firstSystem.contains("ac_skill_0") && firstSystem.contains("ac_skill_9"))
        assertTrue(firstSystem.toByteArray(Charsets.UTF_8).size <= STATIC_PROMPT_SOFT_BUDGET_BYTES)
        assertTrue(seenRequests.first().tools.any { it.name == "update_todo" })
        assertTrue(firstSystem == secondSystem)
        assertTrue(seenRequests[1].messages.take(seenRequests[0].messages.size) == seenRequests[0].messages)
        assertTrue(seenRequests[1].messages.none { it.content.contains("Runtime context") })
        assertTrue(secondMessages.contains("Check current AC"))
        assertTrue(secondMessages.contains("in_progress"))
        assertTrue(secondMessages.contains("Raise fan") && secondMessages.contains("pending"))
        assertTrue(secondMessages.contains("Confirm comfort") && secondMessages.contains("completed"))
    }

    @Test
    fun regularRoundsDoNotReciteTodoReminderWithoutCompaction() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("context-runtime-todo-reminder-").toFile())
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        contextRound(
                            ModelEvent.ToolCall(
                                id = "todo-1",
                                name = "update_todo",
                                argumentsJson = """
                                    {
                                      "todos": [
                                        {"content":"Check current AC","status":"IN_PROGRESS"},
                                        {"content":"Raise fan","status":"PENDING"}
                                      ]
                                    }
                                """.trimIndent(),
                            ),
                        ),
                        contextRound(ModelEvent.ToolCall("read-1", "read_file", """{"path":"/missing-1.txt"}""")),
                        contextRound(ModelEvent.ToolCall("read-2", "read_file", """{"path":"/missing-2.txt"}""")),
                        contextRound(ModelEvent.ToolCall("read-3", "read_file", """{"path":"/missing-3.txt"}""")),
                        contextRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(contextRequest()).toList()

        assertTrue(seenRequests.all { request ->
            request.messages.none { it.content.contains("Runtime todo reminder") }
        })
        assertTrue(seenRequests[4].messages.take(seenRequests[3].messages.size) == seenRequests[3].messages)
    }

    @Test
    fun todoReminderDoesNotReciteStaleStateAfterStateAdvancingToolResults() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("context-runtime-stale-todo-reminder-").toFile())
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        contextRound(
                            ModelEvent.ToolCall(
                                id = "todo-1",
                                name = "update_todo",
                                argumentsJson = """
                                    {
                                      "todos": [
                                        {"content":"Write note","status":"IN_PROGRESS"},
                                        {"content":"Finish","status":"PENDING"}
                                      ]
                                    }
                                """.trimIndent(),
                            ),
                        ),
                        contextRound(
                            ModelEvent.ToolCall(
                                "write-1",
                                "write_file",
                                """{"path":"/scratch/note.txt","content":"done"}""",
                            ),
                        ),
                        contextRound(ModelEvent.ToolCall("read-1", "read_file", """{"path":"/missing-1.txt"}""")),
                        contextRound(ModelEvent.ToolCall("read-2", "read_file", """{"path":"/missing-2.txt"}""")),
                        contextRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(contextRequest()).toList()

        val finishRequestMessages = seenRequests[4].messages.joinToString("\n") { it.content }
        assertTrue(finishRequestMessages.contains("Write note"))
        assertTrue(!finishRequestMessages.contains("Runtime todo reminder"))
        assertTrue(seenRequests[4].messages.take(seenRequests[3].messages.size) == seenRequests[3].messages)
    }

    @Test
    fun todoReminderIsPersistedOnceAfterHistoryCompaction() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val historyStore = InMemoryLoopHistoryStore()
        val root = Files.createTempDirectory("context-runtime-compacted-todo-reminder-").toFile()
        val longTodo = "Check current AC " + "carefully ".repeat(80)
        val loop = NativeToolLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    contextRound(
                        ModelEvent.ToolCall(
                            id = "todo-1",
                            name = "update_todo",
                            argumentsJson = """
                                {
                                  "todos": [
                                    {"content":"$longTodo","status":"IN_PROGRESS"}
                                  ]
                                }
                            """.trimIndent(),
                        ),
                    ),
                    contextRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                ),
                seenRequests = seenRequests,
            ),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
            todoStore = TodoStore(root),
            historyCompactor = HistoryCompactor(modelClient = null, budgetBytes = 300, minBatchMessages = 1),
            loopHistoryStore = historyStore,
        )

        loop.run(contextRequest(), contextRequest().requestId)

        val secondRequestText = seenRequests[1].messages.joinToString("\n") { it.content }
        val historyText = requireNotNull(historyStore.read(contextRequest().requestId))
            .messages
            .joinToString("\n") { it.content }
        assertTrue(secondRequestText.contains("Runtime todo reminder (after-compaction)"))
        assertTrue(secondRequestText.contains("Check current AC"))
        assertEquals(1, secondRequestText.split("Runtime todo reminder").size - 1)
        assertTrue(historyText.contains("Runtime todo reminder (after-compaction)"))
        assertEquals(1, historyText.split("Runtime todo reminder").size - 1)
    }

    private fun todoModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                contextRound(
                    ModelEvent.ToolCall(
                        id = "todo-1",
                        name = "update_todo",
                        argumentsJson = """
                            {
                              "todos": [
                                {"content":"Check current AC","status":"IN_PROGRESS"},
                                {"content":"Raise fan","status":"PENDING"},
                                {"content":"Confirm comfort","status":"COMPLETED"}
                              ]
                            }
                        """.trimIndent(),
                    ),
                ),
                contextRound(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
            ),
            seenRequests = seenRequests,
        )

    private fun contextRound(vararg events: ModelEvent): List<ModelEvent> =
        events.toList() + ModelEvent.Completed

    private fun memoryPromotionRounds(runCount: Int): List<List<ModelEvent>> = buildList {
        repeat(runCount) { index ->
            add(contextRound(schemaRead("command_ac_SetTemperature")))
            add(
                contextRound(
                    ModelEvent.ToolCall(
                        id = "set-temperature-${index + 1}",
                        name = qualifiedMcpTool("command_ac_SetTemperature"),
                        argumentsJson = """{"temperature":22}""",
                    ),
                ),
            )
            add(contextRound(ModelEvent.ToolCall("finish-${index + 1}", "finish", """{"response":"done"}""")))
        }
    }

    private fun memoryRequest(index: Int): AgentRequest = AgentRequest(
        requestId = "memory-request-$index",
        sessionId = "memory-session-$index",
        input = "set the cabin temperature to 22",
        scene = "ac_control",
    )

    private fun vfsWithSkill(): VfsFileSystem {
        val skills = VfsMemorySource(
            mapOf(
                "debug/SKILL.md" to """
                    ---
                    description: VFS provided debug skill.
                    ---

                    # Debug

                    VFS skill body
                """.trimIndent(),
            ),
        )
        val memory = VfsMemorySource()
        return VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {
                      "id": "skills",
                      "source": "memory://skills",
                      "target": "/.agents/skills",
                      "mode": "readOnly",
                      "priority": 90
                    },
                    {
                      "id": "memory",
                      "source": "memory://runtime",
                      "target": "/memory",
                      "mode": "readWrite",
                      "priority": 10
                    }
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                when (source) {
                    "memory://skills" -> skills
                    "memory://runtime" -> memory
                    else -> null
                }
            },
        )
    }

    private fun vfsWithMemoryMount(memory: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {
                      "id": "memory",
                      "source": "memory://runtime",
                      "target": "/memory",
                      "mode": "readWrite",
                      "priority": 10
                    }
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "memory://runtime") memory else null
            },
        )

    private fun vfsWithRuntimeRoot(root: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {
                      "id": "root",
                      "source": "memory://root",
                      "target": "/",
                      "mode": "readWrite",
                      "priority": 10
                    }
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "memory://root") root else null
            },
        )

    private fun contextRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "tune the AC",
        scene = "ac_control",
    )
}

private class ThrowingTodoStore : TodoStorePort {
    override suspend fun load(sessionId: String): TodoState? = null

    override suspend fun save(sessionId: String, state: TodoState): Boolean =
        throw java.io.IOException("disk full")

    override suspend fun clear(sessionId: String): Boolean = true
}
