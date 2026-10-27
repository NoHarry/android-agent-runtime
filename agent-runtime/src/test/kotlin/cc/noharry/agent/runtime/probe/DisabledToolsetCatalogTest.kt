package cc.noharry.agent.runtime.probe

import cc.noharry.agent.kernel.core.tools.DynamicToolRegistry
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import org.junit.Assert.assertEquals
import org.junit.Test

class DisabledToolsetCatalogTest {
    @Test
    fun probeContainsExactlyTheAdvertisedNativeTools() {
        val store = ToolActivationStore()
        for (interactionDisabled in listOf(false, true)) {
            val disabled = mutableSetOf("read_file", "exec_js", "ask", "web_fetch")
            if (interactionDisabled) disabled += "ask_async"
            val registry = DynamicToolRegistry(store, true, true, disabled)
            val probe = snapshotAdvertisedToolset("run", store, true, true, disabled)
            disabled.clear()
            assertEquals(
                registry.tools(MaterializerContext(runId = "run")).modelToolNames(),
                probe.map { it.qualifiedName }.toSet(),
            )
        }
    }
}
