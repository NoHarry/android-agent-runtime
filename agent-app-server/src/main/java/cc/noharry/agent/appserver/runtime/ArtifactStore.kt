package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.SessionToolCall
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persists agent-written HTML pages into a per-session folder inside the app-private
 * workspace so they survive run scratch cleanup and stay browsable under the `/` mount:
 *
 *   <workspaceRoot>/artifacts/<base64url(sessionId)>/<vfs-relative-path>.html
 *
 * The runtime isolates `/scratch` writes per run and deletes them at run end; the page
 * source is recovered from the persisted `write_file` arguments instead.
 */
internal object ArtifactStore {

    /** Writes every successful HTML `write_file` in [tools] to the session folder. Returns files written. */
    fun persistHtmlArtifacts(
        workspaceRoot: File,
        sessionId: String,
        tools: List<SessionToolCall>,
    ): Int {
        if (tools.isEmpty() || sessionId.isBlank()) return 0
        val dir = sessionArtifactsDir(workspaceRoot, sessionId)
        var written = 0
        tools.forEach { tool ->
            val artifact = tool.htmlArtifact() ?: return@forEach
            val target = resolveArtifactFile(dir, artifact.path) ?: return@forEach
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(artifact.html, Charsets.UTF_8)
                written += 1
            }
        }
        return written
    }

    fun deleteSessionArtifacts(workspaceRoot: File, sessionId: String): Boolean {
        val dir = sessionArtifactsDir(workspaceRoot, sessionId)
        return !dir.exists() || runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    fun sessionArtifactsDir(workspaceRoot: File, sessionId: String): File =
        File(workspaceRoot, "$ARTIFACTS_DIR/${encodeSessionId(sessionId)}")

    private fun resolveArtifactFile(sessionDir: File, vfsPath: String): File? {
        val relative = vfsPath.removePrefix("/")
        if (relative.isBlank() || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        val root = sessionDir.canonicalFile
        val file = File(root, relative).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun SessionToolCall.htmlArtifact(): HtmlArtifact? {
        if (toolName != WRITE_FILE_TOOL || !status.equals(STATUS_OK, ignoreCase = true)) return null
        val args = argumentsPreview.parseJson() ?: return null
        val result = resultPreview.parseJson() ?: return null
        if (result[RESULT_OK_FIELD]?.jsonPrimitive?.booleanOrNull != true) return null
        val path = args.stringField("path") ?: return null
        val lower = path.lowercase()
        if (HTML_SUFFIXES.none { lower.endsWith(it) }) return null
        val html = args.stringField("content")?.takeIf { it.isNotBlank() } ?: return null
        return HtmlArtifact(path = path, html = html)
    }

    private fun String.parseJson(): JsonObject? =
        runCatching { ArtifactStoreJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private data class HtmlArtifact(val path: String, val html: String)

    private val ArtifactStoreJson = Json { ignoreUnknownKeys = true }
    private val HTML_SUFFIXES = listOf(".html", ".htm")
    private const val ARTIFACTS_DIR = "artifacts"
    private const val WRITE_FILE_TOOL = "write_file"
    private const val STATUS_OK = "OK"
    private const val RESULT_OK_FIELD = "ok"
}
