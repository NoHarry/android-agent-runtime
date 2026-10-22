package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsSourceResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeVfsIntegrationAcceptanceTest {
    @Test
    fun agentRuntimeUsesVirtualWorkspaceForNativeToolsAndEvidence() = runBlocking {
        val source = VfsMemorySource(mapOf("AGENTS.md" to "runtime VFS agents"))
        val workspace = vfsRoot(source)
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(workspace)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        round(ModelEvent.ToolCall("list-1", "list_dir", """{"path":"/"}""")),
                        round(ModelEvent.ToolCall("read-1", "read_file", """{"path":"/AGENTS.md","max_chars":128}""")),
                        round(
                            ModelEvent.ToolCall(
                                "write-1",
                                "write_file",
                                """{"path":"/memory/runtime-note.txt","content":"audit needle","mode":"atomic_replace"}""",
                            ),
                        ),
                        round(ModelEvent.ToolCall("grep-1", "grep", """{"path":"/memory","pattern":"needle"}""")),
                        round(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val keys = source.snapshot().keys

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("audit needle", source.snapshot().getValue("memory/runtime-note.txt"))
        assertTrue("keys=$keys", keys.none { it.startsWith("runs/") })
        assertTrue("keys=$keys", keys.none { it.startsWith("sessions/") })
        assertTrue(events.filterIsInstance<AgentEvent.NativeToolCallFinished>().size >= 4)
    }

    private fun vfsRoot(source: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"root","source":"memory://root","target":"/","mode":"readWrite","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { if (it == "memory://root") source else null },
        )

    private fun round(event: ModelEvent): List<ModelEvent> =
        listOf(event, ModelEvent.Completed)
}
