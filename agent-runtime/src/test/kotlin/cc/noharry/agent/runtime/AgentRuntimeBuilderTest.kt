@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.AgentRuntime
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.spi.SessionRunConflictPolicy
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeEvidenceType
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.RUN_CONTEXT_HEADER
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeBuilderTest {
    @Test
    fun buildReturnsRuntime() {
        val runtime: AgentRuntime = AgentRuntimeBuilder.create().buildEngine()

        assertNotNull(runtime)
    }

    @Test
    fun sessionConcurrencyRejectsNonPositiveCaps() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sessionConcurrency(maxActiveSessions = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sessionConcurrency(maxActiveSessions = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sessionConcurrency(maxQueuedRunsPerSession = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sessionConcurrency(maxQueuedRunsPerSession = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sessionConcurrency(maxQueuedRunsGlobal = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sessionConcurrency(maxQueuedRunsGlobal = -1)
        }
    }

    @Test
    fun sessionConcurrencyAcceptsRollbackMaxActiveSessionsOne() {
        val runtime = AgentRuntimeBuilder.create()
            .sessionConcurrency(maxActiveSessions = 1)
            .buildEngine()

        assertNotNull(runtime)
    }

    @Test
    fun agentRequestSessionRunConflictPolicyDefaultsToNullAndKeepsPositionalCtor() {
        val request = AgentRequest("request-1", "session-1", "hello")

        assertNull(request.sessionRunConflictPolicy)
        assertEquals(
            SessionRunConflictPolicy.QUEUE_NEW,
            request.copy(sessionRunConflictPolicy = SessionRunConflictPolicy.QUEUE_NEW)
                .sessionRunConflictPolicy,
        )
    }

    @Test
    fun runContextFollowsTheInputAndStaysOutOfTheSystemPrompt() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val finish = listOf(ModelEvent.ToolCall("tool-1", "finish", """{"response":"ok"}"""), ModelEvent.Completed)
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(FakeModelClient(listOf(finish), seen))
            .systemPrompt("Host rules.")
            .runContext("Recent turn: drove to the tower.")
            .buildEngine()

        runtime.run(agentRequest()).toList()
        runtime.querySessionEvents(SessionEventQuery("session-1", "request-1"))

        val request = seen.first()
        assertEquals(listOf("run request", "$RUN_CONTEXT_HEADER\nRecent turn: drove to the tower."), request.messages.map { it.content })
        assertEquals(listOf("user", "user"), request.messages.map { it.role })
        assertTrue(request.system.orEmpty().contains("Host rules."))
        assertFalse(request.system.orEmpty().contains("drove to the tower"))
        assertThrows(IllegalArgumentException::class.java) { AgentRuntimeBuilder.create().runContext("a".repeat(16 * 1024 + 1)) }
        Unit
    }

    @Test
    fun aSessionsNextRuntimeStartsWithItsEarlierRunsUnchanged() = runBlocking {
        val history = SessionHistory()
        val seen = mutableListOf<ModelRequest>()
        fun runtime(answer: String) = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    listOf(listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"$answer"}"""), ModelEvent.Completed)),
                    seen,
                ),
            )
            .sessionHistory(history)
            .buildEngine()

        runtime("已调到22度").run(AgentRequest("request-1", "session-1", "空调调到22度")).toList()
        runtime("已调到23度").run(AgentRequest("request-2", "session-1", "再高一点")).toList()
        runtime("好的").run(AgentRequest("request-3", "session-2", "你好")).toList()

        val second = seen[1].messages.map { it.role to it.content }
        assertEquals(listOf("user" to "空调调到22度", "assistant" to "已调到22度", "user" to "再高一点"), second)
        assertEquals(listOf("user" to "你好"), seen[2].messages.map { it.role to it.content })
        assertEquals(4, history.messages("session-1").size)
        history.clearSession("session-1")
        assertTrue(history.messages("session-1").isEmpty())
    }

    @Test
    fun virtualWorkspaceDoesNotBecomeTheEventLogWriter() = runBlocking {
        val vfsRoot = Files.createTempDirectory("builder-eventlog-vfs-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(VfsFileSystem.singleFileRoot(vfsRoot))
            .confirmationOnRun()
            .buildEngine()
        runtime.run(agentRequest()).toList()
        runtime.querySessionEvents(SessionEventQuery("session-1", "request-1"))

        assertTrue(runtime.querySessionEvents(SessionEventQuery("session-1", "request-1")).isNotEmpty())
        assertTrue(
            vfsRoot.walkTopDown().none { it.name == "events.jsonl" && it.path.contains("sessions") },
        )
    }

    @Test
    fun configuredDurableRootWritesOfficialEncodedLayout() = runBlocking {
        val eventRoot = Files.createTempDirectory("builder-eventlog-root-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .sessionEventLogRoot(eventRoot)
            .confirmationOnRun()
            .buildEngine()
        runtime.run(agentRequest()).toList()
        runtime.querySessionEvents(SessionEventQuery("session-1", "request-1"))

        val stream = officialStream(eventRoot, "session-1", "request-1")
        val sidecar = officialHealth(eventRoot, "session-1", "request-1")
        assertTrue(stream.isFile)
        assertTrue(sidecar.isFile)
        assertTrue(runtime.querySessionEvents(SessionEventQuery("session-1", "request-1")).isNotEmpty())
        assertEquals(
            cc.noharry.agent.spi.SessionEventLogHealthStatus.COMPLETE,
            runtime.getSessionEventLogHealth("session-1", "request-1").status,
        )
    }

    @Test
    fun packagedDurablePluginWinsOverConfiguredRoot() = runBlocking {
        val pluginRoot = Files.createTempDirectory("builder-eventlog-plugin-").toFile()
        val configuredRoot = Files.createTempDirectory("builder-eventlog-configured-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .plugin(DurableSessionEventLogPlugin.forRoot(pluginRoot))
            .sessionEventLogRoot(configuredRoot)
            .confirmationOnRun()
            .buildEngine()
        runtime.run(agentRequest()).toList()
        runtime.querySessionEvents(SessionEventQuery("session-1", "request-1"))

        assertTrue(officialStream(pluginRoot, "session-1", "request-1").isFile)
        assertFalse(officialStream(configuredRoot, "session-1", "request-1").exists())
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "run request",
    )

}

private fun officialStream(root: File, sessionId: String, runId: String): File =
    File(
        root,
        "session-events/v2/streams/${encodeFileName(sessionId)}/${encodeFileName(runId)}/segments/000001.jsonl",
    )

private fun officialHealth(root: File, sessionId: String, runId: String): File =
    File(root, "session-events/v2/health/${encodeFileName(sessionId)}/${encodeFileName(runId)}.json")

/** A made-up key the redaction must catch; built at run time so the source never holds a key-shaped literal. */
private val FAKE_SECRET_KEY = "sk-" + "protocolsecret123"
