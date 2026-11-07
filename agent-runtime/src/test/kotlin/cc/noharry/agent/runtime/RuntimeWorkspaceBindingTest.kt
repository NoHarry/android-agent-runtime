@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeWorkspaceBindingTest {
    @Test fun threadSelectsRegisteredWorkspaceForNativeTools() = runBlocking {
        val alpha = workspace("alpha content")
        val beta = workspace("beta content")
        val requests = mutableListOf<ModelRequest>()
        workspaceBuilder(alpha, beta, readThenFinishModel(requests)).build().use { runtime ->
            val events = runtime.startThread(ThreadOptions(workspace = "beta")).runStreamed("read note").toList()
            assertEquals("done", (events.last() as ThreadEvent.TurnCompleted).result.finalResponse)
            val changed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                .filterIsInstance<ThreadItem.Notice>().single { it.kind == NoticeKind.WORKSPACE_CHANGED }
            assertEquals("beta", changed.details.workspaceId)
            assertTrue(requests[1].messages.last().content.contains("beta content"))
        }
    }

    @Test fun threadRetainsSelectedWorkspaceWhenResumeOmitsTheOption() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        workspaceBuilder(workspace("alpha content"), workspace("beta content"), readThenFinishModel(requests)).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(workspace = "beta"))
            thread.run("read note")
            runtime.resumeThread(thread.id).run("read again")
            assertTrue(requests[1].messages.last().content.contains("beta content"))
            assertTrue(requests[3].messages.last().content.contains("beta content"))
        }
    }

    @Test fun approvalContinuationReadsTheSelectedWorkspace() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        workspaceBuilder(workspace("alpha-note"), workspace("beta-note"), askThenReadThenFinishModel(requests)).build().use { runtime ->
            val events = runtime.startThread(ThreadOptions(workspace = "beta")).collectApproving("read note")
            assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            val toolText = requests.flatMap { it.messages }.filter { it.role == "tool" }.joinToString { it.content }
            assertTrue(toolText.contains("beta-note"))
            assertTrue(requests.flatMap { it.messages }.none { it.content.contains("alpha-note") })
        }
    }

    @Test fun restartingPreservesWorkspaceButCannotExecuteAnOldApproval() = runBlocking {
        val root = Files.createTempDirectory("workspace-restart-").toFile()
        val alpha = workspace("alpha-note")
        val beta = workspace("beta-note")
        workspaceBuilder(alpha, beta, FakeModelClient(schemaThenBackendRounds())).storage(root).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "session-1", workspace = "beta"))
            thread.runStreamed("set temperature").collect { event ->
                if (event is ThreadEvent.ItemStarted && event.item is Approval) thread.interrupt()
            }
        }
        val requests = mutableListOf<ModelRequest>()
        workspaceBuilder(alpha, beta, readThenFinishModel(requests)).storage(root).build().use { restored ->
            val thread = restored.resumeThread("session-1")
            assertTrue(thread.pendingRequests().isEmpty())
            val old = thread.items().filterIsInstance<Approval>().single()
            assertEquals(RespondResult.NOT_FOUND, old.approve())
            assertTrue(requests.isEmpty())
            assertEquals(TurnStatus.COMPLETED, thread.run("read note").status)
            assertTrue(requests.last().messages.any { it.role == "tool" && "beta-note" in it.content })
            assertFalse(requests.last().messages.any { "alpha-note" in it.content })
        }
    }

    @Test fun unknownWorkspaceFailsBeforeModelCall() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        AgentRuntimeBuilder.create().workspace(RuntimeWorkspaceRef("known", VfsFileSystem.singleFileRoot(workspace("content"))))
            .modelClient(readThenFinishModel(requests)).build().use { runtime ->
                val result = runtime.startThread(ThreadOptions(workspace = "missing")).run("read note")
                assertTrue(requests.isEmpty())
                assertEquals(TurnStatus.FAILED, result.status)
                assertEquals(RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode, result.error?.code)
                assertTrue(result.error?.message.orEmpty().contains("missing"))
            }
    }

    private fun workspaceBuilder(alpha: File, beta: File, model: FakeModelClient): AgentRuntimeBuilder =
        AgentRuntimeBuilder.create()
            .workspace(RuntimeWorkspaceRef("alpha", VfsFileSystem.singleFileRoot(alpha)))
            .workspace(RuntimeWorkspaceRef("beta", VfsFileSystem.singleFileRoot(beta)))
            .defaultWorkspace("alpha")
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved()).modelClient(model)

    private fun readThenFinishModel(requests: MutableList<ModelRequest>): FakeModelClient = FakeModelClient(
        eventsByRound = List(2) { index -> listOf(
            listOf(ModelEvent.ToolCall("read-$index", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
            finishEvents("done"),
        ) }.flatten(), seenRequests = requests,
    )

    private fun askThenReadThenFinishModel(requests: MutableList<ModelRequest>): FakeModelClient = FakeModelClient(
        eventsByRound = listOf(
            listOf(schemaRead("set_temperature"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("action", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("read", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
            finishEvents("done"),
        ), seenRequests = requests,
    )

    private fun workspace(content: String): File =
        Files.createTempDirectory("runtime-workspace-binding-").toFile().canonicalFile.also {
            File(it, "note.txt").writeText(content)
        }
}
