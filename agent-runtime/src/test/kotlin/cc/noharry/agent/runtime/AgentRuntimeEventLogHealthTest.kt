@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog
import cc.noharry.agent.runtime.eventlog.appendForcedLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import cc.noharry.agent.kernel.core.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.agent.kernel.core.TraceVisibilityConfig

class AgentRuntimeEventLogHealthTest {
    @Test
    fun missingBackendReturnsUnknown() = runBlocking {
        val runtime = runtimeWithLog(null)

        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            runtime.getSessionEventLogHealth("session-1", "run-1").status,
        )
    }

    @Test
    fun legacyCustomBackendReturnsUnknown() = runBlocking {
        val runtime = runtimeWithLog(
            object : SessionEventLog {
                override fun append(event: SessionEventAppend) = error("unused")
                override fun query(query: SessionEventQuery) = emptyList<cc.noharry.agent.kernel.api.SessionEvent>()
                override fun replay(sessionId: String, runId: String) = emptyList<cc.noharry.agent.kernel.api.SessionEvent>()
            },
        )

        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            runtime.getSessionEventLogHealth("session-1", "run-1").status,
        )
    }

    @Test
    fun fileBackedBackendPassesThroughCompleteAndDegraded() = runBlocking {
        val root = Files.createTempDirectory("runtime-eventlog-health-").toFile()
        var remainingSuccesses = 1
        val log = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            persistLine = { file, line ->
                if (remainingSuccesses <= 0) error("injected append failure")
                remainingSuccesses -= 1
                appendForcedLine(file, line)
            },
        )
        log.append(startedEvent())
        val completeRuntime = runtimeWithLog(log)
        assertEquals(
            SessionEventLogHealthStatus.COMPLETE,
            completeRuntime.getSessionEventLogHealth(SESSION_ID, RUN_ID).status,
        )

        assertThrows(IllegalStateException::class.java) {
            log.append(startedEvent(type = SessionEventType.RUN_STATUS))
        }
        val degradedRuntime = runtimeWithLog(log)
        assertEquals(
            SessionEventLogHealthStatus.DEGRADED,
            degradedRuntime.getSessionEventLogHealth(SESSION_ID, RUN_ID).status,
        )
    }
}

private fun runtimeWithLog(log: SessionEventLog?): DefaultAgentRuntime = DefaultAgentRuntime(
    modelClient = null,
    mcpRegistry = ToolProviderRegistry(),
    confirmationTemplate = null,
    protocolRequestTemplate = null,
    nowEpochMillis = { 100 },
    hookChain = RuntimeHookChain.empty(),
    traceVisibilityConfig = TraceVisibilityConfig(),
    traceRedactor = DefaultTraceRedactor,
    sessionEventLog = log,
)

private fun startedEvent(
    type: SessionEventType = SessionEventType.RUN_STARTED,
): SessionEventAppend = SessionEventAppend(
    sessionId = SESSION_ID,
    runId = RUN_ID,
    requestId = REQUEST_ID,
    type = type,
)

private fun incrementalIds(): () -> String {
    var next = 0
    return { "event-${++next}" }
}

private const val SESSION_ID = "session-1"
private const val RUN_ID = "run-1"
private const val REQUEST_ID = "request-1"
