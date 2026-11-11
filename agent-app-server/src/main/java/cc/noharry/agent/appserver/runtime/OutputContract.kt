package cc.noharry.agent.appserver.runtime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Field/type hints only; exact provider schema remains on the catalogue card. Never infer defaults. */
internal fun outputContractSummary(raw: String?): String {
    val schema = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return ""
    val fields = linkedSetOf<String>()
    collectOutputFields(schema, "", 0, fields)
    if (fields.isEmpty()) return ""
    return "\nOutput fields in result.structuredContent (? = optional; omission is unknown): " +
        fields.take(MAX_OUTPUT_FIELDS).joinToString("; ").take(MAX_OUTPUT_HINT_CHARS) +
        ". Field hints are partial; full outputSchema is on the tool card. Check status, reason and required data; OK may only acknowledge a command."
}

private fun collectOutputFields(schema: JsonObject, path: String, depth: Int, fields: MutableSet<String>) {
    if (depth > MAX_OUTPUT_DEPTH || fields.size >= MAX_OUTPUT_FIELDS) return
    val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
    (schema["properties"] as? JsonObject).orEmpty().forEach { (name, value) ->
        if (fields.size >= MAX_OUTPUT_FIELDS || (path.isEmpty() && name !in OUTPUT_ROOT_FIELDS)) return@forEach
        val child = value as? JsonObject ?: return@forEach
        val childPath = if (path.isEmpty()) name else "$path.$name"
        fields += "$childPath${if (name in required) "" else "?"}:${child["type"] ?: "schema"}"
        collectOutputFields(child, childPath, depth + 1, fields)
    }
    (schema["items"] as? JsonObject)?.let { collectOutputFields(it, "$path[]", depth + 1, fields) }
    for (kind in listOf("anyOf", "oneOf", "allOf")) {
        (schema[kind] as? JsonArray).orEmpty().forEach { branch ->
            (branch as? JsonObject)?.let { collectOutputFields(it, path, depth + 1, fields) }
        }
    }
}

private val OUTPUT_ROOT_FIELDS = setOf("status", "data", "upstreamCode", "upstreamMessage")
private const val MAX_OUTPUT_FIELDS = 28
private const val MAX_OUTPUT_DEPTH = 6
private const val MAX_OUTPUT_HINT_CHARS = 2400
