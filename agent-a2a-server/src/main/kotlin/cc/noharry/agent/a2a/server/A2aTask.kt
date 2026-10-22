package cc.noharry.agent.a2a.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

internal class A2aTask(
    val request: A2aRequest,
    streaming: Boolean,
) {
    private val lock = Any()
    private var currentStatus = status("submitted", "已接收文字请求", request.taskId, request.contextId)
    private val artifacts = mutableListOf<JsonObject>()
    private var artifactBytes = 0
    private var ended = false
    val done = CompletableDeferred<Unit>()
    val events = if (streaming) Channel<JsonObject>(EVENT_CAPACITY) else null
    lateinit var job: Job
    /** Set before a cancel that makes room for a newer message in the same context. */
    @Volatile var interrupted = false
    val terminal: Boolean get() = synchronized(lock) { ended }
    val initialSnapshot = snapshot()

    fun snapshot(): JsonObject = synchronized(lock) {
        jsonObject(
            "kind" to text("task"), "id" to text(request.taskId), "contextId" to text(request.contextId),
            "status" to currentStatus, "artifacts" to JsonArray(artifacts.toList()),
        )
    }

    suspend fun publish(output: A2aOutput) {
        val event = when (output) {
            is A2aOutput.Status -> updateStatus("working", output.text, false, buildJsonObject {
                put("kind", "tool-status")
                put("phase", output.phase.take(MAX_STATUS_CHARS))
                output.toolName?.let { put("toolName", it.take(MAX_STATUS_CHARS)) }
                output.toolCallId?.let { put("toolCallId", it.take(MAX_STATUS_CHARS)) }
            })
            is A2aOutput.Speech -> artifact(output.text, A2aArtifactChannel.SPEECH, if (output.final) "final" else "progress")
            is A2aOutput.Question -> artifact(output.text, A2aArtifactChannel.QUESTION, output.state, buildJsonObject {
                put("questionId", output.questionId)
                put("options", JsonArray(output.options.map(::text)))
            })
            is A2aOutput.Result -> error("Terminal results are committed after host cleanup")
        }
        send(event)
    }

    suspend fun finish(result: A2aOutput.Result) {
        if (terminal) return
        try {
            val response = result.text.takeIf(String::isNotBlank)?.let { artifact(it, A2aArtifactChannel.DISPLAY, "final") }
            val terminal = updateStatus(result.state.wire, result.text, true)
            response?.let { send(it) }
            send(terminal)
        } finally {
            events?.close()
            done.complete(Unit)
        }
    }

    fun failDelivery() {
        if (!terminal) updateStatus("failed", "输出连接不可用或输出超限", true)
        events?.close()
        done.complete(Unit)
    }

    private suspend fun send(event: JsonObject) {
        events?.let { withTimeout(DELIVERY_TIMEOUT_MS) { it.send(event) } }
    }

    private fun artifact(value: String, channel: A2aArtifactChannel, phase: String, metadata: JsonObject = jsonObject()): JsonObject = synchronized(lock) {
        val bytes = value.toByteArray(Charsets.UTF_8).size + metadata.toString().toByteArray(Charsets.UTF_8).size
        check(artifactBytes + bytes <= MAX_ARTIFACT_BYTES && artifacts.size < MAX_ARTIFACTS) { "Output limit" }
        artifactBytes += bytes
        val artifact = jsonObject(
            "artifactId" to text("${request.taskId}-${artifacts.size}"), "name" to text(channel.legacyKind),
            "parts" to JsonArray(listOf(textPart(value))),
            "metadata" to JsonObject(metadata + mapOf("channel" to text(channel.wire), "kind" to text(channel.legacyKind), "phase" to text(phase))),
        )
        artifacts += artifact
        eventBase("artifact-update") + mapOf(
            "artifact" to artifact, "append" to JsonPrimitive(false), "lastChunk" to JsonPrimitive(true),
        )
    }.let(::JsonObject)

    private fun updateStatus(
        state: String,
        message: String,
        final: Boolean,
        metadata: JsonObject = jsonObject(),
    ): JsonObject = synchronized(lock) {
        currentStatus = status(state, message.take(MAX_STATUS_CHARS), request.taskId, request.contextId)
        ended = final
        JsonObject(eventBase("status-update") + mapOf(
            "status" to currentStatus, "final" to JsonPrimitive(final),
            "metadata" to JsonObject(metadata + ("channel" to text("status"))),
        ))
    }

    private fun eventBase(kind: String): Map<String, JsonElement> = mapOf(
        "kind" to text(kind), "taskId" to text(request.taskId), "contextId" to text(request.contextId),
    )

    private companion object {
        const val EVENT_CAPACITY = 64
        const val DELIVERY_TIMEOUT_MS = 5_000L
        const val MAX_STATUS_CHARS = 240
        const val MAX_ARTIFACT_BYTES = 1_048_576
        const val MAX_ARTIFACTS = 128
    }
}
