@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.spi.model.ModelImage
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.kernel.core.context.StoredSessionHistory
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Provider history preserves signed thinking, tool payloads and images exactly; it is not a public log. */
internal object ModelHistoryCodec {
    fun encode(history: StoredSessionHistory): JsonObject = buildJsonObject {
        put("version", 1)
        put("runs", JsonArray(history.runs.map { JsonArray(it.map(::message)) }))
        history.lastInputTokens?.let { put("lastInputTokens", it) }
    }

    fun decode(value: JsonObject): StoredSessionHistory {
        require(value.number("version") == 1) { "Unsupported model history version" }
        return StoredSessionHistory(
            value.array("runs").map { run -> (run as JsonArray).map { message(it as JsonObject) } },
            value.optionalLong("lastInputTokens"),
        )
    }

    private fun message(value: ModelMessage): JsonObject = buildJsonObject {
        put("role", value.role)
        put("content", value.content)
        put("images", JsonArray(value.images.map(::image)))
        value.toolCallId?.let { put("toolCallId", it) }
        value.toolName?.let { put("toolName", it) }
        value.toolArgumentsJson?.let { put("toolArgumentsJson", it) }
        value.thinkingSignature?.let { put("thinkingSignature", it) }
        value.reasoningContent?.let { put("reasoningContent", it) }
        put("toolAdditions", JsonArray(value.toolAdditions.map(::JsonPrimitive)))
        put("assistantText", value.assistantText)
    }

    private fun message(value: JsonObject): ModelMessage = ModelMessage(
        role = value.text("role"), content = value.text("content"),
        images = value.array("images").map { image(it as JsonObject) },
        toolCallId = value.optionalText("toolCallId"), toolName = value.optionalText("toolName"),
        toolArgumentsJson = value.optionalText("toolArgumentsJson"),
        thinkingSignature = value.optionalText("thinkingSignature"),
        toolAdditions = value.array("toolAdditions").map { (it as JsonPrimitive).content },
        assistantText = value.text("assistantText"), reasoningContent = value.optionalText("reasoningContent"),
    )

    private fun image(value: ModelImage): JsonObject = buildJsonObject {
        put("bytes", Base64.getEncoder().encodeToString(value.bytes))
        put("mediaType", value.mediaType)
        put("sourcePath", value.sourcePath)
        put("detail", value.detail)
    }

    private fun image(value: JsonObject): ModelImage = ModelImage(
        Base64.getDecoder().decode(value.text("bytes")), value.text("mediaType"), value.text("sourcePath"),
        value.optionalText("detail") ?: "auto",
    )
}
