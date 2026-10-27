@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ProtocolRequestCancellation
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.ProtocolRequestRejection
import cc.noharry.agent.kernel.api.ProtocolRequestResolution
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
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
        val runtime: AgentRuntime = AgentRuntimeBuilder.create().build()

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
            .build()

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
            .build()

        runtime.run(agentRequest()).toList()

        val request = seen.first()
        assertEquals(listOf("trigger protocol request", "$RUN_CONTEXT_HEADER\nRecent turn: drove to the tower."), request.messages.map { it.content })
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
            .build()

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
    fun runEmitsPendingProtocolRequestWhenConfigured() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .protocolRequestOnRun(kind = "host_control", payloadJson = """{"scope":"debug"}""")
            .build()

        val events = runtime.run(agentRequest()).toList()
        val protocolEvent = events.filterIsInstance<AgentEvent.RuntimeProtocolRequestCreated>()
            .single()

        assertEquals(RuntimeProtocolRequestStatus.WAITING, protocolEvent.protocolRequest.status)
        assertEquals(RuntimeRunStatus.WAITING_PROTOCOL_REQUEST, runtime.getRunState("request-1")?.status)
        assertEquals(listOf(protocolEvent.protocolRequest), runtime.getPendingProtocolRequests("session-1"))
    }

    @Test
    fun confirmationTakesPriorityOverConfiguredProtocolRequest() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .confirmationOnRun()
            .protocolRequestOnRun(kind = "host_control", payloadJson = """{"scope":"debug"}""")
            .build()

        val events = runtime.run(agentRequest()).toList()

        assertTrue(events.any { it is AgentEvent.ConfirmationRequired })
        assertFalse(events.any { it is AgentEvent.RuntimeProtocolRequestCreated })
        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState("request-1")?.status)
        assertTrue(runtime.getPendingProtocolRequests("session-1").isEmpty())
    }

    @Test
    fun protocolRequestPublicOutputsRedactPayloadByDefault() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .protocolRequestOnRun(
                kind = "host_control",
                payloadJson = """{"scope":"host_debug_scope","token":"${FAKE_SECRET_KEY}","phone":"13800138000"}""",
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val protocolEvent = events.filterIsInstance<AgentEvent.RuntimeProtocolRequestCreated>().single()
        val pending = runtime.getPendingProtocolRequests("session-1").single()
        val resolved = runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = pending.protocolRequestId,
                requestId = "resolution-1",
            ),
        )
        val publicPayloads = listOf(
            protocolEvent.protocolRequest.payloadJson,
            pending.payloadJson,
            resolved.protocolPayloadJson(),
        )

        publicPayloads.forEach { payload ->
            assertTrue(payload.contains("redacted"))
            assertFalse(payload.contains("host_debug_scope"))
            assertFalse(payload.contains(FAKE_SECRET_KEY))
            assertFalse(payload.contains("13800138000"))
        }
    }

    @Test
    fun resolveProtocolRequestIsIdempotentByRequestId() = runBlocking {
        val runtime = runtimeWithPendingProtocolRequest()
        val protocolRequest = runtime.getPendingProtocolRequests().single()

        val first = runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )
        val duplicate = runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )

        assertEquals(RuntimeProtocolRequestStatus.RESOLVED, first.protocolStatus())
        assertTrue(duplicate is ProtocolRequestCloseLoopResult.AlreadyApplied)
        assertTrue(runtime.getPendingProtocolRequests().isEmpty())
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState("request-1")?.status)
        assertEquals(
            cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.UNKNOWN,
            runtime.getRuntimeResult("request-1")?.completion?.outcome,
        )
        assertFalse(
            cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary.firstPartyPass(
                runtime.getRunState("request-1")!!.status,
                runtime.getRuntimeResult("request-1")!!.completion,
            ),
        )
    }

    @Test
    fun resolveProtocolRequestRecordsEvidenceAndTrace() = runBlocking {
        val runtime = runtimeWithPendingProtocolRequest()
        val protocolRequest = runtime.getPendingProtocolRequests().single()

        val resolved = runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )
        val evidenceId = resolved.protocolEvidenceId()
        val evidence = runtime.getRuntimeEvidence(evidenceId)
        val traceEvent = runtime.getRuntimeTraceEvents("request-1")
            .single { it.eventName == "PROTOCOL_REQUEST_RESOLVED" }

        assertEquals(RuntimeEvidenceType.PROTOCOL_REQUEST_RESOLUTION, evidence?.type)
        assertEquals("PROTOCOL_REQUEST_RESOLVED", evidence?.traceEventName)
        assertEquals("PROTOCOL_REQUEST_RESOLVED", traceEvent.eventName)
        assertEquals(evidenceId, traceEvent.evidenceId)
    }

    @Test
    fun resolveExpiredProtocolRequestMovesToExpired() = runBlocking {
        var now = 1_000L
        val runtime = AgentRuntimeBuilder.create()
            .nowEpochMillis { now }
            .protocolRequestOnRun(kind = "host_control", expiresInMillis = 5)
            .build()
        runtime.run(agentRequest()).toList()
        val protocolRequest = runtime.getPendingProtocolRequests().single()

        now = 1_006L
        val expired = runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )

        assertTrue(expired is ProtocolRequestCloseLoopResult.Expired)
        assertEquals(RuntimeProtocolRequestStatus.EXPIRED, expired.protocolStatus())
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
        assertTrue(runtime.getPendingProtocolRequests().isEmpty())
    }

    @Test
    fun terminalProtocolRequestRejectDoesNotChangeResolvedState() = runBlocking {
        val runtime = runtimeWithPendingProtocolRequest()
        val protocolRequest = runtime.getPendingProtocolRequests().single()

        runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )
        val rejected = runtime.rejectRuntimeProtocolRequest(
            ProtocolRequestRejection(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "rejection-1",
                reason = "too late",
            ),
        )

        assertTrue(rejected is ProtocolRequestCloseLoopResult.AlreadyTerminal)
        assertEquals(RuntimeProtocolRequestStatus.RESOLVED, rejected.protocolStatus())
    }

    @Test
    fun cancelProtocolRequestTransitionsToCancelled() = runBlocking {
        val runtime = runtimeWithPendingProtocolRequest()
        val protocolRequest = runtime.getPendingProtocolRequests().single()

        val cancelled = runtime.cancelRuntimeProtocolRequest(
            ProtocolRequestCancellation(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "cancel-1",
                reason = "host cancelled",
            ),
        )

        assertEquals(RuntimeProtocolRequestStatus.CANCELLED, cancelled.protocolStatus())
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
        assertTrue(runtime.getPendingProtocolRequests(runId = "request-1").isEmpty())
    }

    @Test
    fun resolveUnknownProtocolRequestReturnsNotFound() = runBlocking {
        val runtime = AgentRuntimeBuilder.create().build()

        val result = runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = "missing",
                requestId = "resolution-1",
            ),
        )

        assertEquals(ProtocolRequestCloseLoopResult.NotFound, result)
    }

    @Test
    fun virtualWorkspaceDoesNotBecomeTheEventLogWriter() = runBlocking {
        val vfsRoot = Files.createTempDirectory("builder-eventlog-vfs-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(VfsFileSystem.singleFileRoot(vfsRoot))
            .protocolRequestOnRun(kind = "host_control")
            .build()
        runtime.run(agentRequest()).toList()

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
            .protocolRequestOnRun(kind = "host_control")
            .build()
        runtime.run(agentRequest()).toList()

        val stream = officialStream(eventRoot, "session-1", "request-1")
        val sidecar = officialHealth(eventRoot, "session-1", "request-1")
        assertTrue(stream.isFile)
        assertTrue(sidecar.isFile)
        assertTrue(runtime.querySessionEvents(SessionEventQuery("session-1", "request-1")).isNotEmpty())
        assertEquals(
            cc.noharry.agent.kernel.api.SessionEventLogHealthStatus.COMPLETE,
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
            .protocolRequestOnRun(kind = "host_control")
            .build()
        runtime.run(agentRequest()).toList()

        assertTrue(officialStream(pluginRoot, "session-1", "request-1").isFile)
        assertFalse(officialStream(configuredRoot, "session-1", "request-1").exists())
    }

    private suspend fun runtimeWithPendingProtocolRequest(): AgentRuntime {
        val runtime = AgentRuntimeBuilder.create()
            .protocolRequestOnRun(kind = "host_control")
            .build()
        runtime.run(agentRequest()).toList()
        return runtime
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "trigger protocol request",
    )

    private fun ProtocolRequestCloseLoopResult.protocolStatus(): RuntimeProtocolRequestStatus =
        when (this) {
            is ProtocolRequestCloseLoopResult.Updated -> protocolRequest.status
            is ProtocolRequestCloseLoopResult.AlreadyApplied -> protocolRequest.status
            is ProtocolRequestCloseLoopResult.AlreadyTerminal -> protocolRequest.status
            is ProtocolRequestCloseLoopResult.Expired -> protocolRequest.status
            is ProtocolRequestCloseLoopResult.InvalidState -> status
            ProtocolRequestCloseLoopResult.NotFound -> error("No protocol request status.")
        }

    private fun ProtocolRequestCloseLoopResult.protocolPayloadJson(): String =
        when (this) {
            is ProtocolRequestCloseLoopResult.Updated -> protocolRequest.payloadJson
            is ProtocolRequestCloseLoopResult.AlreadyApplied -> protocolRequest.payloadJson
            is ProtocolRequestCloseLoopResult.AlreadyTerminal -> protocolRequest.payloadJson
            is ProtocolRequestCloseLoopResult.Expired -> protocolRequest.payloadJson
            is ProtocolRequestCloseLoopResult.InvalidState -> error("No protocol request payload.")
            ProtocolRequestCloseLoopResult.NotFound -> error("No protocol request payload.")
        }

    private fun ProtocolRequestCloseLoopResult.protocolEvidenceId(): String =
        when (this) {
            is ProtocolRequestCloseLoopResult.Updated -> protocolRequest.resolutionEvidenceId
            is ProtocolRequestCloseLoopResult.AlreadyApplied -> protocolRequest.resolutionEvidenceId
            is ProtocolRequestCloseLoopResult.AlreadyTerminal -> protocolRequest.resolutionEvidenceId
            is ProtocolRequestCloseLoopResult.Expired -> protocolRequest.resolutionEvidenceId
            is ProtocolRequestCloseLoopResult.InvalidState -> null
            ProtocolRequestCloseLoopResult.NotFound -> null
        } ?: error("No protocol request evidence id.")
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
