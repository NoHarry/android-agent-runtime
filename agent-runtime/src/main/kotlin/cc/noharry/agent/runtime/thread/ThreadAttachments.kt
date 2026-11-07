package cc.noharry.agent.runtime.thread

import cc.noharry.agent.InputPart
import cc.noharry.agent.TurnInput
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Resolves references only from this thread's configured attachment store. Visible items retain the original references. */
internal suspend fun resolveThreadAttachments(
    input: TurnInput,
    read: suspend (String) -> ByteArray?,
): TurnInput = input.copy(parts = input.parts.map { part ->
    if (part !is InputPart.Attachment) part else {
        val ref = part.ref
        val bytes = read(ref.sha256) ?: throw ThreadInputException("ATTACHMENT_NOT_FOUND", "The attachment is missing or corrupt: ${ref.logicalName}")
        val mediaType = ref.mediaType.substringBefore(';').trim().lowercase()
        when {
            mediaType in IMAGE_TYPES -> InputPart.Image(bytes, mediaType)
            mediaType.startsWith("text/") || mediaType == "application/json" ->
                InputPart.Text("[Attachment: ${ref.logicalName}]\n${decodeTextAttachment(bytes)}")
            else -> throw ThreadInputException("ATTACHMENT_MEDIA_UNSUPPORTED", "Unsupported attachment media type: ${ref.mediaType}")
        }
    }
})

private fun decodeTextAttachment(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    throw ThreadInputException("ATTACHMENT_INVALID_TEXT", "Text attachments must contain valid UTF-8.")
}

internal class ThreadInputException(val code: String, message: String) : IllegalArgumentException(message)
private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
