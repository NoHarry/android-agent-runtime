@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.exec.js

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecToolBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZiplineExecEngineInstrumentedTest {
    @Test
    fun evaluatesModelGeneratedJavaScriptAndEmitsBoundedOutput() = runBlocking {
        val bridge = RecordingBridge()
        val result = ZiplineExecEngine().run(
            ExecRequest("run-1", """emit("abcdef"); 42""", timeoutMillis = 1_000, outputTokenBudget = 1),
            bridge,
        )

        assertTrue(result.toString(), result.ok)
        assertEquals("abcd", result.output)
        assertEquals(listOf("abcd"), bridge.output)
    }

    @Test
    fun hostToolCallsUseRuntimeBridge() = runBlocking {
        val bridge = RecordingBridge("""{"ok":true,"value":7}""")
        val result = ZiplineExecEngine().run(
            ExecRequest(
                "run-1",
                """emit(tools.call("read_file", "{\"path\":\"/demo.txt\"}"))""",
                timeoutMillis = 1_000,
                outputTokenBudget = 64,
            ),
            bridge,
        )

        assertTrue(result.toString(), result.ok)
        assertEquals("""{"ok":true,"value":7}""", result.output)
        assertEquals(listOf("read_file" to """{"path":"/demo.txt"}"""), bridge.calls)
    }

    @Test
    fun timeoutInterruptsLongRunningJavaScript() = runBlocking {
        val result = ZiplineExecEngine().run(
            ExecRequest("run-1", """while (true) {}""", timeoutMillis = 1, outputTokenBudget = 64),
            RecordingBridge(),
        )

        assertFalse(result.ok)
        assertEquals(RuntimeErrorCode.EXEC_TIMEOUT.wireCode, result.errorCode)
    }

    @Test
    fun javaClassSideChannelIsDenied() = runBlocking {
        val result = ZiplineExecEngine().run(
            ExecRequest("run-1", """java.lang.System.getProperty("user.home")""", timeoutMillis = 1_000, outputTokenBudget = 64),
            RecordingBridge(),
        )

        assertFalse(result.ok)
        assertEquals(RuntimeErrorCode.EXEC_FAILED.wireCode, result.errorCode)
    }
}

private class RecordingBridge(
    private val toolResult: String = "{}",
) : ExecToolBridge {
    val calls = mutableListOf<Pair<String, String>>()
    val output = mutableListOf<String>()

    override suspend fun callTool(name: String, argumentsJson: String): String {
        calls += name to argumentsJson
        return toolResult
    }

    override suspend fun emitOutput(text: String) {
        output += text
    }
}
