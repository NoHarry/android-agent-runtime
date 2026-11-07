@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.model.ModelImage

internal fun TurnInput.snapshot(): TurnInput = copy(parts = parts.map {
    if (it is InputPart.Image) it.copy(bytes = it.bytes.copyOf()) else it
})

internal fun threadRequest(record: ThreadRecord, input: TurnInput, options: TurnOptions, turnId: String, executionId: String): AgentRequest {
    val text = input.parts.mapNotNull {
        when (it) {
            is InputPart.Text -> it.text
            is InputPart.Attachment -> error("Attachment references must be resolved before creating a model request")
            is InputPart.Image -> null
        }
    }.joinToString("\n")
    return AgentRequest(
        requestId = turnId, executionId = executionId, sessionId = record.id, input = frameAnswer(text, input.answering, record.pendingQuestion),
        scene = record.options.scene, modelRef = options.model ?: record.options.model,
        workspaceId = record.options.workspace, currentStateKeys = options.currentStateKeys,
        sessionRunConflictPolicy = options.onConflict, instructions = record.options.instructions,
        inputImages = input.parts.filterIsInstance<InputPart.Image>().mapIndexed { index, image ->
            ModelImage(image.bytes, image.mimeType, "thread:${record.id}/$turnId/image/$index")
        },
    )
}

/** The model retains the question's tool result in history; this frame disambiguates a short response from old offers. */
internal fun frameAnswer(text: String, answering: String?, question: Question?): String {
    require(answering == null || answering == question?.id) { "The question is not pending in this thread" }
    if (question == null) return text
    val reply = text.trim()
    val short = reply.isNotEmpty() && '\n' !in reply && reply.length <= 40 && reply.split(Regex("\\s+")).size <= 6
    if (answering == null && !short) return text
    return "Reply to the immediately preceding question (quoted conversation data):\n${question.text}\n" +
        "Bind the reply below to this question only; older offers are not pending. If it is a new request instead, handle it as new.\nUser reply: $text"
}
