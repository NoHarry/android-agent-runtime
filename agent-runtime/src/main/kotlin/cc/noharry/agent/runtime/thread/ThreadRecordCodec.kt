package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.runtime.TraceRedactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ThreadRecord(
    val id: String,
    val options: ThreadOptions,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ThreadStatus = ThreadStatus.IDLE,
    val pendingQuestion: Question? = null,
    val generation: Long = 0L,
) {
    fun info(): ThreadInfo = ThreadInfo(id, createdAt, updatedAt, status)
}

internal data class StoredThread(
    val record: ThreadRecord,
    val turns: List<TurnResult> = emptyList(),
    val rememberedDomains: Set<String> = emptySet(),
)

internal class ThreadRecordCodec(
    private val redactor: TraceRedactor,
) {
    private val items = ThreadItemCodec(redactor)

    fun encode(thread: StoredThread): JsonObject = buildJsonObject {
        put("version", 1)
        put("id", thread.record.id)
        put("createdAt", thread.record.createdAt)
        put("updatedAt", thread.record.updatedAt)
        put("status", thread.record.status.name)
        put("options", options(thread.record.options))
        thread.record.pendingQuestion?.let { put("pendingQuestion", items.encode(it)) }
        put("turns", JsonArray(thread.turns.map(::turn)))
        put("rememberedDomains", JsonArray(thread.rememberedDomains.map(::JsonPrimitive)))
    }

    fun decode(value: JsonObject): StoredThread {
        require(value.number("version") == 1) { "Unsupported thread storage version" }
        val record = ThreadRecord(
            value.text("id"), value.obj("options")?.let(::options) ?: ThreadOptions(),
            value.long("createdAt"), value.long("updatedAt"), value.enum("status", ThreadStatus.IDLE),
            value.obj("pendingQuestion")?.let { items.decode(it) as Question },
        )
        return StoredThread(record, value.array("turns").map { turn(it as JsonObject) },
            value.array("rememberedDomains").mapTo(linkedSetOf()) { (it as JsonPrimitive).content })
    }

    private fun options(options: ThreadOptions): JsonObject = buildJsonObject {
        options.threadId?.let { put("threadId", it) }
        options.scene?.let { put("scene", it) }
        options.workspace?.let { put("workspace", it) }
        options.instructions?.let { put("instructions", it) }
        put("approvalPolicy", (options.approvalPolicy ?: ApprovalPolicy.ASK).name)
        options.model?.let { model -> put("model", buildJsonObject {
            put("providerId", model.providerId); put("modelId", model.modelId)
            model.variant?.let { put("variant", it) }
        }) }
    }

    private fun options(value: JsonObject): ThreadOptions = ThreadOptions(
        threadId = value.optionalText("threadId"),
        model = value.obj("model")?.let { ModelRef(it.text("providerId"), it.text("modelId"), it.optionalText("variant")) },
        workspace = value.optionalText("workspace"), scene = value.optionalText("scene"),
        instructions = value.optionalText("instructions"), approvalPolicy = value.enum("approvalPolicy", ApprovalPolicy.ASK),
    )

    private fun turn(turn: TurnResult): JsonObject = buildJsonObject {
        put("turnId", turn.turnId); put("status", turn.status.name)
        put("finalResponse", turn.finalResponse)
        put("items", JsonArray(turn.items.map(items::encode)))
        turn.pending?.let { put("pending", items.encode(it)) }
        turn.usage?.let { usage -> put("usage", buildJsonObject {
            put("inputTokens", usage.inputTokens); put("outputTokens", usage.outputTokens)
            put("reasoningTokens", usage.reasoningTokens); put("cacheReadTokens", usage.cacheReadTokens)
            put("cacheWriteTokens", usage.cacheWriteTokens)
        }) }
        turn.error?.let { error -> put("error", buildJsonObject {
            put("code", error.code); put("message", redactor.redact(error.message)); put("recoverable", error.recoverable)
        }) }
    }

    private fun turn(value: JsonObject): TurnResult = TurnResult(
        value.text("turnId"), value.enum("status", TurnStatus.FAILED), value.text("finalResponse"),
        value.array("items").map { items.decode(it as JsonObject) },
        value.obj("usage")?.let { Usage(it.number("inputTokens"), it.number("outputTokens"), it.number("reasoningTokens"), it.number("cacheReadTokens"), it.number("cacheWriteTokens")) },
        value.obj("pending")?.let { items.decode(it) as PendingRequest },
        value.obj("error")?.let { TurnError(it.text("code"), it.text("message"), it.flag("recoverable")) },
    )
}
