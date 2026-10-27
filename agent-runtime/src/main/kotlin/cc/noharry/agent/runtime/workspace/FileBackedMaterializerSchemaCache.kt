package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.core.state.decodeOrNull
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.kernel.core.state.writeReliabilityText
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaCacheEntry
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaMetadata
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_SCHEMA_CACHE_CLEANUP_SCANS
import cc.noharry.agent.kernel.core.trace.RuntimeVfsPerformanceCounters
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal interface MaterializerSchemaCacheStore {
    fun read(toolName: String, nowEpochMillis: Long): MaterializedSchemaCacheEntry?
    fun write(toolName: String, entry: MaterializedSchemaCacheEntry, nowEpochMillis: Long)
    fun cleanupExpired(nowEpochMillis: Long): Int
}

internal class FileBackedMaterializerSchemaCache(
    private val root: File,
    private val maxEntries: Int = DEFAULT_MATERIALIZER_SCHEMA_CACHE_MAX_ENTRIES,
) : MaterializerSchemaCacheStore {
    init {
        require(maxEntries > 0) { "maxEntries must be positive." }
    }

    @Synchronized
    override fun read(toolName: String, nowEpochMillis: Long): MaterializedSchemaCacheEntry? {
        val entry = schemaFile(toolName).decodeOrNull(::decodeSchemaCacheEntry) ?: return null
        if (entry.metadata.expiresAtEpochMillis <= nowEpochMillis) {
            schemaFile(toolName).delete()
            writeMarker(nowEpochMillis, expiredDeleted = 1, overflowDeleted = 0)
            return null
        }
        return entry
    }

    @Synchronized
    override fun write(
        toolName: String,
        entry: MaterializedSchemaCacheEntry,
        nowEpochMillis: Long,
    ) {
        val expiredDeleted = cleanupExpired(nowEpochMillis)
        schemaFile(toolName).writeReliabilityText(encodeSchemaCacheEntry(entry))
        val overflowDeleted = enforceMaxEntries()
        writeMarker(nowEpochMillis, expiredDeleted, overflowDeleted)
    }

    @Synchronized
    override fun cleanupExpired(nowEpochMillis: Long): Int {
        RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_SCHEMA_CACHE_CLEANUP_SCANS)
        val deleted = schemaFiles()
            .filter { file ->
                val entry = file.decodeOrNull(::decodeSchemaCacheEntry)
                entry == null || entry.metadata.expiresAtEpochMillis <= nowEpochMillis
            }
            .count { it.delete() }
        if (deleted > 0) {
            writeMarker(nowEpochMillis, expiredDeleted = deleted, overflowDeleted = 0)
        }
        return deleted
    }

    private fun enforceMaxEntries(): Int {
        val deleteTargets = schemaFiles()
            .sortedByDescending { it.lastModified() }
            .drop(maxEntries)
        deleteTargets.forEach { it.delete() }
        return deleteTargets.size
    }

    private fun writeMarker(
        nowEpochMillis: Long,
        expiredDeleted: Int,
        overflowDeleted: Int,
    ) {
        File(root, MATERIALIZER_SCHEMA_CACHE_MARKER_FILE).writeReliabilityText(
            listOf(
                "schemaCacheMode=FILE_BACKED",
                "schemaCacheMaxEntries=$maxEntries",
                "lastCleanupAtEpochMillis=$nowEpochMillis",
                "lastExpiredDeleted=$expiredDeleted",
                "lastOverflowDeleted=$overflowDeleted",
                "schemaCacheEntryCount=${schemaFiles().size}",
            ).joinToString(separator = "\n", postfix = "\n"),
        )
    }

    private fun schemaFiles(): List<File> =
        cacheDir().listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }

    private fun schemaFile(toolName: String): File =
        File(cacheDir(), "${encodeFileName(toolName)}.json")

    private fun cacheDir(): File = File(root, MATERIALIZER_SCHEMA_CACHE_DIR)
}

private fun encodeSchemaCacheEntry(entry: MaterializedSchemaCacheEntry): String =
    buildJsonObject {
        put("content", entry.content)
        put("schemaVersion", entry.metadata.schemaVersion)
        put("schemaHash", entry.metadata.schemaHash)
        put("expiresAtEpochMillis", entry.metadata.expiresAtEpochMillis)
    }.toString()

private fun decodeSchemaCacheEntry(value: String): MaterializedSchemaCacheEntry {
    val json = Json.parseToJsonElement(value).jsonObject
    return MaterializedSchemaCacheEntry(
        content = json.string("content"),
        metadata = MaterializedSchemaMetadata(
            schemaVersion = json.string("schemaVersion"),
            schemaHash = json.string("schemaHash"),
            expiresAtEpochMillis = json.long("expiresAtEpochMillis"),
        ),
    )
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: ""

private fun kotlinx.serialization.json.JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: 0L

internal const val MATERIALIZER_SCHEMA_CACHE_DIR = "materializer-schema-cache"
internal const val MATERIALIZER_SCHEMA_CACHE_MARKER_FILE =
    "materializer-schema-cache.properties"
private const val DEFAULT_MATERIALIZER_SCHEMA_CACHE_MAX_ENTRIES = 128
