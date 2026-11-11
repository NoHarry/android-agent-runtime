@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A package's script tool with its script's text. */
internal class ScriptTool(val tool: ManifestTool, val script: String)

/**
 * A package's script tools in one run, as one tool server named for the package ([providerId]): each described with
 * the package's input schema, as read-only only when it says so and its publisher is [trusted] (so the gate lets it
 * run unasked), and called through [runner]. One call at a time, as a provider is by default.
 */
internal class ScriptToolProvider(
    override val providerId: String,
    override val displayName: String,
    private val tools: List<ScriptTool>,
    private val trusted: Boolean,
    private val runner: ScriptRunner,
) : ToolProvider {
    /** One script at a time, the runtime's default. */
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = tools.map { it.tool }.map { tool ->
        ToolDescriptor(
            name = tool.name,
            description = tool.description,
            inputSchemaJson = tool.inputSchema.toString(),
            effectKind = if (scriptToolAsks(tool, trusted)) RuntimeEffectKind.UNKNOWN.name else RuntimeEffectKind.READ_ONLY.name,
        )
    }

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val found = tools.firstOrNull { it.tool.name == toolName }
            ?: return ToolCallResult(success = false, resultJson = JsonObject(mapOf("error" to JsonPrimitive("no tool $toolName"))).toString())
        return runner.call(found.tool, found.script, argumentsJson, context)
    }
}
