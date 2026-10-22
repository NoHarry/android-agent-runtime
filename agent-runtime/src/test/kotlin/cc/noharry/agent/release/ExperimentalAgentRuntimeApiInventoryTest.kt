package cc.noharry.agent.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExperimentalAgentRuntimeApiInventoryTest {
    @Test
    fun experimentalAnnotationRequiresExplicitOptInAtErrorLevel() {
        val source = sourceText("api/ExperimentalAgentRuntimeApi.kt")

        assertTrue(source.contains("@RequiresOptIn("))
        assertTrue(source.contains("level = RequiresOptIn.Level.ERROR"))
    }

    @Test
    fun hookExtensionSurfaceIsMarkedExperimental() {
        val runtimeSource = sourceText("api/RuntimeHooks.kt")
        val builderSource = sourceText("runtime/AgentRuntimeBuilder.kt")

        assertEquals(
            listOf(
                "AgentRuntimeBuilder.hook",
                "AgentRuntimeBuilder.hookTimeoutMillis",
                "HookDecision",
                "HookFailurePolicy",
                "RuntimeHook",
                "RuntimeHookRegistration",
                "ToolMatcher",
            ),
            annotatedNames(
                "AgentRuntimeBuilder.hook" to (builderSource to "fun hook("),
                "AgentRuntimeBuilder.hookTimeoutMillis" to (builderSource to "fun hookTimeoutMillis("),
                "HookDecision" to (runtimeSource to "sealed interface HookDecision"),
                "HookFailurePolicy" to (runtimeSource to "enum class HookFailurePolicy"),
                "RuntimeHook" to (runtimeSource to "interface RuntimeHook"),
                "RuntimeHookRegistration" to (runtimeSource to "data class RuntimeHookRegistration"),
                "ToolMatcher" to (runtimeSource to "fun interface ToolMatcher"),
            ),
        )
    }

    @Test
    fun node4CanonicalSurfaceIsMarkedExperimental() {
        val builderSource = sourceText("runtime/AgentRuntimeBuilder.kt")
        val sources = NODE4_EXPERIMENTAL_SOURCE_PATHS.joinToString("\n", transform = ::sourceText)

        assertEquals(
            (NODE4_EXPERIMENTAL_DECLARATIONS.keys + "AgentRuntimeBuilder.profile").sorted(),
            annotatedNames(
                *NODE4_EXPERIMENTAL_DECLARATIONS.map { (name, declaration) ->
                    name to (sources to declaration)
                }.toTypedArray(),
                "AgentRuntimeBuilder.profile" to (builderSource to "fun profile("),
            ),
        )
    }
}

private val NODE4_EXPERIMENTAL_SOURCE_PATHS = listOf(
    "api/AgentCapabilities.kt",
    "api/AgentPlugin.kt",
    "api/AgentPluginDescriptor.kt",
    "api/AgentProfile.kt",
    "api/KernelApiRange.kt",
    "api/KernelApiVersion.kt",
    "api/KernelApiVersions.kt",
    "api/PluginVersion.kt",
    "api/SessionCapabilityContext.kt",
    "api/SessionCapabilityFactory.kt",
    "api/RuntimeExec.kt",
)

private val NODE4_EXPERIMENTAL_DECLARATIONS = linkedMapOf(
    "AgentCapabilities" to "object AgentCapabilities",
    "AgentPlugin" to "interface AgentPlugin",
    "AgentPluginDescriptor" to "class AgentPluginDescriptor",
    "AgentProfile" to "class AgentProfile",
    "ExecEngine" to "interface ExecEngine",
    "ExecRequest" to "data class ExecRequest",
    "ExecResult" to "data class ExecResult",
    "ExecToolBridge" to "interface ExecToolBridge",
    "KernelApiRange" to "class KernelApiRange",
    "KernelApiVersion" to "class KernelApiVersion",
    "KernelApiVersions" to "object KernelApiVersions",
    "PluginVersion" to "class PluginVersion",
    "SessionCapabilityContext" to "class SessionCapabilityContext",
    "SessionCapabilityFactory" to "fun interface SessionCapabilityFactory",
)

private fun annotatedNames(vararg checks: Pair<String, Pair<String, String>>): List<String> =
    checks.mapNotNull { (name, sourceAndDeclaration) ->
        val (source, declaration) = sourceAndDeclaration
        name.takeIf { source.isAnnotated("@ExperimentalAgentRuntimeApi", declaration) }
    }.sorted()

private fun sourceText(relativePath: String): String {
    val runtime = File("src/main/kotlin/cc/noharry/agent/$relativePath")
    if (runtime.isFile) return runtime.readText()
    val kernel = File("../agent-kernel-api/src/main/kotlin/cc/noharry/agent/kernel/$relativePath")
    return kernel.readText()
}

private fun String.isAnnotated(annotation: String, declaration: String): Boolean {
    val declarationIndex = indexOf(declaration)
    if (declarationIndex < 0) return false
    return substring(0, declarationIndex).takeLast(160).contains(annotation)
}
