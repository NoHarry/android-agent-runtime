@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.RuntimeProbeEmitterHub
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Module-local reflection access to [RuntimeProbeEmitterHub] registry state.
 * Production keeps only emitterFor/install/uninstall/uninstallAllForRuntime.
 */
internal object RuntimeProbeEmitterHubTestAccess {
    private const val MAP_FIELD = "byCompositeKey"

    @Suppress("UNCHECKED_CAST")
    private fun map(): ConcurrentHashMap<String, Any> {
        val field = registryField()
        return field.get(RuntimeProbeEmitterHub) as ConcurrentHashMap<String, Any>
    }

    private fun registryField(): Field {
        val field = RuntimeProbeEmitterHub::class.java.getDeclaredField(MAP_FIELD)
        field.isAccessible = true
        return field
    }

    fun clearAll() {
        map().clear()
    }

    fun sizeForTest(): Int = map().size

    fun sizeForRuntime(runtimeId: String): Int {
        val prefix = "$runtimeId\u0001"
        return map().keys.count { it.startsWith(prefix) }
    }
}
