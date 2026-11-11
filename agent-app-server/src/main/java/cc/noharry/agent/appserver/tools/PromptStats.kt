@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.tools

import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.runtime.RuntimeInspection.STATIC_PROMPT_SOFT_BUDGET_BYTES
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The system prompt of the last chat request this app sent, measured where the request is logged. */
@cc.noharry.agent.appserver.AppServerPluginApi
object LastPrompt {
    @Volatile
    var stats: PromptStats? = null
        private set

    fun record(requestBody: String) {
        if (PROTOCOL_HEADER !in requestBody) return
        systemPromptOf(requestBody)?.let(PromptStats::of)?.let { stats = it }
    }
}

/** The prompt before the host instructions: its size, and the tool section's size and group lines. */
@cc.noharry.agent.appserver.AppServerPluginApi
data class PromptStats(val staticBytes: Int, val toolBytes: Int, val toolGroups: Int, val shortened: Boolean) {
    val overBudget: Boolean get() = staticBytes > STATIC_PROMPT_SOFT_BUDGET_BYTES

    /** For 检查 → 工具目录. */
    fun finding(): Finding = Finding(
        if (shortened) FindingCode.TOOLS_PROMPT_STATS_SHORTENED else FindingCode.TOOLS_PROMPT_STATS,
        listOf(staticBytes, STATIC_PROMPT_SOFT_BUDGET_BYTES, toolBytes, toolGroups).map { it.toString() },
        count = toolGroups,
    )

    companion object {
        fun of(system: String): PromptStats? {
            val staticPart = system.substringBefore(HOST_MARKER)
            if (!staticPart.startsWith(PROTOCOL_HEADER)) return null
            val tools = staticPart.split("\n\n").firstOrNull { it.startsWith(TOOL_SECTION_HEADER) }.orEmpty()
            val groups = tools.lines().filter { it.startsWith("- ") && !it.startsWith("- and ") }
            return PromptStats(
                staticBytes = staticPart.toByteArray(Charsets.UTF_8).size,
                toolBytes = tools.toByteArray(Charsets.UTF_8).size,
                toolGroups = groups.size,
                // The full index gives each group its summary after "): "; the shortened one stops at the tool count.
                shortened = groups.isNotEmpty() && groups.none { "): " in it },
            )
        }
    }
}

/** A chat request's system prompt: Anthropic's `system` (text or blocks), OpenAI's `instructions` or system message. */
internal fun systemPromptOf(requestBody: String): String? {
    val root = try {
        Json.parseToJsonElement(requestBody) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val message = (root["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
    return (root["system"] ?: root["instructions"] ?: message?.get("content"))?.text()
}

private fun JsonElement.text(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
    else -> null
}

private const val PROTOCOL_HEADER = "Model-driven harness protocol"
private const val TOOL_SECTION_HEADER = "Tool domains"
private const val HOST_MARKER = "\n\nHost instructions:\n"
