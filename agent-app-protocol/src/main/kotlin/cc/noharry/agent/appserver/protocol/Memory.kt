package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kinds of things the assistant remembers about the user, in the memory page's order; the page names them. */
@Serializable
enum class MemoryCategory(val id: String) {
    IDENTITY("identity"),
    PLACES("places"),
    HOME("home"),
    MEDIA("media"),
    TRAVEL("travel"),
    PEOPLE("people"),
    OTHER("other"),
    ;

    companion object {
        fun byId(id: String?): MemoryCategory? = entries.firstOrNull { it.id == id }
    }
}

/** Who wrote a memory: the assistant, from the user's own words, or someone typing on the memory page. */
@Serializable
enum class MemorySource(val id: String) {
    SAID("said"),
    TYPED("typed"),
    ;

    companion object {
        fun byId(id: String?): MemorySource = entries.firstOrNull { it.id == id } ?: SAID
    }
}

/** One thing the assistant remembers about the user; [said] keeps the words it was told, when it wrote it itself. */
@Serializable
data class UserMemory(
    val id: String,
    val category: MemoryCategory,
    val content: String,
    val source: MemorySource,
    val said: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** The memory page: what the assistant remembers, and whether it uses it at all. */
@Serializable
data class MemoryView(val memories: List<UserMemory> = emptyList(), val enabled: Boolean = true)

@Serializable
@SerialName("memory")
data class MemorySection(val memory: MemoryView = MemoryView()) : StateSection

/** A memory typed on the page: new without [id], else new words (and kind) for that one. */
@Serializable
data class MemoryWriteParams(val category: MemoryCategory, val content: String, val id: String? = null)

/** Whether it was stored ([created] when new); [refusal] says why nothing changed. */
@Serializable
data class MemoryWriteResult(val saved: Boolean, val created: Boolean = false, val refusal: Finding? = null)

@Serializable
data class DoneResult(val done: Boolean)

/** 操作经验: the runtime's lessons in use and those still waiting for more runs. */
@Serializable
data class MemoryLessons(
    val activeCount: Int = 0,
    val pendingCount: Int = 0,
    val active: List<LessonEntry> = emptyList(),
    val pending: List<LessonEntry> = emptyList(),
)

/** The memory page's requests; the memories themselves are state ([MemorySection]). */
object MemoryRequests {
    val SET_ENABLED = RequestMethod("memory/setEnabled", FlagParams.serializer(), Empty.serializer())
    val WRITE = RequestMethod("memory/write", MemoryWriteParams.serializer(), MemoryWriteResult.serializer())
    val DELETE = RequestMethod("memory/delete", IdParams.serializer(), Empty.serializer())

    /** Every memory; false when the disk took nothing. */
    val CLEAR = RequestMethod("memory/clear", Empty.serializer(), DoneResult.serializer())
    val LESSONS = RequestMethod("memory/lessons", Empty.serializer(), MemoryLessons.serializer())
}

/** With the plans' facts, every memory fits the runtime's 32 context facts. */
const val MAX_MEMORIES = 24
const val MAX_PER_CATEGORY = 10
const val MAX_MEMORY_CHARS = 100
