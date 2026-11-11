package cc.noharry.agent.appserver.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How backend tools reach the model: through call_tool, or loaded into its tool list by a service's own updates. */
class PromptToolModesTest {
    @Test fun thePromptTellsTheModelHowBackendToolsArriveInEachMode() {
        val callTool = appSystemPrompt("")
        val native = appSystemPrompt("", nativeTools = true)
        assertTrue(callTool.contains("via call_tool(name, arguments)"))
        assertFalse(native.contains("call_tool"))
        assertTrue(native.contains("loaded into your tool list right after the result; call them directly"))
        assertTrue(native.toByteArray().size <= callTool.toByteArray().size)
    }
}
