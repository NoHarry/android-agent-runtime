package cc.noharry.agent.kernel.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-runtime/per-run probe stage emitter for host modules and adapters.
 *
 * Registration is owned by AgentRuntime internals. Hosts/Adapters must look up emitters only via
 * exact [emitterFor](runtimeId, runId) — never by runId alone — so two Runtimes sharing a runId
 * cannot cross-wire disk evidence.
 */
@ExperimentalAgentRuntimeApi
fun interface RuntimeProbeStageEmitter {
    /**
     * Emit one stage with allowlisted string attrs. Fail-open: never throw into business path.
     * @return false when dropped/disabled.
     */
    fun emit(stageId: String, attrs: Map<String, String>): Boolean
}

/**
 * Opaque handle from [RuntimeProbeEmitterHub.install]. Only this handle uninstalls that entry.
 */
@ExperimentalAgentRuntimeApi
class ProbeEmitterRegistration internal constructor(
    val runtimeId: String,
    val runId: String,
    internal val token: Long,
)

@ExperimentalAgentRuntimeApi
object RuntimeProbeEmitterHub {
    private data class Entry(
        val token: Long,
        val emitter: RuntimeProbeStageEmitter,
    )

    private val tokenSeq = AtomicLong(1L)
    /** Key: runtimeId + unit separator + runId */
    private val byCompositeKey = ConcurrentHashMap<String, Entry>()

    private fun key(runtimeId: String, runId: String): String = "$runtimeId\u0001$runId"

    /**
     * Exact lookup for a known runtime instance + runId.
     * Production Adapter path must use this only (no runId-only latest map).
     */
    fun emitterFor(runtimeId: String, runId: String): RuntimeProbeStageEmitter? =
        byCompositeKey[key(runtimeId, runId)]?.emitter

    /**
     * Runtime-owned install. Returns a handle for CAS uninstall.
     * Host-facing modules should only use [emitterFor].
     */
    fun install(
        runtimeId: String,
        runId: String,
        emitter: RuntimeProbeStageEmitter,
    ): ProbeEmitterRegistration {
        val token = tokenSeq.getAndIncrement()
        val reg = ProbeEmitterRegistration(runtimeId, runId, token)
        byCompositeKey[key(runtimeId, runId)] = Entry(token, emitter)
        return reg
    }

    /**
     * Compare-and-remove: only removes if [registration] still owns the slot.
     * @return true when this handle removed the entry.
     */
    fun uninstall(registration: ProbeEmitterRegistration): Boolean {
        val k = key(registration.runtimeId, registration.runId)
        val cur = byCompositeKey[k] ?: return false
        if (cur.token != registration.token) return false
        return byCompositeKey.remove(k, cur)
    }

    /** Remove every registration for one Runtime instance (close / abort cleanup). */
    fun uninstallAllForRuntime(runtimeId: String) {
        val prefix = "$runtimeId\u0001"
        val keys = byCompositeKey.keys.filter { it.startsWith(prefix) }
        for (k in keys) {
            byCompositeKey.remove(k)
        }
    }

}
