package cc.noharry.agent.kernel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApiPackageImportBoundaryTest {
    @Test
    fun apiProductionSourcesHaveZeroForbiddenImports() {
        val root = File("src/main/kotlin/cc/noharry/agent/kernel/api")
        assertTrue("api production root must exist: ${root.absolutePath}", root.isDirectory)

        val forbidden = listOf(
            "cc.noharry.agent.runtime",
            "cc.noharry.agent.vfs",
            "cc.noharry.agent.kernel.core",
            "okhttp3.",
            "android.",
            "androidx.",
            "cc.noharry.agent." + "api",
            "kotlinx.serialization.json.JsonObject",
        )
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                matchingLines(file) { line -> forbidden.any { token -> line.contains(token) } }
            }
            .toList()

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun agentCapabilitiesDoesNotImportMcp() {
        val file = File("src/main/kotlin/cc/noharry/agent/kernel/api/AgentCapabilities.kt")
        assertTrue("AgentCapabilities.kt must exist: ${file.absolutePath}", file.isFile)
        assertEquals(emptyList<String>(), matchingLines(file) { it.contains("cc.noharry.agent.mcp") })
    }

    @Test
    fun kernelApiModuleHasZeroProjectDependencies() {
        val buildFile = File("build.gradle.kts")
        assertTrue(buildFile.isFile)
        val text = buildFile.readText()
        assertFalse(text.contains("project("))
        assertFalse(text.contains("project :"))
    }

    @Test
    fun oldApiPackageAndRuntimeAliasesAreAbsent() {
        val androidRoot = File("..")
        val oldApi = File(androidRoot, "agent-runtime/src/main/kotlin/cc/noharry/agent/api")
        assertFalse("old api package must be deleted: ${oldApi.absolutePath}", oldApi.exists())
        assertFalse(File(androidRoot, "agent-runtime/src/main/kotlin/cc/noharry/agent/runtime/RuntimeExec.kt").exists())
        assertFalse(File(androidRoot, "agent-runtime/src/main/kotlin/cc/noharry/agent/runtime/RuntimeHooks.kt").exists())
        assertFalse(File(androidRoot, "agent-runtime/src/main/kotlin/cc/noharry/agent/reliability/RuntimeRecords.kt").exists())
        assertFalse(File(androidRoot, "agent-runtime/src/main/kotlin/cc/noharry/agent/reliability/RuntimeErrorCode.kt").exists())

        val oldPackage = "cc.noharry.agent." + "api"
        val leftover = androidRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { path ->
                val normalized = path.path.replace('\\', '/')
                "/src/" in normalized && "/build/" !in normalized
            }
            .flatMap { file ->
                matchingLines(file) { line ->
                    oldPackage in line && "cc.noharry.agent.kernel.api" !in line
                }
            }
            .toList()
        assertEquals(emptyList<String>(), leftover)
    }
}

private fun matchingLines(file: File, predicate: (String) -> Boolean): List<String> {
    val relative = file.path.replace('\\', '/')
    return file.readLines().mapIndexedNotNull { index, raw ->
        if (predicate(raw)) "$relative:${index + 1}:$raw" else null
    }
}
