package cc.noharry.agent.appserver.skills
import android.content.Context
import cc.noharry.agent.spi.ToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Immutable per-run selection; manifests are trusted APK assets, never fetched from tool output. */
internal data class WorkflowSkill(
    val skill: RuntimeSkill,
    val bindings: Map<String, Set<String>>,
)

internal fun selectedWorkflowSkills(context: Context, skills: List<RuntimeSkill>, input: String): List<WorkflowSkill> =
    skills.filter { it.enabled && it.manifestPath != null }.mapNotNull { skill ->
        val raw = context.assets.open(checkNotNull(skill.manifestPath)).bufferedReader().use { it.readText() }
        parseWorkflowSkill(skill, raw, input)
    }

internal fun parseWorkflowSkill(skill: RuntimeSkill, raw: String, input: String): WorkflowSkill? {
    val manifest = Json.parseToJsonElement(raw).jsonObject
    require(manifest["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported workflow manifest." }
    val matches = manifest["triggers"]!!.jsonArray.any {
        Regex(it.jsonPrimitive.content, RegexOption.IGNORE_CASE).containsMatchIn(input)
    }
    if (!matches) return null
    val bindings = manifest["bindings"]!!.jsonObject.mapValues { (_, fields) ->
        fields.jsonArray.map { it.jsonPrimitive.content }.toSet()
    }
    return WorkflowSkill(skill, bindings)
}

internal fun compatibleWorkflowTools(workflows: List<WorkflowSkill>, tools: List<ToolDescriptor>): List<ToolDescriptor> {
    val catalog = tools.associateBy { it.name }
    return workflows.flatMap { workflow -> workflow.bindings.mapNotNull { (name, fields) ->
        val tool = catalog[name] ?: return@mapNotNull null
        val schema = runCatching { Json.parseToJsonElement(tool.inputSchemaJson).jsonObject }.getOrNull()
            ?: return@mapNotNull null
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        tool.takeIf { properties.keys.containsAll(fields) }
    } }.distinctBy { it.name }
}
