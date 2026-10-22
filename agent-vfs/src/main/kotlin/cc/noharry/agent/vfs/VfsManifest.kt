package cc.noharry.agent.vfs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Static workspace mount manifest parsed from workspace.json.
 *
 * The manifest describes physical sources and target paths only. Agent-runtime
 * semantics such as AGENTS.md, skills, sessions, memory, and MCP are layered on
 * top by runtime discovery.
 */
data class VfsWorkspaceManifest(
    val schemaVersion: Int,
    val vfsContractVersion: String,
    val bundleVersion: String?,
    val workspaceRoot: VfsPath,
    val mounts: List<VfsMount>,
)

/** Mount entry from workspace.json. */
data class VfsMount(
    val id: String,
    val source: String,
    val target: VfsPath,
    val mode: VfsMountMode,
    val priority: Int,
)

/** Read/write capability of a VFS mount. */
enum class VfsMountMode(val wireName: String) {
    READ_ONLY("readOnly"),
    READ_WRITE("readWrite"),
    ;

    companion object {
        /** Parses the workspace.json wire value. */
        fun fromWire(value: String): VfsMountMode =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unsupported VFS mount mode: $value")
    }
}

/** Parser and validator for workspace.json manifests. */
class VfsManifestLoader {
    /** Parses workspace.json text into a validated manifest. */
    fun parse(jsonText: String): VfsWorkspaceManifest {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val schemaVersion = root.requiredInt(FIELD_SCHEMA_VERSION)
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported VFS manifest schemaVersion: $schemaVersion"
        }
        val workspaceRoot = root.requiredPath(FIELD_WORKSPACE_ROOT)
        require(workspaceRoot.value == ROOT_PATH) {
            "workspaceRoot must be /"
        }
        val mounts = root.requiredArray(FIELD_MOUNTS).map { element ->
            parseMount(element.jsonObject)
        }
        require(mounts.isNotEmpty()) {
            "workspace.json must contain at least one mount"
        }
        return VfsWorkspaceManifest(
            schemaVersion = schemaVersion,
            vfsContractVersion = root.requiredString(FIELD_VFS_CONTRACT_VERSION),
            bundleVersion = root.optionalString(FIELD_BUNDLE_VERSION),
            workspaceRoot = workspaceRoot,
            mounts = mounts,
        )
    }

    private fun parseMount(item: JsonObject): VfsMount =
        VfsMount(
            id = item.requiredString(FIELD_ID),
            source = item.requiredString(FIELD_SOURCE),
            target = item.requiredPath(FIELD_TARGET),
            mode = VfsMountMode.fromWire(item.requiredString(FIELD_MODE)),
            priority = item.optionalInt(FIELD_PRIORITY) ?: DEFAULT_MOUNT_PRIORITY,
        )
}

private fun JsonObject.requiredString(name: String): String =
    optionalString(name) ?: throw IllegalArgumentException("Missing string field: $name")

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.requiredInt(name: String): Int =
    optionalInt(name) ?: throw IllegalArgumentException("Missing integer field: $name")

private fun JsonObject.optionalInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name] as? JsonArray ?: throw IllegalArgumentException("Missing array field: $name")

private fun JsonObject.requiredPath(name: String): VfsPath =
    VfsPathResolver.normalize(requiredString(name))
        ?: throw IllegalArgumentException("Invalid VFS path field: $name")

private const val SUPPORTED_SCHEMA_VERSION = 1
private const val ROOT_PATH = "/"
private const val DEFAULT_MOUNT_PRIORITY = 0
private const val FIELD_SCHEMA_VERSION = "schemaVersion"
private const val FIELD_VFS_CONTRACT_VERSION = "vfsContractVersion"
private const val FIELD_BUNDLE_VERSION = "bundleVersion"
private const val FIELD_WORKSPACE_ROOT = "workspaceRoot"
private const val FIELD_MOUNTS = "mounts"
private const val FIELD_ID = "id"
private const val FIELD_SOURCE = "source"
private const val FIELD_TARGET = "target"
private const val FIELD_MODE = "mode"
private const val FIELD_PRIORITY = "priority"
