@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.SessionAttachmentRef
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.runtime.TraceRedactor
import cc.noharry.agent.runtime.redactToolPayload
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ThreadItemCodec(
    private val redactor: TraceRedactor,
) {
    fun encode(item: ThreadItem): JsonObject = buildJsonObject {
        put("id", item.id)
        put("turnId", item.turnId)
        put("status", item.status.name)
        when (item) {
            is ThreadItem.UserMessage -> {
                put("kind", "user"); put("input", JsonArray(item.input.map(::input))); put("steer", item.steer)
                item.requestId?.let { put("requestId", it) }
            }
            is ThreadItem.AgentMessage -> {
                put("kind", "agent"); put("text", if (item.phase == MessagePhase.FINAL) item.text else redact(item.text))
                put("phase", item.phase.name)
            }
            is ThreadItem.Reasoning -> { put("kind", "reasoning"); put("text", redact(item.text)) }
            is ThreadItem.ToolCall -> tool(item)
            is ThreadItem.WebSearch -> {
                put("kind", "search"); put("provider", item.provider); put("query", redact(item.query))
                item.resultCount?.let { put("resultCount", it) }
            }
            is ThreadItem.FileChange -> {
                put("kind", "file"); put("path", redact(item.path)); put("tool", item.tool)
                put("added", item.added); put("removed", item.removed)
            }
            is ThreadItem.Notice -> {
                put("kind", "notice"); put("noticeKind", item.kind.name); put("message", redact(item.message))
            }
            is ThreadItem.Error -> {
                put("kind", "error"); put("code", item.code); put("message", redact(item.message))
                put("recoverable", item.recoverable)
            }
            is Question -> question(item)
            is Approval -> approval(item)
        }
    }

    fun decode(value: JsonObject): ThreadItem {
        val id = value.text("id")
        val turn = value.text("turnId")
        val status = value.enum("status", ItemStatus.COMPLETED)
        return when (value.text("kind")) {
            "user" -> ThreadItem.UserMessage(id, turn, value.array("input").map { input(it as JsonObject) }, value.flag("steer"), status, value.optionalText("requestId"))
            "agent" -> ThreadItem.AgentMessage(id, turn, value.text("text"), value.enum("phase", MessagePhase.COMMENTARY), status)
            "reasoning" -> ThreadItem.Reasoning(id, turn, value.text("text"), status)
            "tool" -> tool(value, id, turn, status)
            "search" -> ThreadItem.WebSearch(id, turn, value.text("provider"), value.text("query"), value.optionalLong("resultCount")?.toInt(), status)
            "file" -> ThreadItem.FileChange(id, turn, value.text("path"), value.number("added"), value.number("removed"), value.text("tool"), status)
            "notice" -> ThreadItem.Notice(id, turn, value.enum("noticeKind", NoticeKind.WORKSPACE_CHANGED), value.text("message"), status)
            "error" -> ThreadItem.Error(id, turn, value.text("code"), value.text("message"), value.flag("recoverable"), status)
            "question" -> Question(id, turn, value.text("text"), value.array("choices").map { (it as JsonPrimitive).content }, value.flag("async"), value.optionalLong("expiresAt"), status)
            "approval" -> StoredApproval(id, turn, value.text("tool"), value.text("arguments"), value.text("domain"), value.text("reason"), value.optionalLong("expiresAt"), status, value.enum("state", ApprovalState.CANCELLED))
            else -> error("Unknown thread item kind")
        }
    }

    private fun JsonObjectBuilder.tool(item: ThreadItem.ToolCall) {
        put("kind", "tool"); put("tool", item.tool); put("source", item.source.name)
        item.server?.let { put("server", it) }
        put("arguments", redactToolPayload(item.arguments, redactor))
        item.output?.let { put("output", redactToolPayload(it, redactor)) }
        item.approvedBy?.let { put("approvedBy", it.name) }
        item.network?.let { activity -> put("network", buildJsonObject {
            put("domain", activity.domain); put("decision", activity.decision)
            activity.statusCode?.let { put("statusCode", it) }; put("truncated", activity.truncated)
        }) }
    }

    private fun tool(value: JsonObject, id: String, turn: String, status: ItemStatus): ThreadItem.ToolCall =
        ThreadItem.ToolCall(
            id, turn, value.text("tool"), value.enum("source", ToolSource.BACKEND), value.optionalText("server"),
            value.text("arguments"), value.optionalText("output"), status,
            value.obj("network")?.let { NetworkActivity(it.text("domain"), it.text("decision"), it.optionalLong("statusCode")?.toInt(), it.flag("truncated")) },
            value.optionalText("approvedBy")?.let { ApprovalPolicy.valueOf(it) },
        )

    private fun JsonObjectBuilder.question(item: Question) {
        put("kind", "question"); put("text", redact(item.text)); put("async", item.async)
        put("choices", JsonArray(item.choices.map { JsonPrimitive(redact(it)) }))
        item.expiresAt?.let { put("expiresAt", it) }
    }

    private fun JsonObjectBuilder.approval(item: Approval) {
        put("kind", "approval"); put("tool", item.tool); put("arguments", redactToolPayload(item.arguments, redactor))
        put("domain", item.domain); put("reason", redact(item.reason)); put("state", item.state.name)
        item.expiresAt?.let { put("expiresAt", it) }
    }

    private fun input(part: InputPart): JsonObject = buildJsonObject {
        when (part) {
            is InputPart.Text -> { put("kind", "text"); put("text", part.text) }
            is InputPart.Image -> {
                put("kind", "image"); put("bytes", Base64.getEncoder().encodeToString(part.bytes)); put("mimeType", part.mimeType)
            }
            is InputPart.Attachment -> {
                put("kind", "attachment"); put("sha256", part.ref.sha256); put("mediaType", part.ref.mediaType)
                put("byteSize", part.ref.byteSize); put("logicalName", redact(part.ref.logicalName))
            }
        }
    }

    private fun input(value: JsonObject): InputPart = when (value.text("kind")) {
        "text" -> InputPart.Text(value.text("text"))
        "image" -> InputPart.Image(Base64.getDecoder().decode(value.text("bytes")), value.text("mimeType"))
        "attachment" -> InputPart.Attachment(SessionAttachmentRef(value.text("sha256"), value.text("mediaType"), value.long("byteSize"), value.text("logicalName")))
        else -> error("Unknown thread input kind")
    }

    private fun redact(value: String): String = redactor.redact(value)
}

/** Loaded approvals describe a past decision and can never execute a tool again. */
internal data class StoredApproval(
    override val id: String,
    override val turnId: String,
    override val tool: String,
    override val arguments: String,
    override val domain: String,
    override val reason: String,
    override val expiresAt: Long?,
    override val status: ItemStatus,
    override val state: ApprovalState,
) : Approval() {
    override suspend fun approve(scope: ApprovalScope): RespondResult = response()
    override suspend fun deny(reason: String?): RespondResult = response()
    private fun response(): RespondResult = when (state) {
        ApprovalState.EXPIRED -> RespondResult.EXPIRED
        ApprovalState.PENDING, ApprovalState.CANCELLED -> RespondResult.NOT_FOUND
        ApprovalState.APPROVED, ApprovalState.DENIED -> RespondResult.ALREADY_ANSWERED
    }
}
