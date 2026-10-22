@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Metadata only, scoped to an owned async writer. Cold readers still validate every record. */
internal class SessionEventAppendCursor(
    val snapshot: FileStreamSnapshot,
    val lastSequence: Long,
    manifestFile: File,
) {
    private val files = listOf(manifestFile) + snapshot.manifest.segments.map {
        File(snapshot.activeSegmentFile.parentFile, segmentFileName(it.index))
    }
    private val stamps = files.map(::stamp)

    // Changes by another writer, replacement, truncation or a link force the full reader path.
    // No event payload is retained here, so a large context does not duplicate the history in RAM.
    fun isUnchanged(manifestFile: File): Boolean = files.first() == manifestFile &&
        stamps.none { it == null } && files.map(::stamp) == stamps

    private fun stamp(file: File): List<Any?>? = runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile)
        listOf(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }.getOrNull()
}

internal data class FileStreamSnapshot(
    val sessionId: String,
    val runId: String,
    val streamDir: File,
    val manifest: StreamManifest,
    val events: List<SessionEvent>,
    val activeSegmentFile: File,
    val activeByteLength: Long,
    val tornTail: Boolean,
)
