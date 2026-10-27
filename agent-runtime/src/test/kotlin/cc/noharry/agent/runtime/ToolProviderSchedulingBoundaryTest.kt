package cc.noharry.agent.runtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolProviderSchedulingBoundaryTest {
    @Test
    fun canonicalSchedulingFilesDoNotConsumeMcpClientProvider() {
        val hits = CANONICAL_SCHEDULING_FILES.flatMap { (name, directory) ->
            val file = File(directory, name)
            assertTrue("$name must exist: ${file.absolutePath}", file.isFile)
            matchingLines(file) { it.contains("McpClientProvider") }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun exactlyOneToolProviderRegistryAndNoPlanBackendClass() {
        val sources = productionSources()
        assertEquals(
            listOf("ToolProviderRegistry.kt"),
            classDeclarations(sources, "ToolProviderRegistry").map { it.name },
        )
        assertEquals(emptyList<String>(), classDeclarations(sources, "ToolProviderPlanBackend").map(::rel))
        assertEquals(emptyList<String>(), classDeclarations(sources, "McpPluginRegistry").map(::rel))
        assertEquals(emptyList<String>(), classDeclarations(sources, "McpPlanToolBackend").map(::rel))
    }
}

private const val CORE_MAIN = "../agent-kernel-core/src/main/kotlin/cc/noharry/agent/kernel/core"
private const val RUNTIME_MAIN = "src/main/kotlin/cc/noharry/agent/runtime"

private val CANONICAL_SCHEDULING_FILES = listOf(
    "state/SessionRunScheduler.kt" to CORE_MAIN,
    "state/SessionRunSchedulerContracts.kt" to CORE_MAIN,
    "tools/ProviderConcurrencyLimiter.kt" to CORE_MAIN,
    "plugin/PluginRegistry.kt" to CORE_MAIN,
    "plugin/ProfileCompiler.kt" to CORE_MAIN,
    "plugin/RuntimeBlueprint.kt" to CORE_MAIN,
    "tools/ToolProviderRegistry.kt" to CORE_MAIN,
    "run/RuntimeProfileAdapter.kt" to RUNTIME_MAIN,
    "DefaultAgentRuntime.kt" to RUNTIME_MAIN,
    "run/NativeLoopFactory.kt" to RUNTIME_MAIN,
)

private fun productionSources(): List<File> {
    val roots = listOf(File(CORE_MAIN), File(RUNTIME_MAIN))
    roots.forEach { root ->
        assertTrue("production root must exist: ${root.absolutePath}", root.isDirectory)
    }
    return roots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}

private fun classDeclarations(files: List<File>, name: String): List<File> {
    val pattern = Regex("""\b(?:class|typealias)\s+${Regex.escape(name)}\b""")
    return files.filter { file -> file.readLines().any(pattern::containsMatchIn) }
}

private fun matchingLines(file: File, predicate: (String) -> Boolean): List<String> {
    val relative = rel(file)
    return file.readLines().mapIndexedNotNull { index, raw ->
        if (predicate(raw)) "$relative:${index + 1}:$raw" else null
    }
}

private fun rel(file: File): String = file.path.replace('\\', '/')
