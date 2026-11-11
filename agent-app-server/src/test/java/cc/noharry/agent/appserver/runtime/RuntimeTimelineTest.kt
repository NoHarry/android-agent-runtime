package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTimelineTest {
    @Test
    fun intakeSummaryContainsOnlyRealRequestFields() {
        val request = RuntimeTurnRequest(
            requestId = "req-1",
            sessionId = "sess-1",
            input = "把空调调到24度",
            scene = "debug-console",
        )
        val workspace = File("runtime-debug-workspace")

        val summary = runtimeIntakeSummary(request, ModelProvider.OPENAI, "stream", workspace)

        // Real fields from the request.
        assertTrue(summary.contains("requestId=req-1"))
        assertTrue(summary.contains("sessionId=sess-1"))
        assertTrue(summary.contains("scene=debug-console"))
        assertTrue(summary.contains("inputPreview=把空调调到24度"))
        assertTrue(summary.contains("provider=OpenAI"))
        assertTrue(summary.contains("mode=stream"))
        // No hardcoded routing preview should leak into intake.
        assertFalse(summary.contains("capability-routing-preview"))
        assertFalse(summary.contains("candidateTools"))
    }

    @Test
    fun parseRawToolNameExtractsToolLine() {
        val body = """
            runId=run-1
            tool=command_ac_TemperatureAdjust
            source=BACKEND
            arguments={"value":24}
        """.trimIndent()

        assertEquals("command_ac_TemperatureAdjust", parseRawToolName(body))
    }

    @Test
    fun parseRawToolNameFallsBackForMissingToolLine() {
        assertEquals("unknown", parseRawToolName("no tool line here"))
    }

    private fun assertEquals(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
