package cc.noharry.agent.appserver.runtime

import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.protocol.view.GENERATED_IMAGES_DIR
import java.io.File
import kotlin.random.Random

/*
 * Files the server keeps for the screens to show (a generated picture, a page the model drew), each under a folder of
 * the conversation it belongs to. Pictures are the server's own store (`files/generated-images/`): the picture plugin
 * draws into a conversation's folder, a delegation's report brings its pictures into a task's, the cards plugin reads
 * any of them; a conversation's folder goes with the conversation and all of them with the runtime data.
 */

/** A conversation's picture folder (base64url of its id, as the HTML artifacts use), so its pictures go with it. */
@AppServerPluginApi
fun sessionPicturesDir(context: Context, sessionId: String): File =
    File(File(context.filesDir, GENERATED_IMAGES_DIR), encodeSessionId(sessionId.ifBlank { UNKNOWN_SESSION }))

/** Deletes the pictures of one conversation; true when nothing of it is left. */
internal fun deleteSessionGeneratedImages(context: Context, sessionId: String): Boolean {
    val directory = sessionPicturesDir(context, sessionId)
    return !directory.exists() || runCatching { directory.deleteRecursively() }.getOrDefault(false)
}

/** The file behind a saved picture's reference (`generated-images/<folder>/<name>`); null for anything else. */
@AppServerPluginApi
fun generatedImageFile(context: Context, reference: String): File? {
    val root = File(context.filesDir, GENERATED_IMAGES_DIR).canonicalFile
    val file = File(context.filesDir, reference).canonicalFile
    return file.takeIf { it.parentFile?.parentFile == root && it.isFile }
}

/** The extension a picture's bytes say it has: png, webp, or jpg for anything else. */
@AppServerPluginApi
fun imageExtension(bytes: ByteArray): String = when {
    bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
    bytes.size > 11 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> "jpg"
}

/**
 * A name for a file kept for the screens: [prefix] plus the time in base 36 and four random letters. Trace redaction treats a run of
 * ten or more digits, spaces and dashes as a phone number, so a dated name ("20261009-172728") would reach the
 * transcript, and the overlay, as `[REDACTED_PHONE]`.
 */
@AppServerPluginApi
fun screenFileName(prefix: String, nowMillis: Long = System.currentTimeMillis(), random: Random = Random.Default): String =
    "$prefix-${nowMillis.toString(NAME_RADIX)}-" + (1..NAME_SUFFIX_LETTERS).map { NAME_LETTERS[random.nextInt(NAME_LETTERS.length)] }.joinToString("")

private const val UNKNOWN_SESSION = "unknown-session"
private const val NAME_RADIX = 36
private const val NAME_SUFFIX_LETTERS = 4
private const val NAME_LETTERS = "abcdefghjkmnpqrstuvwxyz"
