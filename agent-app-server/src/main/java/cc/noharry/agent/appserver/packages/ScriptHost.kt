@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.tools.isCallFailure
import cc.noharry.agent.runtime.RuntimeInspection
import cc.noharry.agent.spi.ExecToolBridge
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The engine's bridge for one call of a script tool: each host call the script makes, `host.http` or `host.tool`,
 * done within what is left of the call's time ([deadlineNanos]) and answered as `{value}`, or as `{error}` when it is
 * refused or fails, which the script reads as an error it can catch. It never throws but for cancellation (and running
 * out of memory), so nothing a script asks for can end the engine's run another way.
 */
internal class ScriptBridge(
    private val http: ScriptHttp,
    private val tools: ScriptHostTools,
    private val deadlineNanos: Long,
    private val context: ToolCallContext,
) : ExecToolBridge {
    override suspend fun callTool(name: String, argumentsJson: String): String {
        val answer = try {
            val request = try {
                Json.parseToJsonElement(argumentsJson) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: refused("$name: the request is not an object")
            val left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (left <= 0) refused("$name: the tool's time ran out")
            val value = when (name) {
                HOST_HTTP -> http.send(request, left)
                HOST_TOOL -> tools.call(request, left, context)
                else -> refused("$name is not a host function (host.http, host.tool, host.log)")
            }
            JsonObject(mapOf(KEY_VALUE to value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refusal: ScriptRefusal) {
            JsonObject(mapOf(KEY_ERROR to JsonPrimitive(refusal.message)))
        } catch (failed: Throwable) {
            // A host tool's Error too: the script reads it as an error it can catch.
            if (!failed.isCallFailure()) throw failed
            JsonObject(mapOf(KEY_ERROR to JsonPrimitive("$name failed (${failed.javaClass.simpleName})")))
        }
        return answer.toString()
    }

    override suspend fun emitOutput(text: String) = Unit
}

/**
 * `host.tool {name, arguments}`: one of the run's tools that the package named in `hostTools` and that says it only
 * reads (`effectKind` READ_ONLY), called directly on its provider (no approval: it only reads). [providers] are the
 * run's backend tool providers a plugin's tools are given, less the plugins' own MCP servers; a tool is found by its
 * whole name in the run (`mcp__<server>__<tool>`) or, when only one has it, by its own name.
 */
internal class ScriptHostTools(private val allowed: Set<String>, private val providers: List<ToolProvider>) {
    private val listing = Mutex()
    private var listed: List<Pair<ToolProvider, ToolDescriptor>>? = null

    suspend fun call(request: JsonObject, leftMillis: Long, context: ToolCallContext): JsonElement {
        (request.keys - setOf(KEY_NAME, KEY_ARGUMENTS)).firstOrNull()?.let { refused("host.tool: $it is not a member of a call") }
        val name = (request[KEY_NAME] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refused("host.tool: no name")
        if (name !in allowed) refused("host.tool: $name is not among the host tools this package asked for")
        val (provider, tool) = find(name)
        if (tool.effectKind != RuntimeEffectKind.READ_ONLY.name) refused("host.tool: $name does not say it only reads; scripts call only tools that read")
        val arguments = request[KEY_ARGUMENTS] ?: JsonObject(emptyMap())
        if (arguments !is JsonObject) refused("host.tool: arguments is not an object")
        val result = withTimeoutOrNull(leftMillis) { provider.callTool(tool.name, arguments.toString(), context) }
            ?: refused("host.tool: $name did not answer in time")
        if (!result.success) refused("host.tool: $name failed: ${result.resultJson.take(MAX_FAILURE_CHARS)}")
        if (result.resultJson.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) refused("host.tool: $name answered over ${MAX_RESULT_BYTES / KIB} KiB")
        return try {
            Json.parseToJsonElement(result.resultJson)
        } catch (_: SerializationException) {
            JsonPrimitive(result.resultJson)
        }
    }

    private suspend fun find(name: String): Pair<ToolProvider, ToolDescriptor> {
        val all = listing.withLock {
            listed ?: providers.flatMap { provider ->
                listedTools(provider).map { provider to it }
            }.also { listed = it }
        }
        all.firstOrNull { (provider, tool) -> RuntimeInspection.qualifiedToolName(provider.providerId, tool.name) == name }?.let { return it }
        val named = all.filter { it.second.name == name }
        return when (named.size) {
            1 -> named.single()
            0 -> refused("host.tool: this run has no tool $name")
            else -> refused("host.tool: more than one tool is named $name; name it as mcp__<server>__$name")
        }
    }
}

/** [provider]'s tools; none when listing them fails. */
private suspend fun listedTools(provider: ToolProvider): List<ToolDescriptor> = try {
    provider.listTools()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failed: Throwable) {
    if (!failed.isCallFailure()) throw failed
    emptyList()
}

internal const val HOST_HTTP = "host.http"
internal const val HOST_TOOL = "host.tool"
private const val KEY_VALUE = "value"
private const val KEY_ERROR = "error"
private const val KEY_NAME = "name"
private const val KEY_ARGUMENTS = "arguments"
private const val KIB = 1024
private const val MAX_RESULT_BYTES = 256 * KIB
private const val MAX_FAILURE_CHARS = 500
