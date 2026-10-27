@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Advisory, bounded discovery on the same catalog/activation/recording path as search_tools. */
internal suspend fun prefetchRuntimeTools(
    input: String,
    context: MaterializerContext,
    registry: ToolProviderRegistry,
    activations: ToolActivationStore,
    recorder: LoopSessionRecorder,
) {
    val catalog = registry.prefetchCatalog(context)
    if (catalog.isEmpty()) return
    val start = System.nanoTime()
    var remainingTools = MAX_PREFETCH_TOOLS
    for ((provider, tools) in catalog) {
        val remainingMs = PREFETCH_BUDGET_MS - (System.nanoTime() - start) / NANOS_PER_MILLI
        if (remainingMs <= 0 || remainingTools <= 0) break
        val selector = provider as ToolPrefetchProvider
        val local = localPrefetchTools(selector, tools, input, context, recorder, remainingTools)
        activatePrefetched(local, context, activations)
        remainingTools -= local.size
        if (!selector.remotePrefetchEnabled || selector.prefetchOnSearch || remainingTools <= 0) continue
        // Remote ranking only sees what local hints left unactivated; duplicates would spend candidate slots.
        val activated = activations.activated(context.runId).mapTo(mutableSetOf()) { it.toolName }
        val unactivated = tools.filterNot { it.qualifiedName in activated }
        if (unactivated.isEmpty()) continue
        val selected = prefetchProvider(
            selector, unactivated, input, context, recorder, registry,
            minOf(selector.prefetchTimeoutMillis.coerceAtLeast(1L),
                (PREFETCH_BUDGET_MS - (System.nanoTime() - start) / NANOS_PER_MILLI).coerceAtLeast(0L)), remainingTools,
        )
        val additional = selected.filter { remote -> local.none { it.qualifiedName == remote.qualifiedName } }
        activatePrefetched(additional, context, activations)
        remainingTools -= additional.size
    }
}

private fun activatePrefetched(tools: List<McpToolRef>, context: MaterializerContext, activations: ToolActivationStore) {
    tools.forEach { tool ->
        val schema = runCatching { tool.prefetchSchema() }.getOrNull() ?: return@forEach
        activations.activate(context.runId, tool, schema, ToolActivationMetadata(source = PREFETCH_SOURCE))
    }
}

private fun localPrefetchTools(
    selector: ToolPrefetchProvider, tools: List<McpToolRef>, input: String, context: MaterializerContext,
    recorder: LoopSessionRecorder, maxTools: Int,
): List<McpToolRef> {
    val request = ToolPrefetchRequest(input, tools.map { it.descriptor }, context.toMcpCallContext(), maxTools)
    val names = try { selector.localToolNames(request).distinct() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { return emptyList() }
    if (names.isEmpty()) return emptyList()
    val byName = tools.associateBy { it.originalToolName }
    val selected = names.mapNotNull(byName::get).filter { runCatching { it.prefetchSchema() }.isSuccess }.take(maxTools)
    val trace = PrefetchRecording(context, recorder, "${tools.first().serverId}:local")
    trace.request(buildJsonObject {
        put("kind", "local_tool_hints")
        put("input", input)
        putJsonArray("candidateNames") { names.forEach { add(JsonPrimitive(it)) } }
    }.toString())
    trace.response(buildJsonObject { put("kind", "current_catalog_selection") }.toString())
    trace.finish("OK", selected)
    return selected
}

internal suspend fun prefetchProvider(
    selector: ToolPrefetchProvider,
    tools: List<McpToolRef>,
    input: String,
    context: MaterializerContext,
    recorder: LoopSessionRecorder,
    registry: ToolProviderRegistry,
    timeoutMs: Long,
    maxTools: Int,
    discoveryId: String = "",
): List<McpToolRef> {
    val trace = PrefetchRecording(context, recorder, tools.first().serverId + discoveryId)
    return try {
        val result = withTimeoutOrNull(timeoutMs) {
            registry.selectPrefetchedTools(
                tools.first().provider,
                ToolPrefetchRequest(
                    input, tools.map { it.descriptor },
                    ToolCallContext(context.sessionId, context.runId, context.requestId), maxTools,
                ), trace,
            )
        }
        val byName = tools.associateBy { it.originalToolName }
        val selected = result.orEmpty().distinct().mapNotNull(byName::get)
            .filter { runCatching { it.prefetchSchema() }.isSuccess }.take(maxTools)
        trace.finish(if (result == null) "TIMEOUT" else "OK", selected)
        selected
    } catch (cancelled: CancellationException) {
        trace.finish("CANCELLED", emptyList())
        throw cancelled
    } catch (_: Exception) {
        trace.finish("FAILED", emptyList())
        emptyList()
    }
}

private class PrefetchRecording(
    private val context: MaterializerContext,
    private val recorder: LoopSessionRecorder,
    serverId: String,
) : ToolPrefetchTrace {
    private val callIdPrefix = "${context.runId}:prefetch:$serverId"
    private var event: ModelEvent.ToolCall? = null
    private var response: JsonElement? = null
    private var diagnostics: JsonObject? = null
    private var startedAtNanos = System.nanoTime()
    private var stage = 0

    override fun request(json: String) {
        Json.parseToJsonElement(json)
        if (event != null) {
            check(response != null) { "Complete the current prefetch response before the next request." }
            finishStage("OK", emptyList())
        }
        stage++
        startedAtNanos = System.nanoTime()
        val callId = if (stage == 1) callIdPrefix else "$callIdPrefix:$stage"
        val call = ModelEvent.ToolCall(callId, "prefetch_tools", json)
        event = call
        recorder.recordToolStarted(context, call, DynamicToolSource.NATIVE_READ)
    }

    override fun response(json: String) {
        check(event != null && response == null) { "Prefetch response requires one request." }
        response = Json.parseToJsonElement(json)
    }

    override fun diagnostics(json: String) {
        check(event != null && diagnostics == null) { "Diagnostics require one current prefetch request." }
        diagnostics = Json.parseToJsonElement(json) as JsonObject
    }

    fun finish(status: String, tools: List<McpToolRef>) {
        if (event == null) request("{}")
        finishStage(status, tools)
    }

    private fun finishStage(status: String, tools: List<McpToolRef>) {
        val call = checkNotNull(event)
        val result = buildJsonObject {
            put("ok", status == "OK")
            put("status", status)
            put("stage", stage)
            put("durationMs", (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI)
            response?.let { put("response", it) }
            diagnostics?.let { put("diagnostics", it) }
            putJsonArray("selectedTools") { tools.forEach { add(JsonPrimitive(it.qualifiedName)) } }
        }
        recorder.recordToolFinished(context, call, DynamicToolSource.NATIVE_READ, result.toString())
        event = null
        response = null
        diagnostics = null
    }
}

private fun McpToolRef.prefetchSchema(): String {
    val schema = Json.parseToJsonElement(descriptor.inputSchemaJson) as JsonObject
    return JsonObject(schema + ("description" to JsonPrimitive(descriptor.description))).toString()
}

private const val PREFETCH_SOURCE = "tool_prefetch"
private const val MAX_PREFETCH_TOOLS = 24
private const val PREFETCH_BUDGET_MS = 15_000L
private const val NANOS_PER_MILLI = 1_000_000L
