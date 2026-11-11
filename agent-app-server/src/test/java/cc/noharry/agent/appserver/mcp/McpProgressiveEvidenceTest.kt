package cc.noharry.agent.appserver.mcp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpProgressiveEvidenceTest {
    @Test
    fun rawReadFileSchemaArgumentsExposeBrowsedToolName() {
        val body = """
            runId=run-1
            toolCallId=call-1
            tool=read_file
            source=native
            arguments={"path":"/mcp/tools/command_navi_CalRoute/schema.json"}
        """.trimIndent()

        assertEquals(
            "command_navi_CalRoute",
            mcpSchemaToolFromRawToolEvent("Raw tool arguments", body),
        )
    }

    @Test
    fun nonReadFileRawEventIsIgnored() {
        val body = """
            tool=finish
            arguments={"response":"done"}
        """.trimIndent()

        assertNull(mcpSchemaToolFromRawToolEvent("Raw tool arguments", body))
    }
}
