package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A [ToolProvider] made of plain Kotlin functions, for tools an app has in its own code:
 *
 * ```
 * val clock = functionTools("clock") {
 *     tool("now", "The current local time.", readOnly = true) { LocalTime.now().toString() }
 *     tool(
 *         "add", "Adds two numbers.",
 *         inputSchema = """{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}""",
 *     ) { args -> (args.getValue("a").jsonPrimitive.double + args.getValue("b").jsonPrimitive.double).toString() }
 * }
 * ```
 *
 * A handler gets the call's arguments and returns the result: JSON (an object or an array) as it is, anything else as
 * text. A handler that throws fails that one call, and the model sees why. Calls are made one at a time.
 * Candidate API, not frozen.
 */
fun functionTools(
    id: String,
    displayName: String = id,
    tools: FunctionToolsBuilder.() -> Unit,
): ToolProvider = FunctionToolProvider(id, displayName, FunctionToolsBuilder().apply(tools).build())

/** Collects the tools of [functionTools]. Candidate API, not frozen. */
class FunctionToolsBuilder internal constructor() {
    private val tools = linkedMapOf<String, FunctionTool>()

    /**
     * Adds the tool [name]: [description] tells the model when to use it, [inputSchema] is the JSON Schema of its
     * arguments (any object by default), and [readOnly] says it changes nothing, so a run may call it freely.
     */
    fun tool(
        name: String,
        description: String,
        inputSchema: String = OPEN_OBJECT_SCHEMA,
        readOnly: Boolean = false,
        handler: suspend (arguments: JsonObject) -> String,
    ) {
        require(name.isNotBlank()) { "A tool needs a name." }
        require(name !in tools) { "Tool $name is defined twice." }
        val descriptor = ToolDescriptor(
            name = name,
            description = description,
            inputSchemaJson = inputSchema,
            title = name,
            effectKind = if (readOnly) READ_ONLY_EFFECT else UNKNOWN_EFFECT,
        )
        tools[name] = FunctionTool(descriptor, handler)
    }

    internal fun build(): Map<String, FunctionTool> = tools.toMap()
}

internal class FunctionTool(val descriptor: ToolDescriptor, val handler: suspend (JsonObject) -> String)

private class FunctionToolProvider(
    override val providerId: String,
    override val displayName: String,
    private val tools: Map<String, FunctionTool>,
) : ToolProvider {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = tools.values.map { it.descriptor }

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val tool = tools[toolName] ?: return failure(toolName, "UNKNOWN_TOOL", "No tool named $toolName.")
        val arguments = parseObject(argumentsJson)
            ?: return failure(toolName, "INVALID_ARGUMENTS", "Tool arguments must be a JSON object.")
        val output = try {
            tool.handler(arguments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(toolName, "TOOL_FAILED", error.message ?: error.javaClass.simpleName)
        }
        return ToolCallResult(success = true, resultJson = resultJson(output))
    }
}

/** [output] as the call's result: JSON as it is, other text wrapped as `{"text": ...}`. */
private fun resultJson(output: String): String {
    val parsed = parseElement(output.trim())
    return if (parsed is JsonObject || parsed is JsonArray) output.trim() else buildJsonObject { put("text", JsonPrimitive(output)) }.toString()
}

private fun failure(tool: String, code: String, message: String): ToolCallResult = ToolCallResult(
    success = false,
    resultJson = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("code", JsonPrimitive(code))
        put("tool", JsonPrimitive(tool))
        put("message", JsonPrimitive(message))
    }.toString(),
)

private fun parseObject(json: String): JsonObject? = parseElement(json.ifBlank { "{}" }) as? JsonObject

private fun parseElement(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private const val OPEN_OBJECT_SCHEMA = """{"type":"object","additionalProperties":true}"""
private const val READ_ONLY_EFFECT = "READ_ONLY"
private const val UNKNOWN_EFFECT = "UNKNOWN"
