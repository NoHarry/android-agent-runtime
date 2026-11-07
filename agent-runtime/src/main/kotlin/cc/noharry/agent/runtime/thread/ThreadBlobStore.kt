package cc.noharry.agent.runtime.thread

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonObject

/** One thread's content-addressed image bytes, shared by visible snapshots and provider history. */
internal class ThreadBlobStore(private val root: File) {
    fun put(bytes: ByteArray): String {
        val sha = storageHash(bytes)
        val target = File(root, sha)
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) atomicWrite(target, bytes)
        return sha
    }

    fun read(sha: String): ByteArray {
        require(SHA256.matches(sha)) { "Invalid thread image reference" }
        val target = File(root, sha)
        check(target.isFile) { "Missing thread image" }
        val bytes = target.readBytes()
        check(storageHash(bytes) == sha) { "Corrupt thread image" }
        return bytes
    }

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}

internal fun imageBytes(value: JsonObject, blobs: ThreadBlobStore?): ByteArray =
    value.optionalText("blobSha256")?.let { checkNotNull(blobs) { "Missing thread image store" }.read(it) }
        ?: Base64.getDecoder().decode(value.text("bytes"))

internal fun storageHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
