package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaMetadata
import java.security.MessageDigest

internal fun materializedSchemaMetadata(
    schemaJson: String,
    expiresAtEpochMillis: Long,
): MaterializedSchemaMetadata {
    val hash = sha256(schemaJson)
    return MaterializedSchemaMetadata(
        schemaVersion = "$SCHEMA_HASH_VERSION_PREFIX${hash.take(SCHEMA_VERSION_HASH_CHARS)}",
        schemaHash = hash,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private const val SCHEMA_HASH_VERSION_PREFIX = "sha256:"
private const val SCHEMA_VERSION_HASH_CHARS = 12
