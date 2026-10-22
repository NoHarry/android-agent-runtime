package cc.noharry.agent.vfs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Host adapter for controlled Room/SQLite virtual resources. Candidate API. */
interface VfsRoomResourceAdapter {
    fun schemaJson(): String

    fun resources(): List<VfsRoomResource>

    fun readResource(resource: String): String?

    fun queryNames(): List<String> = emptyList()

    fun readQuery(name: String): String? = null

    fun updateRow(payload: VfsRoomUpdateRow): Boolean
}

/** One virtual Room resource exposed through VFS, never a physical `.db` file. */
data class VfsRoomResource(
    val name: String,
    val displayPath: String = "$name.json",
    val searchableText: String = "",
)

/** Controlled row update payload for Room resources. */
data class VfsRoomUpdateRow(
    val resource: String,
    val key: String,
    val fields: Map<String, String>,
    val expectedVersion: String? = null,
)

/** Controlled Room/SQLite VFS source backed by a host-provided adapter. */
class VfsRoomSource(
    private val adapter: VfsRoomResourceAdapter,
    private val writableResources: Set<String> = emptySet(),
) : VfsSource {
    private val byteCache = VfsTextByteCache(VFS_COUNTER_ROOM_READ_FULL_ENCODES)

    override suspend fun list(relativePath: String): VfsListResult {
        return when (clean(relativePath) ?: return VfsListResult.InvalidPath) {
            "" -> VfsListResult.Entries(rootEntries())
            RESOURCES_DIR -> VfsListResult.Entries(
                adapter.resources().map { VfsEntry(it.displayPath, VFS_ENTRY_FILE) }.sortedBy { it.name },
            )
            QUERIES_DIR -> queryEntries()
            else -> VfsListResult.NotFound
        }
    }

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (offset < 0 || maxBytes <= 0) return VfsReadResult.InvalidPath
        val path = clean(relativePath) ?: return VfsReadResult.InvalidPath
        if (path.endsWith(DB_SUFFIX)) return physicalDatabaseDenied()
        val text = when {
            path == SCHEMA_FILE -> adapter.schemaJson()
            path.startsWith(RESOURCES_PREFIX) -> adapter.readResource(path.resourceName(RESOURCES_PREFIX))
            path.startsWith(QUERIES_PREFIX) -> adapter.readQuery(path.resourceName(QUERIES_PREFIX))
            else -> null
        } ?: return VfsReadResult.NotFound
        return text.window(path, offset, maxBytes, byteCache)
    }

    override suspend fun stat(relativePath: String): VfsStatResult {
        val path = clean(relativePath) ?: return VfsStatResult.InvalidPath
        if (path.endsWith(DB_SUFFIX)) {
            return VfsStatResult.Error(ERROR_ACCESS, "Physical database files are not exposed.")
        }
        if (path.isBlank() || path == RESOURCES_DIR || path == QUERIES_DIR) {
            return VfsStatResult.Found(VfsStat(type = VFS_ENTRY_DIR))
        }
        val text = when {
            path == SCHEMA_FILE -> adapter.schemaJson()
            path.startsWith(RESOURCES_PREFIX) -> adapter.readResource(path.resourceName(RESOURCES_PREFIX))
            path.startsWith(QUERIES_PREFIX) -> adapter.readQuery(path.resourceName(QUERIES_PREFIX))
            else -> null
        } ?: return VfsStatResult.NotFound
        return VfsStatResult.Found(
            VfsStat(
                type = VFS_ENTRY_FILE,
                sizeBytes = text.utf8EncodedSize().toLong(),
            ),
        )
    }

    override suspend fun write(
        relativePath: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String?,
    ): VfsWriteResult {
        if (expectedRevision != null) return VfsWriteResult.Conflict
        if (mode != VfsWriteMode.OVERWRITE) {
            return VfsWriteResult.Error(ERROR_NOT_SUPPORTED, "Room resources only support controlled update_row.")
        }
        if (containsDeniedSql(content)) {
            return VfsWriteResult.Error(ERROR_PERMISSION, "Destructive SQL is not supported.")
        }
        val payload = parseUpdate(content) ?: return VfsWriteResult.InvalidPath
        if (payload.resource !in writableResources) {
            return VfsWriteResult.Error(ERROR_PERMISSION, "Room resource is not writable.")
        }
        val path = clean(relativePath) ?: return VfsWriteResult.InvalidPath
        if (path.endsWith(DB_SUFFIX)) {
            return VfsWriteResult.Error(ERROR_ACCESS, "Physical database files are not exposed.")
        }
        if (!path.startsWith(RESOURCES_PREFIX) || path.resourceName(RESOURCES_PREFIX) != payload.resource) {
            return VfsWriteResult.Error(ERROR_PERMISSION, "Write path must match the controlled Room resource.")
        }
        return if (adapter.updateRow(payload)) {
            byteCache.invalidate(path)
            VfsWriteResult.Written(content.toByteArray(Charsets.UTF_8).size.toLong())
        } else {
            VfsWriteResult.NotFound
        }
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        if (pattern.isEmpty() || maxMatches < 0) return VfsSearchResult.InvalidPattern
        val path = clean(relativePath) ?: return VfsSearchResult.InvalidPath
        if (path.endsWith(DB_SUFFIX)) return VfsSearchResult.Error(ERROR_ACCESS, "Physical database files are not exposed.")
        val files = searchableFiles().filter { it.path == path || path.isBlank() || it.path.startsWith("$path/") }
        if (files.isEmpty()) return VfsSearchResult.NotFound
        return searchFiles(files, pattern, maxMatches, maxScanBytes)
    }

    private fun rootEntries(): List<VfsEntry> =
        buildList {
            add(VfsEntry(SCHEMA_FILE, VFS_ENTRY_FILE))
            add(VfsEntry(RESOURCES_DIR, VFS_ENTRY_DIR))
            if (adapter.queryNames().isNotEmpty()) add(VfsEntry(QUERIES_DIR, VFS_ENTRY_DIR))
        }

    private fun queryEntries(): VfsListResult =
        adapter.queryNames()
            .map { VfsEntry(it.withJsonSuffix(), VFS_ENTRY_FILE) }
            .sortedBy { it.name }
            .let { entries -> if (entries.isEmpty()) VfsListResult.NotFound else VfsListResult.Entries(entries) }

    private fun searchableFiles(): List<RoomSearchFile> =
        adapter.resources().mapNotNull { resource ->
            val text = adapter.readResource(resource.name) ?: resource.searchableText.takeIf { it.isNotBlank() }
            text?.let { RoomSearchFile("$RESOURCES_DIR/${resource.displayPath}", it) }
        } + adapter.queryNames().mapNotNull { name ->
            adapter.readQuery(name)?.let { RoomSearchFile("$QUERIES_DIR/${name.withJsonSuffix()}", it) }
        }
}

private data class RoomSearchFile(val path: String, val text: String)

private fun searchFiles(
    files: List<RoomSearchFile>,
    pattern: String,
    maxMatches: Int,
    maxScanBytes: Long,
): VfsSearchResult {
    val matcher = VfsSearchMatcher(pattern)
    var scanned = 0L
    val matches = mutableListOf<VfsSearchMatch>()
    files.forEach { file ->
        scanned += file.text.utf8EncodedSize()
        if (scanned > maxScanBytes) return VfsSearchResult.BudgetExceeded
        file.text.lineSequence().forEachIndexed { index, line ->
            if (matches.size < maxMatches && matcher.matches(line)) {
                matches += VfsSearchMatch(file.path, index + 1, line)
            }
        }
    }
    return VfsSearchResult.Matches(matches, scannedBytes = scanned)
}

private fun parseUpdate(content: String): VfsRoomUpdateRow? {
    val root = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
    if (root.string(OPERATION_FIELD) != UPDATE_ROW_OPERATION) return null
    val fields = (root[FIELDS_FIELD] as? JsonObject)?.mapValues { it.value.scalarText() } ?: return null
    return VfsRoomUpdateRow(
        resource = root.string(RESOURCE_FIELD) ?: return null,
        key = root.string(KEY_FIELD) ?: return null,
        fields = fields,
        expectedVersion = root.string(EXPECTED_VERSION_FIELD),
    )
}

private fun String.window(
    path: String,
    offset: Long,
    maxBytes: Int,
    byteCache: VfsTextByteCache,
): VfsReadResult.Found {
    val bytes = byteCache.bytes(path, this)
    val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
    val end = (start + maxBytes).coerceAtMost(bytes.size)
    return VfsReadResult.Found(bytes.copyOfRange(start, end).toString(Charsets.UTF_8), end - start, bytes.size.toLong())
}

private fun clean(path: String): String? =
    VfsPathResolver.normalize(if (path.startsWith("/")) path else "/$path")
        ?.value
        ?.trim('/')

private fun String.resourceName(prefix: String): String =
    removePrefix(prefix).removeSuffix(JSON_SUFFIX).trim('/')

private fun String.withJsonSuffix(): String =
    if (endsWith(JSON_SUFFIX)) this else "$this$JSON_SUFFIX"

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonElement.scalarText(): String =
    jsonPrimitive.contentOrNull ?: toString()

private fun physicalDatabaseDenied(): VfsReadResult =
    VfsReadResult.Error(ERROR_ACCESS, "Physical database files are not exposed.")

private fun containsDeniedSql(text: String): Boolean {
    val normalized = text.uppercase()
    return DENIED_SQL_KEYWORDS.any { keyword -> Regex("""\b$keyword\b""").containsMatchIn(normalized) } ||
        normalized.contains(PRAGMA_WRITABLE_SCHEMA)
}

private const val SCHEMA_FILE = "schema.json"
private const val RESOURCES_DIR = "resources"
private const val QUERIES_DIR = "queries"
private const val RESOURCES_PREFIX = "$RESOURCES_DIR/"
private const val QUERIES_PREFIX = "$QUERIES_DIR/"
private const val JSON_SUFFIX = ".json"
private const val DB_SUFFIX = ".db"
private const val OPERATION_FIELD = "operation"
private const val RESOURCE_FIELD = "resource"
private const val KEY_FIELD = "key"
private const val FIELDS_FIELD = "fields"
private const val EXPECTED_VERSION_FIELD = "expectedVersion"
private const val UPDATE_ROW_OPERATION = "update_row"
private const val ERROR_PERMISSION = "EPERM"
private const val ERROR_ACCESS = "EACCES"
private const val ERROR_NOT_SUPPORTED = "ENOTSUP"
private const val PRAGMA_WRITABLE_SCHEMA = "PRAGMA WRITABLE_SCHEMA"
private val DENIED_SQL_KEYWORDS = listOf("DROP", "ALTER", "CREATE", "DELETE", "ATTACH", "DETACH", "VACUUM")
