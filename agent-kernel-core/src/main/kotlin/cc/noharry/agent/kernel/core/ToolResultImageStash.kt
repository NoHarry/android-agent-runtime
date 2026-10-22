package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelImage

/**
 * Holds provider images between a backend call and its tool-result message, keyed by tool call id.
 *
 * Tool results travel through the loop as text (hooks, summaries, breakers), so images ride beside
 * them instead of through every string seam. Bounded so a call that never finishes cannot pile up.
 */
internal class ToolResultImageStash(private val capacity: Int = MAX_PENDING_TOOL_RESULT_IMAGES) {
    private val pending = LinkedHashMap<String, List<ModelImage>>()

    fun put(toolCallId: String, images: List<ModelImage>) {
        if (toolCallId.isBlank() || images.isEmpty()) return
        synchronized(pending) {
            pending[toolCallId] = images
            while (pending.size > capacity) pending.remove(pending.keys.first())
        }
    }

    fun take(toolCallId: String): List<ModelImage> = synchronized(pending) { pending.remove(toolCallId) }.orEmpty()
}

private const val MAX_PENDING_TOOL_RESULT_IMAGES = 16
