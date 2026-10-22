@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProtocolRequestCancellation
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.ProtocolRequestRejection
import cc.noharry.agent.kernel.api.ProtocolRequestResolution
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.runtime.eventlog.scalarFields
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProtocolRequestStorageTest {
    @Test
    fun pendingProtocolRequestRestoresFromFileStorage() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-protocol-").toFile()
        storedRuntime(storageRoot)
            .protocolRequestOnRun(kind = "host_control", payloadJson = """{"scope":"debug"}""")
            .build()
            .run(agentRequest())
            .toList()

        val restored = storedRuntime(storageRoot).build()
        val pending = restored.getPendingProtocolRequests("session-1").single()

        assertEquals("request-1:protocol-request", pending.protocolRequestId)
        assertEquals(RuntimeProtocolRequestStatus.WAITING, pending.status)
        assertEquals(RuntimeRunStatus.WAITING_PROTOCOL_REQUEST, restored.getRunState("request-1")?.status)
    }

    @Test
    fun protocolRequestResolveIsIdempotentOnSameRuntime() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .protocolRequestOnRun(kind = "host_control")
            .build()
        runtime.run(agentRequest()).toList()
        val protocolRequest = runtime.getPendingProtocolRequests().single()

        val resolved = runtime.resolveRuntimeProtocolRequest(
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
        val result = runtime.getRuntimeResult("request-1")

        assertEquals(RuntimeProtocolRequestStatus.RESOLVED, resolved.protocolStatus())
        assertTrue(duplicate is ProtocolRequestCloseLoopResult.AlreadyApplied)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState("request-1")?.status)
        assertEquals(RuntimeCompletionOutcome.UNKNOWN, result?.completion?.outcome)
        assertTrue(runtime.getPendingProtocolRequests().isEmpty())
        val evidenceId = resolved.protocolEvidenceId()
        assertEquals(
            RuntimeEvidenceType.PROTOCOL_REQUEST_RESOLUTION,
            runtime.getRuntimeEvidence(evidenceId)?.type,
        )
    }

    @Test
    fun protocolRequestResolveAfterProcessRestartIsUnsupported() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-protocol-restart-").toFile()
        storedRuntime(storageRoot)
            .protocolRequestOnRun(kind = "host_control")
            .build()
            .run(agentRequest())
            .toList()
        val protocolRequest = storedRuntime(storageRoot).build().getPendingProtocolRequests().single()

        val resolved = storedRuntime(storageRoot).build().resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )
        val restored = storedRuntime(storageRoot).build()

        assertEquals(RuntimeProtocolRequestStatus.RESOLVED, resolved.protocolStatus())
        assertEquals(RuntimeRunStatus.CANCELLED, restored.getRunState("request-1")?.status)
        assertEquals(
            RuntimeErrorCode.PROCESS_RESTART_ADMISSION_UNSUPPORTED.wireCode,
            restored.getRuntimeResult("request-1")?.errorCode,
        )
        assertEquals(
            RuntimeCompletionOutcome.UNKNOWN,
            restored.getRuntimeResult("request-1")?.completion?.outcome,
        )
    }

    @Test
    fun protocolRejectPersistsCancelledUnknownAndFinishesEventLog() = runBlocking {
        assertProtocolClosePersists(
            close = { runtime, protocolRequestId ->
                runtime.rejectRuntimeProtocolRequest(
                    ProtocolRequestRejection(protocolRequestId, "rej-1", reason = "test reject"),
                )
            },
        )
    }

    @Test
    fun protocolCancelPersistsCancelledUnknownAndFinishesEventLog() = runBlocking {
        assertProtocolClosePersists(
            close = { runtime, protocolRequestId ->
                runtime.cancelRuntimeProtocolRequest(
                    ProtocolRequestCancellation(protocolRequestId, "can-1"),
                )
            },
        )
    }

    @Test
    fun protocolExpirePersistsCancelledUnknownAndFinishesEventLog() = runBlocking {
        val log = InMemorySessionEventLog()
        var now = 1_000L
        val runtime = AgentRuntimeBuilder.create()
            .nowEpochMillis { now }
            .protocolRequestOnRun(kind = "host_control", expiresInMillis = 5)
            .sessionEventLog(log)
            .build()
        runtime.run(agentRequest()).toList()
        val protocolRequest = runtime.getPendingProtocolRequests().single()
        now = 1_006L
        runtime.resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(protocolRequest.protocolRequestId, "resolution-1"),
        )
        assertProtocolTerminalTruth(runtime, log)
    }

    @Test
    fun protocolRequestCancelRestoresTerminalStateAcrossRuntimeInstances() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-protocol-").toFile()
        storedRuntime(storageRoot)
            .protocolRequestOnRun(kind = "host_control")
            .build()
            .run(agentRequest())
            .toList()
        val protocolRequest = storedRuntime(storageRoot).build().getPendingProtocolRequests().single()

        val cancelled = storedRuntime(storageRoot).build().cancelRuntimeProtocolRequest(
            ProtocolRequestCancellation(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "cancel-1",
                reason = "host cancelled",
            ),
        )
        val restored = storedRuntime(storageRoot).build()

        assertEquals(RuntimeProtocolRequestStatus.CANCELLED, cancelled.protocolStatus())
        assertEquals(RuntimeRunStatus.CANCELLED, restored.getRunState("request-1")?.status)
        assertTrue(restored.getPendingProtocolRequests().isEmpty())
    }

    @Test
    fun expiredProtocolRequestRestoresExpiredStateAcrossRuntimeInstances() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-protocol-").toFile()
        var now = 1_000L
        storedRuntime(storageRoot) { now }
            .protocolRequestOnRun(kind = "host_control", expiresInMillis = 5)
            .build()
            .run(agentRequest())
            .toList()
        val protocolRequest = storedRuntime(storageRoot) { now }.build()
            .getPendingProtocolRequests()
            .single()

        now = 1_006L
        val expired = storedRuntime(storageRoot) { now }.build().resolveRuntimeProtocolRequest(
            ProtocolRequestResolution(
                protocolRequestId = protocolRequest.protocolRequestId,
                requestId = "resolution-1",
            ),
        )
        val restored = storedRuntime(storageRoot) { now }.build()

        assertTrue(expired is ProtocolRequestCloseLoopResult.Expired)
        assertEquals(RuntimeProtocolRequestStatus.EXPIRED, expired.protocolStatus())
        assertEquals(RuntimeRunStatus.CANCELLED, restored.getRunState("request-1")?.status)
        assertTrue(restored.getPendingProtocolRequests().isEmpty())
    }

    private suspend fun assertProtocolClosePersists(
        close: suspend (cc.noharry.agent.kernel.api.AgentRuntime, String) -> ProtocolRequestCloseLoopResult,
    ) {
        val log = InMemorySessionEventLog()
        val runtime = AgentRuntimeBuilder.create()
            .protocolRequestOnRun(kind = "host_control")
            .sessionEventLog(log)
            .build()
        runtime.run(agentRequest()).toList()
        val protocolRequest = runtime.getPendingProtocolRequests().single()
        close(runtime, protocolRequest.protocolRequestId)
        assertProtocolTerminalTruth(runtime, log)
    }

    private suspend fun assertProtocolTerminalTruth(
        runtime: cc.noharry.agent.kernel.api.AgentRuntime,
        log: InMemorySessionEventLog,
    ) {
        val result = runtime.getRuntimeResult("request-1")
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
        assertEquals(RuntimeRunStatus.CANCELLED, result?.status)
        assertEquals(RuntimeCompletionOutcome.UNKNOWN, result?.completion?.outcome)
        val replay = log.replay("session-1", "request-1")
        assertTrue(replay.any { it.type == SessionEventType.RUN_FINISHED })
        assertEquals(
            RuntimeRunStatus.CANCELLED.name,
            replay.last { it.type == SessionEventType.RUN_STATUS }.payload.scalarFields()["status"],
        )
    }

    private fun storedRuntime(
        storageRoot: java.io.File,
        clock: () -> Long = { System.currentTimeMillis() },
    ): AgentRuntimeBuilder = AgentRuntimeBuilder.create()
        .reliabilityStorageRoot(storageRoot)
        .nowEpochMillis(clock)

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
