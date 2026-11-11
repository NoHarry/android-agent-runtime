@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.RuntimeDiagnostics
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.TurnResult
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.ToolUseResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIdentityRoutingTest {
    @Test fun hookProgressUsesThePublicTurnChannelInsteadOfTheExecutionUuid() = runBlocking {
        val turn = UUID.randomUUID().toString()
        val execution = UUID.randomUUID().toString()
        val visible = ExecutionEvents.subscribe(turn)
        val internal = ExecutionEvents.subscribe(execution)
        try {
            val hook = ExecutionToolHook()
            val context = HookContext("session", execution, turn)
            val call = ToolUse("read_file", "{}", ToolSource.NATIVE_READ, "call")
            hook.onPreToolUse(call, context)
            hook.onPostToolUse(call, ToolUseResult(true, "{}"), context)
            assertEquals(ExecutionMessage.ToolStatus("read_file", "call", "started"), visible.events.tryReceive().getOrNull())
            assertEquals(ExecutionMessage.ToolStatus("read_file", "call", "completed"), visible.events.tryReceive().getOrNull())
            assertTrue(internal.events.tryReceive().isFailure)
        } finally { visible.close(); internal.close() }
    }

    @Test fun terminalOutcomeLookupIncludesTheSessionForIdenticalTurnIds() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val delegate = object : RuntimeMultiSessionRuntimeStub() {}
        val runtime = object : AgentRuntime by delegate {
            override val diagnostics = object : RuntimeDiagnostics by delegate.diagnostics {
                override suspend fun getRuntimeResult(threadId: String, turnId: String) =
                    completedRuntimeResult(turnId).also { calls += threadId to turnId }
            }
        }
        val event = ThreadEvent.TurnCompleted(TurnResult("same-turn", TurnStatus.COMPLETED, "done", emptyList()))
        assertEquals(event, runtime.hostEvent(event, "first-session"))
        assertEquals(event, runtime.hostEvent(event, "second-session"))
        assertEquals(listOf("first-session" to "same-turn", "second-session" to "same-turn"), calls)
    }
}
