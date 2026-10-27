@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.core.state.encodeFileName
import java.io.File

/**
 * Content-addressed image attachments under one durable EventLog root.
 *
 * Layout: `<root>/session-events/v2/attachments/{encodedSession}/{sha256}`. Bytes are written once per
 * session, forced to disk before the event that names them, and checked against their sha256 when read,
 * the same discipline as the per-run `blobs/` spill. Events keep only the [SessionAttachmentRef], so a
 * screenshot repeated across model requests is stored once. Deleting a session's root deletes them.
 */
internal class SessionAttachmentStore(private val canonicalRoot: File) {
    private val writeLock = Any()

    /** Stores [bytes] unless an identical attachment exists; false when [ref] does not match them. */
    fun put(sessionId: String, ref: SessionAttachmentRef, bytes: ByteArray): Boolean {
        if (!SHA256_HEX.matches(ref.sha256) || sha256Hex(bytes) != ref.sha256) return false
        val file = attachmentFile(sessionId, ref.sha256)
        synchronized(writeLock) {
            if (existsNoFollow(file) && file.isFile && file.length() == bytes.size.toLong()) return true
            writeForcedBytes(file, bytes)
        }
        return true
    }

    /** Verified bytes, or null when the attachment is missing or no longer matches its hash. */
    fun read(sessionId: String, sha256: String): ByteArray? {
        if (!SHA256_HEX.matches(sha256)) return null
        val file = attachmentFile(sessionId, sha256)
        if (!existsNoFollow(file) || !file.isFile) return null
        rejectLink(file.toPath())
        return file.readBytes().takeIf { sha256Hex(it) == sha256 }
    }

    private fun attachmentFile(sessionId: String, sha256: String): File =
        containedFile(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            ATTACHMENTS_DIR,
            encodeFileName(sessionId),
            sha256,
        )
}

internal const val ATTACHMENTS_DIR = "attachments"
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
