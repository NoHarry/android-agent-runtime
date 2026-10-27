package cc.noharry.agent.kernel.core.loop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CorePackageBoundaryTest {
    @Test
    fun coreProductionSourcesHaveZeroForbiddenImports() {
        val root = File("src/main/kotlin/cc/noharry/agent/kernel/core")
        assertTrue("core production root must exist: ${root.absolutePath}", root.isDirectory)
        val forbidden = listOf(
            "cc.noharry.agent.runtime",
            "cc.noharry.agent.vfs",
            "cc.noharry.agent.plugin",
            "okhttp3.",
            "android.",
            "androidx.",
        )
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> matchingLines(file) { line -> forbidden.any { token -> line.contains(token) } } }
            .toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun coreModuleDependsOnlyOnKernelApiProject() {
        val buildFile = File("build.gradle.kts")
        assertTrue(buildFile.isFile)
        val text = buildFile.readText()
        val projects = Regex("""project\((\":[^\"]+\")\)""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("\":agent-kernel-api\""), projects)
        assertFalse(text.contains("com.android"))
        assertFalse(text.contains("okhttp"))
    }

    @Test
    fun movedTypesLiveOnlyUnderKernelCoreSourcePath() {
        val coreRoot = File("src/main/kotlin/cc/noharry/agent/kernel/core")
        val runtimeRoot = File("../agent-runtime/src/main/kotlin/cc/noharry/agent/runtime")
        assertTrue(coreRoot.isDirectory)
        assertTrue(runtimeRoot.isDirectory)
        val movedFiles = listOf(
            "PluginRegistry.kt",
            "PluginInstaller.kt",
            "ProfileCompiler.kt",
            "RuntimeBlueprint.kt",
            "SessionRunScheduler.kt",
            "RunStateController.kt",
            "ReliabilityStorage.kt",
            "HistoryCompactor.kt",
            "ToolProviderRegistry.kt",
            "NativeLoopOutcome.kt",
            "McpCatalogModels.kt",
            "NativeToolLoop.kt",
            "NativeRoundRunner.kt",
            "NativeToolUseController.kt",
            "NativeToolLoopSupport.kt",
            "NativeReadOnlyToolBatch.kt",
            "MemoryCandidateExtractor.kt",
            "RuntimeMemoryRetriever.kt",
            "RuntimeMemoryHints.kt",
            "RuntimeMemoryPolicy.kt",
            "BootstrapPromptProvider.kt",
        )
        val coreFiles = coreRoot.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        val missing = movedFiles.filter { it !in coreFiles }
        assertEquals(emptyList<String>(), missing)
        val leftover = movedFiles.filter { File(runtimeRoot, it).exists() }
        assertEquals(emptyList<String>(), leftover)
    }

    @Test
    fun runtimeProductionHasNoDuplicateMovedTypeDeclarations() {
        val runtimeRoot = File("../agent-runtime/src/main/kotlin")
        val movedTypes = listOf(
            "class PluginRegistry",
            "class PluginInstaller",
            "class ProfileCompiler",
            "class RuntimeBlueprint",
            "class SessionRunScheduler",
            "class RunStateController",
            "interface ReliabilityStorage",
            "class HistoryCompactor",
            "class ToolProviderRegistry",
            "sealed interface NativeLoopOutcome",
            "data class McpCatalogSnapshot",
            "class NativeToolLoop",
            "class NativeRoundRunner",
            "class NativeToolUseController",
        )
        val hits = runtimeRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/build/" !in it.path.replace('\\', '/') }
            .flatMap { file ->
                matchingLines(file) { line ->
                    movedTypes.any { token ->
                        val trimmed = line.trim()
                        trimmed == token || trimmed.startsWith("$token ") || trimmed.startsWith("$token(")
                    }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), hits)
    }
}

private fun matchingLines(file: File, predicate: (String) -> Boolean): List<String> {
    val relative = file.path.replace('\\', '/')
    return file.readLines().mapIndexedNotNull { index, raw ->
        if (predicate(raw)) "$relative:${index + 1}:$raw" else null
    }
}
