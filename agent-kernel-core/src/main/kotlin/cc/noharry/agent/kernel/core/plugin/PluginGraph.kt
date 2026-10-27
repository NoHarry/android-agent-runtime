@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.PluginContractMessages
import java.util.TreeSet

class PluginDependencyGraph(
    private val ids: List<String>,
    private val blockers: Map<String, Set<String>>,
) {
    fun sortedIds(): List<String> {
        val remaining = ids.associateWith { id -> blockers[id].orEmpty().size }.toMutableMap()
        val dependents = reverseEdges()
        val ready = TreeSet(remaining.filterValues { it == 0 }.keys)
        val ordered = ArrayList<String>(ids.size)
        while (ready.isNotEmpty()) {
            val id = ready.first()
            ready.remove(id)
            ordered += id
            dependents[id].orEmpty().forEach { child ->
                val next = remaining.getValue(child) - 1
                remaining[child] = next
                if (next == 0) ready += child
            }
        }
        check(ordered.size == ids.size) { PluginContractMessages.DEPENDENCY_CYCLE }
        return ordered
    }

    private fun reverseEdges(): Map<String, Set<String>> {
        val dependents = ids.associateWith { mutableSetOf<String>() }
        blockers.forEach { (id, parents) ->
            parents.forEach { parent -> dependents.getValue(parent) += id }
        }
        return dependents
    }
}

object PluginGraph {
    fun build(
        plugins: List<AgentPlugin>,
        alreadyProvided: Set<CapabilityKey<*>>,
    ): PluginDependencyGraph {
        val byId = plugins.associateBy { it.descriptor.id }
        val providers = HashMap<CapabilityKey<*>, MutableList<String>>()
        byId.forEach { (id, plugin) ->
            plugin.descriptor.provides.forEach { key ->
                providers.getOrPut(key) { mutableListOf() } += id
            }
        }
        val blockers = byId.mapValues { (id, plugin) ->
            plugin.descriptor.requires.flatMap { key ->
                if (key in alreadyProvided) emptyList() else providers[key].orEmpty().filter { it != id }
            }.toSet()
        }
        return PluginDependencyGraph(byId.keys.toList(), blockers)
    }
}
