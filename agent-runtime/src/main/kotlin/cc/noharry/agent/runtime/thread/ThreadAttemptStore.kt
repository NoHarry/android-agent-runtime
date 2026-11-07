package cc.noharry.agent.runtime.thread

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Durable mapping from a conversation-local turn id to its latest independent engine attempt. */
internal class ThreadAttemptStore(
    private val root: File?,
    private val lock: Any,
) {
    private val memory = mutableMapOf<String, List<ThreadAttempt>>()

    fun latest(threadId: String, turnId: String): ThreadAttempt? = synchronized(lock) {
        read(threadId).firstOrNull { it.turnId == turnId }
    }

    fun active(threadId: String): List<ThreadAttempt> = synchronized(lock) { read(threadId).filter { it.active } }

    fun begin(threadId: String, turnId: String, executionId: String): ThreadAttempt = synchronized(lock) {
        val records = read(threadId)
        val attempt = ThreadAttempt(turnId, executionId, (records.firstOrNull { it.turnId == turnId }?.attempt ?: 0) + 1, true)
        write(threadId, records.filterNot { it.turnId == turnId } + attempt)
        attempt
    }

    fun complete(threadId: String, turnId: String, executionId: String) = synchronized(lock) {
        val records = read(threadId)
        if (records.none { it.turnId == turnId && it.executionId == executionId && it.active }) return@synchronized
        write(threadId, records.map { if (it.turnId == turnId && it.executionId == executionId) it.copy(active = false) else it })
    }

    fun delete(threadId: String) = synchronized(lock) {
        memory.remove(threadId)
        root?.let { file(threadId).let { check(!it.exists() || it.delete()) { "Cannot delete turn attempts" } } }
        Unit
    }

    private fun read(threadId: String): List<ThreadAttempt> {
        if (root == null) return memory[threadId].orEmpty()
        val target = file(threadId)
        if (!target.exists()) return emptyList()
        val json = Json.parseToJsonElement(target.readText()) as JsonObject
        require(json.number("version") == 1) { "Unsupported attempt storage version" }
        return json.array("attempts").map { item ->
            val value = item as JsonObject
            ThreadAttempt(value.text("turnId"), value.text("executionId"), value.number("attempt"), value.flag("active"))
        }
    }

    private fun write(threadId: String, records: List<ThreadAttempt>) {
        if (root == null) { memory[threadId] = records; return }
        val encoded = buildJsonObject {
            put("version", 1)
            put("attempts", JsonArray(records.map { item -> buildJsonObject {
                put("turnId", item.turnId); put("executionId", item.executionId)
                put("attempt", item.attempt); put("active", item.active)
            } }))
        }
        atomicWrite(file(threadId), encoded.toString())
    }

    private fun file(threadId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(threadId.toByteArray(Charsets.UTF_8))
        return File(requireNotNull(root), digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".attempts.json")
    }
}

internal data class ThreadAttempt(val turnId: String, val executionId: String, val attempt: Int, val active: Boolean)
