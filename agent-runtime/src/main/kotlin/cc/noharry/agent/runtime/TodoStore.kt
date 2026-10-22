package cc.noharry.agent.runtime

import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import cc.noharry.agent.kernel.core.TodoItem
import cc.noharry.agent.kernel.core.TodoState
import cc.noharry.agent.kernel.core.TodoStatus
import cc.noharry.agent.kernel.core.TodoStorePort
import cc.noharry.agent.kernel.core.encodeFileName
import cc.noharry.agent.kernel.core.decodeOrNull
import cc.noharry.agent.kernel.core.writeReliabilityText

/**
 * Persists todo state per session in the runtime workspace.
 *
 * File names are encoded via [encodeFileName] to prevent path traversal
 * (mem-008: run artifact directories must not use raw request/session ids).
 */
internal class TodoStore private constructor(
    private val root: File?,
    private val workspace: VfsFileSystem?,
) : TodoStorePort {
    constructor(root: File) : this(root, null)
    constructor(workspace: VfsFileSystem) : this(null, workspace)

    private val todoDir = root?.let { File(it, TODO_DIR_NAME).apply { mkdirs() } }

    override suspend fun load(sessionId: String): TodoState? {
        val file = todoDir?.let { File(it, fileName(sessionId)) }
        if (file != null) return file.decodeOrNull(::decodeTodoState)
        val result = workspace?.read(todoPath(sessionId), 0, MAX_TODO_STATE_BYTES) ?: return null
        return (result as? VfsReadResult.Found)
            ?.let { runCatching { decodeTodoState(it.text) }.getOrNull() }
    }

    override suspend fun save(sessionId: String, state: TodoState): Boolean {
        val content = encodeTodoState(state)
        val file = todoDir?.let { File(it, fileName(sessionId)) }
        if (file != null) {
            file.writeReliabilityText(content)
            return true
        }
        return workspace?.write(todoPath(sessionId), content, VfsWriteMode.OVERWRITE)
            .let { it is VfsWriteResult.Written }
    }

    override suspend fun clear(sessionId: String): Boolean {
        val file = todoDir?.let { File(it, fileName(sessionId)) }
        if (file != null) return file.delete()
        return workspace?.deleteTree(todoPath(sessionId)) == true
    }
}

private fun fileName(sessionId: String): String = encodeFileName(sessionId) + ".json"

private fun todoPath(sessionId: String): String = "$TODO_VFS_ROOT/${fileName(sessionId)}"

private const val TODO_DIR_NAME = "todo"
private const val TODO_VFS_ROOT = "/memory/todo"
private const val MAX_TODO_STATE_BYTES = 64 * 1024

private fun encodeTodoState(state: TodoState): String = buildJsonObject {
    put("sessionId", state.sessionId)
    putJsonArray("items") {
        state.items.forEach { item ->
            add(buildJsonObject {
                put("content", item.content)
                put("status", item.status.name)
            })
        }
    }
    put("updatedAtEpochMillis", state.updatedAtEpochMillis)
}.toString()

private fun decodeTodoState(value: String): TodoState {
    val json = Json.parseToJsonElement(value) as JsonObject
    val items = (json["items"] as? JsonArray).orEmpty().map { element ->
        val obj = element as JsonObject
        TodoItem(
            content = obj.stringField("content"),
            status = TodoStatus.fromWire(obj.stringField("status")),
        )
    }
    return TodoState(
        sessionId = json.stringField("sessionId"),
        items = items,
        updatedAtEpochMillis = json.longField("updatedAtEpochMillis"),
    )
}

private fun JsonObject.stringField(name: String): String =
    (this[name] as? JsonPrimitive)?.content ?: ""

private fun JsonObject.longField(name: String): Long =
    (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
