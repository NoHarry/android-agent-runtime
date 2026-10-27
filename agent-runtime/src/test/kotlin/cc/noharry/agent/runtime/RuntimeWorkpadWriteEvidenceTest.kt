package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.state.encodeFileName
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWorkpadWriteEvidenceTest {
    @Test
    fun runtimeRecordsWriteArtifactsAndCleansRunScratchOnTerminal() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-write-evidence-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = "write_file",
                                argumentsJson = """{"path":"/scratch/out.txt","content":"hello"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"done"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()

        val workpad = requireNotNull(runtime.getRuntimeWorkpad("request-1"))
        val traceNames = runtime.getRuntimeTraceEvents("request-1").map { it.eventName }
        assertTrue(workpad.writeArtifacts.single().contains("path=/scratch/out.txt"))
        assertTrue(workpad.writeArtifacts.single().contains("bytes=5"))
        assertTrue(traceNames.contains("WORKSPACE_WRITE_RECORDED"))
        assertFalse(File(workspace, "scratch/${encodeFileName("request-1")}").exists())
    }
}
