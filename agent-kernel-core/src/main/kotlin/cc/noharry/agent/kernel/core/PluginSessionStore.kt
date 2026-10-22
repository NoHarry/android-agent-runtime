@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.SessionCapabilityContext

class PluginSessionStore {
    private val values = LinkedHashMap<SessionSlot, Any>()
    private val globalOrder = mutableListOf<SessionSlot>()
    private val sessionOrder = LinkedHashMap<String, MutableList<SessionSlot>>()
    private val generations = HashMap<String, Long>()

    fun generationOf(sessionId: String): Long = generations[sessionId] ?: 0L

    fun peek(slot: SessionSlot): Any? = values[slot]

    fun cachedValues(
        context: SessionCapabilityContext,
        bindings: List<CapabilityBinding>,
    ): List<Any>? {
        val slots = bindings.map { SessionSlot(context.sessionId, it.key, it) }
        if (slots.any { it !in values }) return null
        return slots.map { values.getValue(it) }
    }

    fun publish(slot: SessionSlot, value: Any): Any {
        values[slot]?.let { return it }
        values[slot] = value
        globalOrder += slot
        sessionOrder.getOrPut(slot.sessionId) { mutableListOf() } += slot
        return value
    }

    fun takeSession(sessionId: String): List<Any> {
        generations[sessionId] = generationOf(sessionId) + 1L
        val slots = sessionOrder.remove(sessionId)?.asReversed() ?: return emptyList()
        return slots.mapNotNull { slot ->
            globalOrder.remove(slot)
            values.remove(slot)
        }
    }

    fun takeAll(): List<Any> {
        val snapshot = globalOrder.asReversed().mapNotNull { values[it] }
        values.clear()
        globalOrder.clear()
        sessionOrder.clear()
        generations.clear()
        return snapshot
    }
}
