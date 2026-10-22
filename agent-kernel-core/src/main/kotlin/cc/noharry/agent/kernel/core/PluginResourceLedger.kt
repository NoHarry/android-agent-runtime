package cc.noharry.agent.kernel.core

import java.util.IdentityHashMap

class PluginResourceLedger(
    private val claimFirst: (AutoCloseable) -> Boolean = { true },
    private val releaseClaim: (AutoCloseable) -> Unit = {},
) {
    private val resources = mutableListOf<AutoCloseable>()
    private val ownedIdentities = IdentityHashMap<AutoCloseable, Unit>()

    fun <T : AutoCloseable> own(resource: T): T {
        if (ownedIdentities.containsKey(resource)) {
            return resource
        }
        if (!claimFirst(resource)) {
            return resource
        }
        ownedIdentities[resource] = Unit
        resources += resource
        return resource
    }

    fun snapshot(): List<AutoCloseable> = resources.toList()

    fun rollback() {
        resources.asReversed().forEach { resource ->
            closeSafely(resource)
            releaseClaim(resource)
        }
        resources.clear()
        ownedIdentities.clear()
    }
}
