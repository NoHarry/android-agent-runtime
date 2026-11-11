package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a model request first differs from the previous request of its session. A provider caches a prompt by its
 * prefix, so the first part that changed is where the cache stops; a change in the system prompt or the tool list at
 * the start of a run means the run pays for most of its input again. Kept per prompt-cache key (one per session) for
 * the whole process, because the server builds a new runtime, and so a new model client, for every run.
 */
internal object PromptPrefix {
    private val previous = ConcurrentHashMap<String, PromptShape>()

    @OptIn(ExperimentalAgentRuntimeApi::class)
    fun compare(request: ModelRequest): String {
        val current = PromptShape(request.system.orEmpty().toByteArray(Charsets.UTF_8), request.tools.map { it.name }, request.messages)
        val before = previous.put(request.promptCacheKey.orEmpty(), current) ?: return "first request of this session"
        val system = commonPrefix(before.system.asList(), current.system.asList())
        val tools = commonPrefix(before.tools, current.tools)
        val messages = commonPrefix(before.messages, current.messages)
        val change = when {
            system < current.system.size || system < before.system.size -> "system"
            tools < current.tools.size || tools < before.tools.size -> "tools"
            messages < before.messages.size -> "messages"
            else -> "none (only appended)"
        }
        return "system same $system/${current.system.size}B · tools same $tools/${current.tools.size} · " +
            "messages same $messages/${current.messages.size} · first change: $change"
    }
}

private class PromptShape(val system: ByteArray, val tools: List<String>, val messages: List<ModelMessage>)

private fun <T> commonPrefix(a: List<T>, b: List<T>): Int {
    val limit = minOf(a.size, b.size)
    for (index in 0 until limit) if (a[index] != b[index]) return index
    return limit
}
