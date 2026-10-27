@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Node4LifecycleDefaultCensusTest {
    @Test
    fun legacySerializedIsGoneFromProductionAndTests() {
        assertEquals(emptyList<String>(), hits(androidModules(), """\.legacySerialized\s*\("""))
    }

    @Test
    fun agentPluginDescriptorLegacyIsGone() {
        assertEquals(emptyList<String>(), hits(androidModules(), """AgentPluginDescriptor\.legacy\b"""))
    }

    @Test
    fun productionPluginBodiesDoNotUseTwoArgSharedProvide() {
        val productionPlugins = androidModules()
            .flatMap { module ->
                File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            }
            .filter { file ->
                val text = file.readText()
                text.contains(": AgentPlugin") || text.contains("fun install(scope: AgentPluginScope)")
            }
        val twoArg = Regex("""(?:scope\.)?provide\s*\(\s*[^,\n]+,\s*[^,\n]+\)""")
        val hits = productionPlugins.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, raw ->
                val line = stripComment(raw)
                if (!twoArg.containsMatchIn(line)) return@mapIndexedNotNull null
                if (line.contains("SessionCapabilityFactory") || line.contains("concurrency")) {
                    return@mapIndexedNotNull null
                }
                "${rel(file)}:${index + 1}:$raw"
            }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun productionProvidersDeclareExplicitConcurrency() {
        val missing = mutableListOf<String>()
        androidModules().forEach { module ->
            File(module, "src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    if (isProviderImplementor(text) && !hasExplicitConcurrency(text)) {
                        missing += "${rel(file)} missing explicit providerConcurrency"
                    }
                }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun firstPartyCensusRemainsComplete() {
        val durable = DurableSessionEventLogPlugin.forRoot(
            java.nio.file.Files.createTempDirectory("census-node4-").toFile(),
        )
        assertTrue(durable.descriptor.provides.isNotEmpty())
        assertEquals(durable.descriptor.id, durable.id)
    }
}

private val PROVIDER_IFACES = listOf("ModelClient", "ToolProvider", "ExecEngine", "McpClientProvider")

private fun isProviderImplementor(text: String): Boolean {
    val stripped = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""//.*"""), " ")
        .replace(Regex("""\s+"""), " ")
    return PROVIDER_IFACES.any { iface ->
        Regex(
            """(?:class|object)(?:\s+\w[\w.]*)?\s*(?:\([^;{]*\))?\s*:\s*[^;{]*\b""" +
                Regex.escape(iface) + """\b""",
        ).containsMatchIn(stripped)
    }
}

private fun hasExplicitConcurrency(text: String): Boolean =
    text.contains("override val providerConcurrency") &&
        !text.contains("legacySerialized")

private fun androidModules(): List<File> {
    val root = File("..")
    return root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("agent-") }
        .filter { File(it, "src").isDirectory }
}

private fun hits(modules: List<File>, pattern: String): List<String> {
    val regex = Regex(pattern)
    return modules.flatMap { module ->
        module.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}build${File.separator}") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, raw ->
                    if (regex.containsMatchIn(stripComment(raw))) "${rel(file)}:${index + 1}:$raw" else null
                }
            }
    }
}

private fun stripComment(line: String): String {
    val index = line.indexOf("//")
    return if (index < 0) line else line.substring(0, index)
}

private fun rel(file: File): String = file.path.replace('\\', '/')
