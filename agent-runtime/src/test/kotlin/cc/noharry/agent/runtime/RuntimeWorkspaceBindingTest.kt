@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.vfs.VfsFileSystem
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.failed

class RuntimeWorkspaceBindingTest {
    @Test
    fun requestSelectsRegisteredWorkspaceForNativeTools() = runBlocking {
        val alpha = workspace("alpha content")
        val beta = workspace("beta content")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspace(RuntimeWorkspaceRef("alpha", VfsFileSystem.singleFileRoot(alpha)))
            .workspace(RuntimeWorkspaceRef("beta", VfsFileSystem.singleFileRoot(beta)))
            .defaultWorkspace("alpha")
            .modelClient(readThenFinishModel(seenRequests, "done"))
            .build()

        val events = runtime.run(agentRequest(workspaceId = "beta")).toList()

        assertEquals("done", events.filterIsInstance<AgentEvent.ResultAvailable>().single().result.response)
        assertEquals("beta", events.filterIsInstance<AgentEvent.WorkspaceChanged>().single().workspaceId)
        assertTrue(seenRequests[1].messages.last().content.contains("beta content"))
    }

    @Test
    fun sessionInheritsLastSelectedWorkspaceWhenRequestOmitsWorkspaceId() = runBlocking {
        val alpha = workspace("alpha content")
        val beta = workspace("beta content")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspace(RuntimeWorkspaceRef("alpha", VfsFileSystem.singleFileRoot(alpha)))
            .workspace(RuntimeWorkspaceRef("beta", VfsFileSystem.singleFileRoot(beta)))
            .defaultWorkspace("alpha")
            .modelClient(readThenFinishModel(seenRequests, "done"))
            .build()

        runtime.run(agentRequest(requestId = "request-1", workspaceId = "beta")).toList()
        runtime.run(agentRequest(requestId = "request-2")).toList()

        assertTrue(seenRequests[1].messages.last().content.contains("beta content"))
        assertTrue(seenRequests[3].messages.last().content.contains("beta content"))
    }

    @Test
    fun sameRuntimeResumeReadsSpecifiedWorkspaceViaReadFile() = runBlocking {
        val alpha = workspace("alpha-note")
        val beta = workspace("beta-note")
        val root = Files.createTempDirectory("workspace-resume-same-runtime-").toFile()
        val seen = mutableListOf<ModelRequest>()
        sameRuntime(root, alpha, beta, seen).use { runtime ->
            val pending = runtime.run(agentRequest(workspaceId = "beta")).toList()
                .filterIsInstance<AgentEvent.ConfirmationRequired>()
                .single()
            runtime.resumeConfirmation(approve(pending.pending.pendingId))
            val result = runtime.getRuntimeResult("request-1")
            assertEquals(RuntimeRunStatus.COMPLETED, result?.status)
            val toolText = seen.flatMap { it.messages }.filter { it.role == "tool" }.joinToString { it.content }
            assertTrue(toolText.contains("beta-note"))
            assertTrue(seen.flatMap { it.messages }.none { it.content.contains("alpha-note") })
        }
    }

    @Test
    fun processRestartResumeIsUnsupportedWithoutModelOrTools() = runBlocking {
        val alpha = workspace("alpha-note")
        val beta = workspace("beta-note")
        val root = Files.createTempDirectory("workspace-resume-process-restart-").toFile()
        val pending = waitingRuntime(root, alpha, beta).run(agentRequest(workspaceId = "beta")).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
        val seen = mutableListOf<ModelRequest>()
        resumeRuntime(root, alpha, beta, seen).use { restored ->
            val result = restored.resumeConfirmation(approve(pending.pending.pendingId))
            assertTrue(
                result is cc.noharry.agent.kernel.api.ConfirmationResumeResult.Rejected ||
                    result is cc.noharry.agent.kernel.api.ConfirmationResumeResult.NotFound,
            )
            assertEquals(emptyList<ModelRequest>(), seen)
        }
    }

    @Test
    fun unknownWorkspaceFailsBeforeModelCall() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspace(RuntimeWorkspaceRef("known", VfsFileSystem.singleFileRoot(workspace("content"))))
            .modelClient(readThenFinishModel(seenRequests, "done"))
            .build()

        val events = runtime.run(agentRequest(workspaceId = "missing")).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()

        assertEquals(emptyList<ModelRequest>(), seenRequests)
        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode, result.errorCode)
        assertEquals(RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode, failed.code)
        assertTrue(failed.message.contains("missing"))
    }

    private fun readThenFinishModel(
        seenRequests: MutableList<ModelRequest>,
        response: String,
    ): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(ModelEvent.ToolCall("read-1", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"$response"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("read-2", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("finish-2", "finish", """{"response":"$response"}"""), ModelEvent.Completed),
            ),
            seenRequests = seenRequests,
        )

    private fun agentRequest(
        requestId: String = "request-1",
        workspaceId: String? = null,
    ): AgentRequest =
        AgentRequest(
            requestId = requestId,
            sessionId = "session-1",
            input = "read note",
            workspaceId = workspaceId,
        )

    private fun workspace(content: String): File =
        Files.createTempDirectory("runtime-workspace-binding-").toFile().canonicalFile.also {
            File(it, "note.txt").writeText(content)
        }

    private fun waitingRuntime(root: File, alpha: File, beta: File): cc.noharry.agent.kernel.api.AgentRuntime =
        workspaceRuntime(root, alpha, beta, askThenWaitModel())

    private fun resumeRuntime(
        root: File,
        alpha: File,
        beta: File,
        seen: MutableList<ModelRequest>,
    ): cc.noharry.agent.kernel.api.AgentRuntime = workspaceRuntime(root, alpha, beta, readThenFinishModel(seen, "done"))

    private fun sameRuntime(
        root: File,
        alpha: File,
        beta: File,
        seen: MutableList<ModelRequest>,
    ): cc.noharry.agent.kernel.api.AgentRuntime = workspaceRuntime(root, alpha, beta, askThenReadThenFinishModel(seen, "done"))

    private fun workspaceRuntime(
        root: File,
        alpha: File,
        beta: File,
        model: FakeModelClient,
    ): cc.noharry.agent.kernel.api.AgentRuntime =
        AgentRuntimeBuilder.create()
            .reliabilityStorageRoot(root)
            .workspace(RuntimeWorkspaceRef("alpha", VfsFileSystem.singleFileRoot(alpha)))
            .workspace(RuntimeWorkspaceRef("beta", VfsFileSystem.singleFileRoot(beta)))
            .defaultWorkspace("alpha")
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved())
            .modelClient(model)
            .build()

    private fun askThenReadThenFinishModel(
        seenRequests: MutableList<ModelRequest>,
        response: String,
    ): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(schemaRead("set_temperature"), ModelEvent.Completed),
                listOf(
                    ModelEvent.ToolCall(
                        "tool-ask",
                        qualifiedMcpTool("set_temperature"),
                        "{}",
                    ),
                    ModelEvent.Completed,
                ),
                listOf(ModelEvent.ToolCall("read-1", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"$response"}"""), ModelEvent.Completed),
            ),
            seenRequests = seenRequests,
        )

    private suspend fun assertProcessRestartAdmissionUnsupported(
        result: RuntimeResult?,
        runtime: cc.noharry.agent.kernel.api.AgentRuntime,
    ) {
        assertEquals(RuntimeRunStatus.CANCELLED, result?.status)
        assertEquals(RuntimeErrorCode.PROCESS_RESTART_ADMISSION_UNSUPPORTED.wireCode, result?.errorCode)
        assertEquals(RuntimeCompletionOutcome.UNKNOWN, result?.completion?.outcome)
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
    }

    private fun askThenWaitModel(): FakeModelClient =
        FakeModelClient(
            eventsByRound = schemaThenBackendRounds(),
        )

    private fun resumeRequest(requestId: String = "answer-1", workspaceId: String?): AgentRequest =
        AgentRequest(
            requestId = requestId,
            sessionId = "session-1",
            input = "continue",
            resumeRunId = "request-1",
            workspaceId = workspaceId,
        )

    private inline fun cc.noharry.agent.kernel.api.AgentRuntime.use(block: (cc.noharry.agent.kernel.api.AgentRuntime) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
