@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.DynamicToolSource
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchRecordingOrderTest {
    @Test
    fun firstStageResultPrecedesDependentStageStartAndBothPrecedeModel() {
        val log = InMemorySessionEventLog()
        var clock = 100L
        val recorder = RuntimeSessionRecorder(null, log) { clock++ }
        recorder.startRun(AgentRequest("q", "s", "input"), "r")
        val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")
        for (stage in 1..2) {
            val call = ModelEvent.ToolCall("prefetch:$stage", "prefetch_tools", """{"stage":$stage}""")
            recorder.recordToolStarted(context, call, DynamicToolSource.NATIVE_READ)
            recorder.recordToolFinished(context, call, DynamicToolSource.NATIVE_READ, """{"ok":true,"stage":$stage}""")
        }
        recorder.recordModelRequest(context, ModelRequest(listOf(ModelMessage("user", "input"))))
        val types = log.replay("s", "r").map { it.type }.filter {
            it in setOf(SessionEventType.TOOL_STARTED, SessionEventType.TOOL_RESULT, SessionEventType.MODEL_REQUEST)
        }
        assertEquals(listOf(SessionEventType.TOOL_STARTED, SessionEventType.TOOL_RESULT,
            SessionEventType.TOOL_STARTED, SessionEventType.TOOL_RESULT, SessionEventType.MODEL_REQUEST), types)
    }

    @Test
    fun discoveryResultIsPersistedBeforeTheFirstModelRequest() {
        val log = InMemorySessionEventLog()
        var clock = 100L
        val recorder = RuntimeSessionRecorder(null, log) { clock++ }
        recorder.startRun(AgentRequest("q", "s", "input"), "r")
        val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")
        val call = ModelEvent.ToolCall("prefetch", "prefetch_tools", """{"state":"input"}""")
        recorder.recordToolStarted(context, call, DynamicToolSource.NATIVE_READ)
        recorder.recordToolFinished(context, call, DynamicToolSource.NATIVE_READ, """{"ok":true,"selectedTools":[]}""")
        recorder.recordModelRequest(context, ModelRequest(listOf(ModelMessage("user", "input"))))
        val types = log.replay("s", "r").map { it.type }.filter {
            it in setOf(SessionEventType.TOOL_STARTED, SessionEventType.TOOL_RESULT, SessionEventType.MODEL_REQUEST)
        }
        assertEquals(listOf(SessionEventType.TOOL_STARTED, SessionEventType.TOOL_RESULT, SessionEventType.MODEL_REQUEST), types)
    }
}
