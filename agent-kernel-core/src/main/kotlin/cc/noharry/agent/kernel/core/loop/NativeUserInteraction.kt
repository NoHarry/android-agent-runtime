@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.SessionEventPayload.UserInteraction
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.trace.NativePublicEventBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NativeUserInteraction(private val inputs: RuntimeSteeringQueue) {
    suspend fun ask(event: ModelEvent.ToolCall, context: MaterializerContext, events: NativePublicEventBuffer): String {
        val root = parse(event.argumentsJson)
        require(root.keys == setOf("questions")) { "ask_async requires questions only" }
        val raw = root["questions"] as? JsonArray ?: throw IllegalArgumentException("questions must be an array")
        require(raw.size in 1..MAX_QUESTIONS_PER_CALL) { "Provide one to three questions" }
        val questions = raw.map { value ->
            val question = value as? JsonObject ?: throw IllegalArgumentException("Invalid question")
            require(question.keys.all { it in setOf("title", "options") }) { "Unknown question field" }
            val title = question.string("title")
            require(title.isNotBlank() && title.length <= MAX_QUESTION_CHARS) { "Invalid question title" }
            val options = question["options"]?.let { parseOptions(it as? JsonArray ?: throw IllegalArgumentException("Invalid options")) }.orEmpty()
            UserInteraction("QUESTION", "PENDING", text = title, options = options)
        }
        val registered = inputs.questions(context.runId, event.id, questions)
        inputs.projectPublic(context.runId, events)
        return buildJsonObject {
            put("accepted", true)
            put("questionIds", buildJsonArray { registered.forEach { add(JsonPrimitive(it.questionId)) } })
            put("message", "Questions are displayed. Continue work immediately; replies arrive as user messages. Do not wait, poll, or repeat the question. If no reply arrives, finish available work with stated assumptions for optional preferences; skip/report actions missing required input or approval. Unanswered questions expire when you finish, so do the independent work before finishing.")
        }.toString()
    }

}

/** Projects canonical question/input facts recorded since the last call onto the public stream, redacted. */
internal suspend fun RuntimeSteeringQueue.projectPublic(runId: String, events: NativePublicEventBuffer) {
    takePublicFacts(runId).forEach { events.recordInteraction(it) }
}

internal fun interactionFailure(code: String, message: String?): String = buildJsonObject {
    put("ok", false)
    put("code", code)
    put("message", message.orEmpty())
}.toString()

private fun parse(arguments: String): JsonObject =
    Json.parseToJsonElement(arguments) as? JsonObject ?: throw IllegalArgumentException("Expected an object")

private fun parseOptions(raw: JsonArray): List<String> {
    require(raw.size in 1..MAX_OPTIONS) { "Invalid option count" }
    return raw.map {
        val option = (it as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Options must be strings")
        require(option.isNotBlank() && option.length <= MAX_OPTION_CHARS) { "Invalid option" }
        option
    }
}

private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Missing $name")
internal const val ASK_ASYNC = "ask_async"
private const val MAX_QUESTIONS_PER_CALL = 3
private const val MAX_QUESTION_CHARS = 4096
private const val MAX_OPTIONS = 8
private const val MAX_OPTION_CHARS = 512

val INTERACTION_TOOLS = listOf(
    ModelTool(ASK_ASYNC, """{
      "type":"object","additionalProperties":false,"required":["questions"],
      "description":"Ask optional clarification questions without stopping this run, only when meaningful independent work remains to do meanwhile. Returns question IDs immediately; answers arrive as new user messages. Continue working immediately without waiting, polling, or repeating unanswered questions. Unanswered questions expire when the run finishes, so finish after the independent work. If nothing useful can be done before the answer, use ask instead. No reply must not block task completion: state assumptions for optional preferences and skip/report actions missing required input or approval. A suggestion is not user approval.",
      "properties":{"questions":{"type":"array","description":"One to three self-contained questions. Work continues without waiting for replies.","minItems":1,"maxItems":3,"items":{
        "type":"object","additionalProperties":false,"required":["title"],"properties":{
          "title":{"type":"string","description":"The complete question shown to the user.","minLength":1,"maxLength":4096},
          "options":{"type":"array","description":"Optional suggested answers; free text is always allowed. Options are never submitted automatically.","minItems":1,"maxItems":8,"items":{"type":"string","minLength":1,"maxLength":512}}
        }
      }}}
    }""".trimIndent()),
)
