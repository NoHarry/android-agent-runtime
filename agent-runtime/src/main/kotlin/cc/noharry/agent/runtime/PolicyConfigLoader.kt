package cc.noharry.agent.runtime

import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.policy.PolicyRule
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cc.noharry.agent.kernel.api.ToolSource

internal object PolicyConfigLoader {
    fun load(jsonText: String): List<PolicyRule> {
        val root = parseObject(jsonText)
        root.validateVersion()
        val rules = root["rules"] as? JsonArray ?: invalid("Policy config rules must be an array.")
        return rules.mapIndexed { index, item ->
            val rule = item as? JsonObject ?: invalid("Policy rule $index must be an object.")
            rule.toPolicyRule(index)
        }
    }

    private fun JsonObject.toPolicyRule(index: Int): PolicyRule {
        val match = this["match"]?.jsonObject ?: JsonObject(emptyMap())
        val tools = match.stringList("tools")
        val sources = match.stringList("sources")?.validatedSources(index)
        val pathPrefix = match.stringField("pathPrefix")
        val argsPattern = match.stringField("argsPattern")?.toRegexRule(index)
        val decision = decision(index)
        return PolicyRule { action, context ->
            if (action !is PolicyAction.Call) {
                PolicyDecision.Allow
            } else if (context.approvedConfirmation && decision is PolicyDecision.RequireConfirmation) {
                PolicyDecision.Allow
            } else if (matches(action, tools, sources, pathPrefix, argsPattern)) {
                decision
            } else {
                PolicyDecision.Allow
            }
        }
    }

    private fun JsonObject.decision(index: Int): PolicyDecision =
        when (val decision = stringField("decision") ?: invalid("Policy rule $index missing decision.")) {
            "allow" -> PolicyDecision.Allow
            "deny" -> PolicyDecision.Deny(
                code = stringField("reasonCode") ?: RuntimeErrorCode.POLICY_DENIED.wireCode,
                reason = stringField("userMessage") ?: "Policy denied tool use.",
            )
            "require_confirmation" -> PolicyDecision.RequireConfirmation(
                reason = stringField("reasonCode") ?: "Policy requires confirmation.",
                prompt = stringField("prompt") ?: stringField("userMessage")
                    ?: invalid("Policy rule $index missing confirmation prompt."),
            )
            else -> invalid("Policy rule $index has unsupported decision '$decision'.")
        }

    private fun matches(
        action: PolicyAction.Call,
        tools: List<String>?,
        sources: Set<String>?,
        pathPrefix: String?,
        argsPattern: Regex?,
    ): Boolean =
        tools.matchesTool(action.toolName) &&
            (sources == null || action.source in sources) &&
            pathPrefix.matchesPath(action.arguments.stringField("path")) &&
            (argsPattern == null || argsPattern.containsMatchIn(action.arguments.toString().take(MAX_ARGS_INPUT)))
}

internal class PolicyConfigException(message: String) : IllegalArgumentException(
    "${RuntimeErrorCode.POLICY_CONFIG_INVALID.wireCode}: $message",
)

private fun parseObject(jsonText: String): JsonObject =
    try {
        Json.parseToJsonElement(jsonText) as? JsonObject ?: invalid("Policy config must be an object.")
    } catch (error: RuntimeException) {
        invalid(error.message ?: "Policy config JSON is invalid.")
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.stringList(name: String): List<String>? =
    this[name]?.jsonArray?.map { it.jsonPrimitive.content }

private fun JsonObject.validateVersion() {
    val rawVersion = stringField("version") ?: return
    val version = rawVersion.toIntOrNull() ?: invalid("Policy config version must be 1.")
    if (version != 1) {
        invalid("Policy config version must be 1.")
    }
}

private fun List<String>.validatedSources(index: Int): Set<String> {
    val valid = ToolSource.values().mapTo(mutableSetOf()) { it.name }
    forEach { source ->
        if (source !in valid) {
            invalid("Policy rule $index has unsupported source '$source'.")
        }
    }
    return toSet()
}

private fun String.toRegexRule(index: Int): Regex {
    if (length > MAX_PATTERN_LENGTH) {
        invalid("Policy rule $index argsPattern exceeds $MAX_PATTERN_LENGTH characters.")
    }
    return try {
        Regex(this)
    } catch (error: RuntimeException) {
        invalid(error.message ?: "Policy rule $index argsPattern is invalid.")
    }
}

private fun List<String>?.matchesTool(toolName: String): Boolean =
    this == null || any { pattern -> globMatches(pattern, toolName) }

private fun String?.matchesPath(path: String?): Boolean =
    this == null || path?.let(::canonicalRuntimeWorkspacePath)?.startsWith(this) == true

private fun globMatches(pattern: String, value: String): Boolean =
    Regex(pattern.toRegexPattern()).matches(value)

private fun String.toRegexPattern(): String {
    val output = StringBuilder("^")
    var escaping = false
    for (char in this) {
        if (escaping) {
            output.append(Regex.escape(char.toString()))
            escaping = false
        } else {
            when (char) {
                '\\' -> escaping = true
                '*' -> output.append(".*")
                '?' -> output.append('.')
                else -> output.append(Regex.escape(char.toString()))
            }
        }
    }
    if (escaping) {
        output.append(Regex.escape("\\"))
    }
    return output.append('$').toString()
}

private fun invalid(message: String): Nothing = throw PolicyConfigException(message)

private const val MAX_PATTERN_LENGTH = 512
private const val MAX_ARGS_INPUT = 32 * 1024
