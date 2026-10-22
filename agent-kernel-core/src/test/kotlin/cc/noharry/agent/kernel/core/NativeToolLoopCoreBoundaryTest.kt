@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeToolLoopCoreBoundaryTest {
    @Test
    fun missingModelClientIsTerminalWithoutRuntimeTypes() = runBlocking {
        val outcome = NativeToolLoop(
            modelClient = null,
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
        ).run(AgentRequest(input = "hello", sessionId = "s", requestId = "r"), runId = "run-1")
        val terminal = outcome as NativeLoopOutcome.Terminal
        assertEquals(RuntimeRunStatus.FAILED, terminal.result.status)
        assertEquals(RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode, terminal.result.errorCode)
        assertEquals(MODEL_CLIENT_NOT_CONFIGURED_MESSAGE, terminal.result.response)
    }

    @Test
    fun protocolSourcesLiveOnlyInCore() {
        val coreRoot = File("src/main/kotlin/cc/noharry/agent/kernel/core")
        val runtimeRoot = File("../agent-runtime/src/main/kotlin")
        listOf(
            "NativeToolLoop.kt",
            "NativeRoundRunner.kt",
            "NativeToolUseController.kt",
        ).forEach { name ->
            assertTrue(File(coreRoot, name).isFile)
            assertTrue(!File(runtimeRoot, "cc/noharry/agent/runtime/$name").exists())
        }
    }
}
