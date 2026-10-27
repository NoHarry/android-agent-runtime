package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage

/**
 * Derived per-request view of message images. Canonical history and the EventLog keep every image.
 *
 * A text-only model gets a text placeholder for each image. Otherwise the newest [keepNewest] images
 * stay and older ones fold into text [foldStep] at a time, oldest first. The prompt prefix therefore
 * changes once per step, not on every call, and a folded image never comes back.
 */
class ModelImageRequestView(
    private val imageInputEnabled: Boolean = true,
    private val keepNewest: Int = DEFAULT_KEEP_NEWEST_IMAGES,
    private val foldStep: Int = DEFAULT_IMAGE_FOLD_STEP,
) {
    init {
        require(keepNewest >= 0) { "keepNewest must not be negative." }
        require(foldStep > 0) { "foldStep must be positive." }
    }

    /** Returns [messages] with the oldest images replaced by placeholders, or unchanged. */
    fun apply(messages: List<ModelMessage>): List<ModelMessage> {
        var remaining = foldedCount(messages.sumOf { it.images.size })
        if (remaining == 0) return messages
        val reason = if (imageInputEnabled) FOLDED_IMAGE_REASON else TEXT_ONLY_IMAGE_REASON
        return messages.map { message ->
            if (remaining == 0 || message.images.isEmpty()) return@map message
            val fold = minOf(remaining, message.images.size)
            remaining -= fold
            message.copy(
                content = message.content + message.images.take(fold).joinToString("") { it.placeholder(reason) },
                images = message.images.drop(fold),
            )
        }
    }

    /** How many of [total] images, oldest first, fold into text. */
    fun foldedCount(total: Int): Int = when {
        !imageInputEnabled -> total
        total <= keepNewest -> 0
        else -> ((total - keepNewest) / foldStep) * foldStep
    }
}

private fun ModelImage.placeholder(reason: String): String = "\n[image omitted: $reason: $sourcePath]"

const val DEFAULT_KEEP_NEWEST_IMAGES = 3
const val DEFAULT_IMAGE_FOLD_STEP = 3
private const val FOLDED_IMAGE_REASON = "older images fold first, newer ones follow"
private const val TEXT_ONLY_IMAGE_REASON = "this model accepts text only"
