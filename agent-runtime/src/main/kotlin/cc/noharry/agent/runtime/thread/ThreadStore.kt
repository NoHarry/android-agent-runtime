package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.kernel.core.context.SessionHistoryPersistence
import cc.noharry.agent.kernel.core.context.StoredSessionHistory
import cc.noharry.agent.runtime.DefaultTraceRedactor
import cc.noharry.agent.runtime.TraceRedactor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One private storage root for provider history and redacted thread snapshots. Call from the runtime I/O dispatcher. */
internal class ThreadStore(
    root: File? = null,
    redactor: TraceRedactor = DefaultTraceRedactor,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val root = root?.canonicalFile
    internal val coordination = this.root?.path?.let { roots.getOrPut(it) { ThreadCoordination() } } ?: ThreadCoordination()
    private val lock = coordination.lock
    internal val attempts = ThreadAttemptStore(this.root, lock)
    private val codec = ThreadRecordCodec(redactor)
    private val memory = linkedMapOf<String, StoredThread>()
    private val liveTurns = linkedMapOf<String, LinkedHashMap<String, TurnResult>>()
    private val modelMemory = mutableMapOf<String, StoredSessionHistory>()
    init { synchronized(lock) { coordination.stores.add(this) } }

    fun executionSlot(threadId: String): ThreadExecutionSlot = coordination.slot(threadId)
    fun isCurrent(threadId: String, generation: Long): Boolean = synchronized(lock) {
        val slot = executionSlot(threadId)
        !slot.deleting && slot.generation == generation
    }

    private fun requireGeneration(threadId: String, generation: Long?) {
        val slot = executionSlot(threadId)
        check(!slot.deleting && (generation == null || slot.generation == generation)) { "Thread was deleted" }
    }

    fun load(threadId: String, generation: Long? = null): ThreadRecord? = synchronized(lock) {
        requireGeneration(threadId, generation)
        read(threadId)?.record?.copy(generation = executionSlot(threadId).generation)
    }

    fun create(id: String, options: ThreadOptions): ThreadRecord = synchronized(lock) {
        require(id.isNotBlank()) { "Thread id must not be blank" }
        requireGeneration(id, null)
        (read(id)?.record ?: nowEpochMillis().let { now ->
            ThreadRecord(id, options.copy(threadId = id), now, now).also { write(StoredThread(it)) }
        }).copy(generation = executionSlot(id).generation)
    }

    fun list(): List<ThreadInfo> = synchronized(lock) {
        val threads = root?.listFiles()?.filter { it.name.endsWith(THREAD_SUFFIX) }?.mapNotNull { file ->
            try { codec.decode(Json.parseToJsonElement(file.readText()) as JsonObject) } catch (_: Exception) { null }
        } ?: memory.values.toList()
        threads.filterNot { executionSlot(it.record.id).deleting }.map { it.record.info() }.sortedByDescending { it.updatedAt }
    }

    @Volatile internal var beforeSaveTurnForTest: ((TurnResult) -> Unit)? = null

    fun saveTurn(threadId: String, result: TurnResult, generation: Long? = null) = synchronized(lock) {
        if (generation != null && !isCurrent(threadId, generation)) return@synchronized
        beforeSaveTurnForTest?.invoke(result)
        val current = requireNotNull(read(threadId)) { "Thread does not exist: $threadId" }
        val turns = current.turns.filterNot { it.turnId == result.turnId } + result
        val pending = when {
            result.status == TurnStatus.WAITING_FOR_ANSWER -> result.pending as? Question
            result.error?.code in INPUT_REJECTION_CODES -> current.record.pendingQuestion
            else -> null
        }
        val record = current.record.copy(updatedAt = nowEpochMillis(),
            status = if (pending != null) ThreadStatus.WAITING_FOR_ANSWER else ThreadStatus.IDLE, pendingQuestion = pending)
        write(current.copy(record = record, turns = turns))
        liveTurns.getOrPut(threadId) { linkedMapOf() }[result.turnId] = result
    }

    fun items(threadId: String, afterTurnId: String? = null, generation: Long? = null): List<ThreadItem> = synchronized(lock) {
        requireGeneration(threadId, generation)
        val turns = read(threadId)?.turns.orEmpty()
        val offset = afterTurnId?.let { id -> turns.indexOfFirst { it.turnId == id }.let { if (it < 0) turns.size else it + 1 } } ?: 0
        turns.drop(offset).flatMap { liveTurns[threadId]?.get(it.turnId)?.items ?: it.items }
    }

    fun turn(threadId: String, turnId: String, generation: Long? = null): TurnResult? = synchronized(lock) {
        requireGeneration(threadId, generation)
        val saved = read(threadId)?.turns?.firstOrNull { it.turnId == turnId } ?: return@synchronized null
        liveTurns[threadId]?.get(turnId) ?: saved
    }

    fun updateOptions(threadId: String, options: ThreadOptions, generation: Long? = null) = synchronized(lock) {
        requireGeneration(threadId, generation)
        val thread = requireNotNull(read(threadId)) { "Thread does not exist: $threadId" }
        write(thread.copy(record = thread.record.copy(options = options.copy(threadId = threadId))))
    }

    fun rememberedDomains(threadId: String): Set<String> = synchronized(lock) { read(threadId)?.rememberedDomains.orEmpty() }

    fun rememberDomain(threadId: String, domain: String, generation: Long? = null) = synchronized(lock) {
        requireGeneration(threadId, generation)
        val thread = requireNotNull(read(threadId)) { "Thread does not exist: $threadId" }
        write(thread.copy(rememberedDomains = thread.rememberedDomains + domain))
    }

    fun delete(threadId: String) = synchronized(lock) {
        attempts.delete(threadId)
        removeFile(threadId, THREAD_SUFFIX)
        removeFile(threadId, HISTORY_SUFFIX)
        coordination.stores.forEach { it.clearCachedThread(threadId) }
        Unit
    }

    private fun clearCachedThread(threadId: String) {
        memory.remove(threadId)
        liveTurns.remove(threadId)
        modelMemory.remove(threadId)
    }

    fun modelHistoryPersistence(): SessionHistoryPersistence = object : SessionHistoryPersistence {
        override val identity: Any get() = root?.path ?: this@ThreadStore
        override fun load(sessionId: String): StoredSessionHistory? = synchronized(lock) {
            if (root == null) modelMemory[sessionId]
            else readModelHistory(sessionId)
        }

        override fun save(sessionId: String, history: StoredSessionHistory) = synchronized(lock) {
            if (root == null) modelMemory[sessionId] = history
            else atomicWrite(file(sessionId, HISTORY_SUFFIX), ModelHistoryCodec.encode(history).toString())
        }

        override fun delete(sessionId: String) = synchronized(lock) {
            modelMemory.remove(sessionId)
            removeFile(sessionId, HISTORY_SUFFIX)
        }

        override fun clear() = synchronized(lock) {
            modelMemory.clear()
            root?.listFiles()?.filter { it.name.endsWith(HISTORY_SUFFIX) }?.forEach { check(it.delete()) { "Cannot delete model history" } }
            Unit
        }
    }

    private fun readModelHistory(sessionId: String): StoredSessionHistory? {
        val target = file(sessionId, HISTORY_SUFFIX).takeIf(File::isFile) ?: return null
        val contents = target.readText()
        return try { ModelHistoryCodec.decode(Json.parseToJsonElement(contents) as JsonObject) } catch (_: RuntimeException) {
            val suffix = if (File(target.path + ".corrupt").exists()) ".${UUID.randomUUID()}.corrupt" else ".corrupt"
            Files.move(target.toPath(), File(target.path + suffix).toPath())
            null
        }
    }

    private fun read(id: String): StoredThread? = if (root == null) memory[id] else {
        file(id, THREAD_SUFFIX).takeIf(File::isFile)?.let { codec.decode(Json.parseToJsonElement(it.readText()) as JsonObject) }
    }

    private fun write(thread: StoredThread) {
        if (root == null) memory[thread.record.id] = thread
        else atomicWrite(file(thread.record.id, THREAD_SUFFIX), codec.encode(thread).toString())
    }

    private fun removeFile(id: String, suffix: String) {
        if (root == null) return
        val target = file(id, suffix)
        check(!target.exists() || target.delete()) { "Cannot delete thread storage" }
    }

    private fun file(id: String, suffix: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return File(requireNotNull(root), digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + suffix)
    }

    private companion object {
        val roots = ConcurrentHashMap<String, ThreadCoordination>()
        val INPUT_REJECTION_CODES = setOf("QUESTION_NOT_FOUND", "IMAGE_INPUT_UNSUPPORTED", "ATTACHMENT_NOT_FOUND",
            "ATTACHMENT_MEDIA_UNSUPPORTED", "ATTACHMENT_INVALID_TEXT", "THREAD_BUSY")
        const val THREAD_SUFFIX = ".thread.json"
        const val HISTORY_SUFFIX = ".history.json"
    }
}

internal fun atomicWrite(target: File, contents: String) {
    val directory = requireNotNull(target.parentFile)
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create thread storage directory" }
    val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}
