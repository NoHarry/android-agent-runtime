package cc.noharry.agent.vfs

import java.security.MessageDigest

/** Replayable snapshot of a VFS manifest installation. */
data class VfsSnapshot(
    val vfsContractVersion: String,
    val manifestHash: String,
    val capturedAtEpochMillis: Long,
    val mounts: List<VfsSnapshotMount>,
)

/** Replayable snapshot entry for one mount. */
data class VfsSnapshotMount(
    val id: String,
    val source: String,
    val target: String,
    val mode: String,
    val priority: Int,
)

/** Creates VFS snapshots for replay and diagnostics. */
object VfsSnapshotFactory {
    /** Creates a snapshot from the manifest and the exact workspace.json text. */
    fun snapshot(
        manifest: VfsWorkspaceManifest,
        manifestJson: String,
        capturedAtEpochMillis: Long,
    ): VfsSnapshot =
        VfsSnapshot(
            vfsContractVersion = manifest.vfsContractVersion,
            manifestHash = sha256(manifestJson),
            capturedAtEpochMillis = capturedAtEpochMillis,
            mounts = manifest.mounts.map { mount ->
                VfsSnapshotMount(
                    id = mount.id,
                    source = mount.source,
                    target = mount.target.value,
                    mode = mount.mode.wireName,
                    priority = mount.priority,
                )
            },
        )

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private const val SHA_256 = "SHA-256"
