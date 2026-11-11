package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.evidence.RuntimeMemoryEvidence
import cc.noharry.agent.appserver.protocol.ContextPanelState
import cc.noharry.agent.appserver.protocol.LessonEntry
import cc.noharry.agent.appserver.protocol.SkillEntry
import cc.noharry.agent.appserver.protocol.TodoEntry
import cc.noharry.agent.vfs.VFS_ENTRY_DIR
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Loads the file-backed part of the debug context panel: lessons and skills only.
 *
 * Todo is never loaded here. It is exclusively event-sourced from the public `update_todo`
 * NativeToolCallFinished payload (see [decodeTodoToolResult]) and projected by the session
 * runner, so any todo JSON left under `memory/todo` by earlier builds must not reach the UI.
 * Callers are responsible for supplying the projected board.
 *
 * Lessons remain file-based: `memory/lessons/{base64url(scene)}/lessons.json`. File-format
 * contracts are pinned in the matching unit test.
 *
 * Skills are host-provided runtime context entries. The panel mirrors the SKILL.md
 * frontmatter actually present in the mounted workspace assets.
 */
internal suspend fun loadContextPanel(context: Context): ContextPanelState {
    val workspaceRoot = appWorkspaceRoot(context)
    val workspace = workspaceVfs(context, workspaceRoot)
    val lessons = readLessons(workspace)
    val skills = readSkills(workspace)
    return ContextPanelState(
        visible = true,
        todo = emptyList(),
        lessons = lessons,
        skills = skills,
    )
}

internal suspend fun loadRuntimeMemoryEvidence(context: Context): RuntimeMemoryEvidence {
    val workspaceRoot = appWorkspaceRoot(context)
    val workspace = workspaceVfs(context, workspaceRoot)
    val active = readLessons(workspace)
    val pending = readPendingLessons(workspace)
    return RuntimeMemoryEvidence(
        activeCount = active.size,
        pendingCount = pending.size,
        active = active,
        pending = pending,
    )
}

/**
 * Decodes the public `update_todo` NativeToolCallFinished payload
 * (`{"ok":true,"todoCount":N,"todos":[{"content":..,"status":..}]}`) emitted by the runtime.
 * Returns null for failure payloads or non-todo JSON so the board keeps its last state.
 */
internal fun decodeTodoToolResult(resultJson: String): List<TodoEntry>? {
    val json = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
    if ((json["ok"] as? JsonPrimitive)?.content != "true") return null
    val todos = json["todos"] as? JsonArray ?: return null
    return todos.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        TodoEntry(
            content = obj.stringOrEmpty("content"),
            status = obj.stringOrEmpty("status"),
        )
    }
}

/** Reads all promoted lessons from the manifest-backed workspace VFS. */
internal suspend fun readLessons(workspace: VfsFileSystem): List<LessonEntry> {
    return readLessonEntries(workspace, LESSONS_FILE)
}

/** Reads all pending lessons from the manifest-backed workspace VFS. */
internal suspend fun readPendingLessons(workspace: VfsFileSystem): List<LessonEntry> {
    return readLessonEntries(workspace, PENDING_FILE)
}

private suspend fun readLessonEntries(workspace: VfsFileSystem, fileName: String): List<LessonEntry> {
    val scenes = when (val result = workspace.list("$MEMORY_ROOT/$LESSONS_DIR")) {
        is VfsListResult.Entries -> result.entries.filter { it.type == VFS_ENTRY_DIR }
        else -> emptyList()
    }
    return scenes.flatMap { scene ->
        val lessonsJson = workspace.readTextOrNull("$MEMORY_ROOT/$LESSONS_DIR/${scene.name}/$fileName")
            ?: return@flatMap emptyList()
        decodeLessons(scene.name, lessonsJson)
    }
}

/** Reads all promoted lessons across scene dirs; internal for file-format unit testing. */
internal fun readLessons(memoryDir: File): List<LessonEntry> {
    return readLessonEntries(memoryDir, LESSONS_FILE)
}

/** Reads all pending lessons across scene dirs; internal for file-format unit testing. */
internal fun readPendingLessons(memoryDir: File): List<LessonEntry> {
    return readLessonEntries(memoryDir, PENDING_FILE)
}

private fun readLessonEntries(memoryDir: File, fileName: String): List<LessonEntry> {
    val lessonsRoot = File(memoryDir, LESSONS_DIR)
    if (!lessonsRoot.isDirectory) return emptyList()
    return lessonsRoot.listFiles { f -> f.isDirectory }.orEmpty().flatMap { sceneDir ->
        val file = File(sceneDir, fileName)
        if (!file.isFile) return@flatMap emptyList()
        decodeLessons(sceneDir.name, file.readText())
    }
}

private fun decodeLessons(encodedSceneName: String, value: String): List<LessonEntry> {
    val json = runCatching { Json.parseToJsonElement(value) as JsonArray }.getOrNull() ?: return emptyList()
    return json.mapNotNull { element ->
        val obj = element.jsonObject
        LessonEntry(
            scene = obj.stringOrNull("scene") ?: encodedSceneName.decodeSceneName(),
            content = obj.stringOrEmpty("content"),
            confidence = obj.floatOrZero("confidence"),
            hitCount = obj.intOrZero("hitCount"),
        )
    }
}

internal suspend fun readSkills(workspace: VfsFileSystem): List<SkillEntry> {
    val listed = workspace.list(SKILLS_ROOT) as? VfsListResult.Entries ?: return emptyList()
    return listed.entries
        .filter { it.type == VFS_ENTRY_DIR }
        .mapNotNull { entry ->
            workspace.readTextOrNull("$SKILLS_ROOT/${entry.name}/SKILL.md")
                ?.let { content -> decodeSkill(entry.name, content) }
        }
}

private fun decodeSkill(directoryName: String, content: String): SkillEntry {
    val frontmatter = parseSkillFrontmatter(content)
    return SkillEntry(
        name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: directoryName,
        description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: "Workspace skill at $SKILLS_ROOT/$directoryName/SKILL.md",
    )
}

private fun parseSkillFrontmatter(content: String): Map<String, String> {
    if (!content.startsWith(FRONTMATTER_MARKER)) return emptyMap()
    val end = content.indexOf(FRONTMATTER_END_MARKER, startIndex = FRONTMATTER_MARKER.length)
    if (end <= 0) return emptyMap()
    return content.substring(FRONTMATTER_MARKER.length, end)
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator < 0) return@mapNotNull null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().trim('"')
            key.takeIf { it.isNotBlank() }?.let { it to value }
        }
        .toMap()
}

/** Mirrors the SDK internal Base64 URL-safe no-padding file-name encoding: a conversation's folder name. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun encodeSessionId(sessionId: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(sessionId.toByteArray(Charsets.UTF_8))

/** Inverse of encodeSessionId; best-effort for display only. */
private fun String.decodeSceneName(): String =
    runCatching { Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8) }.getOrDefault(this)

private fun JsonObject.stringOrEmpty(name: String): String =
    (this[name] as? JsonPrimitive)?.content ?: ""

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.content?.takeIf { it != "null" }

private fun JsonObject.floatOrZero(name: String): Float =
    (this[name] as? JsonPrimitive)?.floatOrNull ?: 0f

private fun JsonObject.intOrZero(name: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull ?: 0

private suspend fun VfsFileSystem.readTextOrNull(path: String): String? =
    when (val result = read(path, offset = 0, maxBytes = MAX_DEBUG_CONTEXT_READ_BYTES)) {
        is VfsReadResult.Found -> result.text
        else -> null
    }

internal const val DEBUG_UPDATE_TODO_TOOL_NAME = "update_todo"

private const val MEMORY_ROOT = "/memory"
private const val SKILLS_ROOT = "/.agents/skills"
private const val LESSONS_DIR = "lessons"
private const val LESSONS_FILE = "lessons.json"
private const val PENDING_FILE = "pending.json"
private const val MAX_DEBUG_CONTEXT_READ_BYTES = 256 * 1024
private const val FRONTMATTER_MARKER = "---"
private const val FRONTMATTER_END_MARKER = "\n---"
