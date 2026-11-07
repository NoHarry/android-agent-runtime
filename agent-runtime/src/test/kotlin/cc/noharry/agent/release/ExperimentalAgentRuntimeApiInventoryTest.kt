package cc.noharry.agent.release

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalAgentRuntimeApiInventoryTest {
    @Test
    fun experimentalAnnotationRequiresExplicitOptInAtErrorLevel() {
        val source = apiSource("ExperimentalAgentRuntimeApi.kt").readText()
        assertTrue(source.contains("@RequiresOptIn("))
        assertTrue(source.contains("level = RequiresOptIn.Level.ERROR"))
    }

    @Test
    fun stableExtensionContractsDoNotRequireExperimentalOptIn() {
        val contracts = listOf("RuntimeHooks.kt", "RuntimeExec.kt", "ToolProvider.kt", "SessionEventLog.kt", "model/ModelClient.kt")
        contracts.forEach { name ->
            assertFalse("$name must be stable", apiSource(name).readText().contains("@ExperimentalAgentRuntimeApi\n"))
        }
    }

    @Test
    fun pluginCompositionAndOldRunContractsAreInternal() {
        val names = listOf("AgentCapabilities", "AgentPlugin", "AgentPluginDescriptor", "AgentProfile", "AgentRuntime")
        names.forEach { name ->
            assertFalse("$name must not be in the contracts module", apiSource("$name.kt").exists())
            val source = File("src/main/kotlin/cc/noharry/agent/spi/$name.kt").readText()
            assertTrue("$name must be internal", Regex("internal (?:class|interface|object) $name\\b").containsMatchIn(source))
        }
    }

    @Test
    fun diagnosticsRemainExperimental() {
        val diagnostics = File("../agent-api/src/main/kotlin/cc/noharry/agent/RuntimeDiagnostics.kt").readText()
        assertTrue(diagnostics.contains("@ExperimentalAgentRuntimeApi"))
        assertTrue(apiSource("RuntimeContextSpi.kt").readText().contains("@ExperimentalAgentRuntimeApi"))
    }
}

private fun apiSource(name: String): File = File("../agent-api/src/main/kotlin/cc/noharry/agent/spi/$name")
