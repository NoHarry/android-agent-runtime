@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.loop.INTERACTION_TOOLS
import cc.noharry.agent.kernel.core.loop.RuntimeActionDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

class DynamicToolRegistry(
    private val activationStore: ToolActivationStore,
    private val nativeNetworkEnabled: Boolean = true,
    private val nativeExecEnabled: Boolean = true,
    disabledNativeTools: Set<String> = emptySet(),
    /** How activated backend tools reach the model (see [BackendToolExposure]). */
    private val exposure: BackendToolExposure = BackendToolExposure.DIRECT,
    private val nativeSubagentsEnabled: Boolean = false,
    /** Offers web_search, which the network port runs with the host's search service. */
    private val nativeWebSearchEnabled: Boolean = false,
    /** The host's kinds of subagent, offered as spawn_agent's `agent`. */
    subagentTypes: List<NativeSubagentType> = emptyList(),
) {
    private val subagentTools = nativeSubagentTools(subagentTypes)

    private val disabledTools = snapshotDisabledNativeTools(disabledNativeTools)

    fun tools(context: MaterializerContext): DynamicToolSet {
        val entries = mutableListOf<DynamicToolEntry>()
        val warnings = linkedSetOf<String>()
        val failures = linkedSetOf<String>()
        // Native tools first, then backend tools in the order they were activated: a tool found later is appended,
        // so each round's (and, with session activations, each run's) tool list extends the previous one and the
        // provider's prompt cache over it survives.
        NATIVE_READ_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.NATIVE_READ) }
        NATIVE_WRITE_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.NATIVE_WRITE) }
        if (nativeNetworkEnabled) {
            NATIVE_NETWORK_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.NETWORK) }
        }
        if (nativeWebSearchEnabled) {
            NATIVE_SEARCH_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.NETWORK) }
        }
        if (nativeExecEnabled) {
            NATIVE_EXEC_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.EXEC) }
        }
        if (nativeSubagentsEnabled) {
            subagentTools.forEach { entries += DynamicToolEntry(it, DynamicToolSource.NATIVE_READ) }
        }
        NATIVE_TODO_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.NATIVE_TODO) }
        TERMINAL_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.TERMINAL) }
        INTERACTION_TOOLS.forEach { entries += DynamicToolEntry(it, DynamicToolSource.INTERACTION) }
        addActivatedBackendTools(entries, context, failures)
        val available = entries.filter { it.source == DynamicToolSource.BACKEND || it.modelTool.name !in disabledTools }
        return DynamicToolSet(available, warnings.toList(), failures.toList(), exposure = exposure)
    }

    private fun addActivatedBackendTools(
        entries: MutableList<DynamicToolEntry>,
        context: MaterializerContext,
        failures: MutableSet<String>,
    ) {
        val usedNames = (NATIVE_RESERVED_TOOL_NAMES + CALL_TOOL_NAME).toMutableSet()
        entries.mapTo(usedNames) { it.modelTool.name }
        for (activation in activationStore.activated(context.runId)) {
            if (activation.schemaJson.isBlank()) {
                failures += inspectFailure(activation.toolName, "Activated schema was blank.")
                continue
            }
            val injectedName = activation.toolName
            if (injectedName in usedNames) {
                failures += inspectFailure(activation.toolName, "Activated MCP tool name collided with an existing tool.")
                continue
            }
            usedNames += injectedName
            entries += DynamicToolEntry(
                modelTool = ModelTool(injectedName, activation.schemaJson, description = activation.description),
                source = DynamicToolSource.BACKEND,
                backendToolName = activation.toolName,
                action = RuntimeActionDescriptor(
                    providerId = activation.serverId,
                    actionName = activation.originalToolName,
                    effectKind = activation.effectKind,
                    impact = activation.completionImpact,
                ),
            )
        }
    }

}

private val NATIVE_RESERVED_TOOL_NAMES = NATIVE_TOOLS.mapTo(mutableSetOf()) { it.name }

/** Tool set advertised to and resolvable by the model for one round. */
data class DynamicToolSet(
    private val entries: List<DynamicToolEntry>,
    val warnings: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val exposure: BackendToolExposure = BackendToolExposure.DIRECT,
) {
    /** Backend entries are dispatched but not offered; the model reaches them through call_tool (see DeferredTools). */
    val defersBackendTools: Boolean = exposure == BackendToolExposure.CALL_TOOL

    @OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
    val modelTools: List<ModelTool> = when (exposure) {
        BackendToolExposure.DIRECT -> entries.map { it.modelTool }
        BackendToolExposure.CALL_TOOL -> entries.filter { it.source != DynamicToolSource.BACKEND }.map { it.modelTool } + CALL_TOOL
        // Declared deferred, they cost nothing until a tool_addition in the conversation loads them; adding to or
        // reordering the deferred declarations leaves the provider's cached prefix intact (measured on DeepSeek).
        BackendToolExposure.NATIVE_DEFERRED -> entries.map { if (it.source == DynamicToolSource.BACKEND) it.modelTool.copy(deferred = true) else it.modelTool }
    }

    /** A name the model used for a tool: the qualified name, or a backend tool's own name without the server prefix. */
    fun resolveToolName(name: String): String =
        entries.firstOrNull { it.modelTool.name == name }?.modelTool?.name
            ?: entries.firstOrNull { it.source == DynamicToolSource.BACKEND && it.action?.actionName == name }?.modelTool?.name
            ?: name

    fun sourceFor(toolName: String): DynamicToolSource? =
        entries.firstOrNull { it.modelTool.name == toolName }?.source

    fun backendToolName(toolName: String): String? =
        entries.firstOrNull { it.modelTool.name == toolName }?.backendToolName

    fun schemaJson(toolName: String): String? =
        entries.firstOrNull { it.modelTool.name == toolName }?.modelTool?.schemaJson

    fun isReadOnlyBackendTool(toolName: String): Boolean {
        val entry = entries.firstOrNull { it.modelTool.name == toolName } ?: return false
        return entry.source == DynamicToolSource.BACKEND &&
            entry.readOnlyHint &&
            !entry.requiresObligationLedger()
    }

    fun requiresObligationLedger(toolName: String): Boolean =
        entries.firstOrNull { it.modelTool.name == toolName }?.requiresObligationLedger() == true

    fun actionDescriptor(toolName: String): RuntimeActionDescriptor? =
        entries.firstOrNull { it.modelTool.name == toolName }?.action

    fun modelToolNames(): Set<String> =
        entries.mapTo(linkedSetOf()) { it.modelTool.name }
}

data class DynamicToolEntry(
    val modelTool: ModelTool,
    val source: DynamicToolSource,
    val backendToolName: String? = null,
    val readOnlyHint: Boolean = modelTool.schemaJson.hasReadOnlyHint(),
    val action: RuntimeActionDescriptor? = null,
)

fun DynamicToolEntry.requiresObligationLedger(): Boolean {
    val descriptor = action ?: return false
    return descriptor.effectKind == RuntimeEffectKind.MUTATING &&
        descriptor.impact == CompletionImpact.REQUIRED_ON_CALL
}

enum class DynamicToolSource {
    INTERACTION,
    NATIVE_READ,
    NATIVE_WRITE,
    NETWORK,
    EXEC,
    NATIVE_TODO,
    BACKEND,
    TERMINAL,
}

fun injectedBackendToolName(originalName: String, usedNames: Set<String>): String {
    var candidate = if (originalName in usedNames) "cap_$originalName" else originalName
    while (candidate in usedNames) {
        candidate = "cap_$candidate"
    }
    return candidate
}

private fun inspectFailure(toolName: String, message: String): String =
    "inspect_failed tool=$toolName code=${RuntimeErrorCode.SCHEMA_INSPECT_FAILED.wireCode} " +
        "message=\"${failureMessage(message)}\" retryable=true"

private fun failureMessage(message: String): String =
    message.replace("\"", "'")

private fun String.hasReadOnlyHint(): Boolean {
    val schema = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return false
    return schema.booleanField("readOnlyHint") == true ||
        schema.booleanField("x-readOnlyHint") == true ||
        (schema["annotations"] as? JsonObject)?.booleanField("readOnlyHint") == true
}

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

/** How activated backend tools reach the model. */
enum class BackendToolExposure {
    /** In the request's tool list; the list grows as tools are found. */
    DIRECT,
    /** Out of the tool list; schemas in messages, calls through `call_tool` (any provider). */
    CALL_TOOL,
    /** Declared deferred and loaded by `tool_addition` messages; needs provider support (DeepSeek's official API). */
    NATIVE_DEFERRED,
}
