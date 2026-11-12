package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import java.util.IdentityHashMap

@OptIn(ExperimentalA2aClientApi::class)
internal class OfficialStreamCallbacks(
    private val emitEvent: (A2aStreamEvent) -> Boolean,
    private val emitTerminal: (A2aStreamEvent) -> Unit,
    private val mapFailure: (Throwable) -> A2aClientError,
) {
    private val lock = Any()
    private val seen = HashSet<A2aStreamEvent>()
    private var state = State.ACTIVE

    fun onEvent(event: A2aStreamEvent, terminal: Boolean) = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized
        if (!seen.add(event)) {
            finish(A2aStreamEvent.Failed(A2aClientError.Protocol("duplicate_stream_event")))
            return@synchronized
        }

        if (terminal) state = State.FINISHED
        if (!emitEvent(event)) {
            state = State.FINISHED
            return@synchronized
        }
        if (terminal) emitTerminal(A2aStreamEvent.Completed)
    }

    fun onComplete() = synchronized(lock) {
        if (state == State.ACTIVE) {
            finish(A2aStreamEvent.Incomplete(A2aClientError.Incomplete()))
        }
    }

    fun onError(error: Throwable) = synchronized(lock) {
        if (state == State.ACTIVE) {
            finish(A2aStreamEvent.Failed(mapFailure(error)))
        }
    }

    fun cancel() = synchronized(lock) {
        if (state == State.ACTIVE) state = State.CANCELLED
    }

    private fun finish(event: A2aStreamEvent) {
        state = State.FINISHED
        emitTerminal(event)
    }

    private enum class State {
        ACTIVE,
        FINISHED,
        CANCELLED,
    }
}

internal class OfficialStreamResourceLease<T : Any>(
    private val release: (T) -> Unit,
) {
    private val lock = Any()
    private var cancelled = false
    private var resource: T? = null
    private var activeUses = 0

    fun attach(value: T): Boolean {
        val accepted = synchronized(lock) {
            if (cancelled) false else {
                check(resource == null) { "stream_resource_already_attached" }
                resource = value
                true
            }
        }
        if (!accepted) release(value)
        return accepted
    }

    fun runIfActive(block: (T) -> Unit): Boolean {
        val value = synchronized(lock) {
            if (cancelled) return false
            (resource ?: return false).also { activeUses += 1 }
        }
        try {
            block(value)
        } finally {
            finishUse(value)
        }
        return true
    }

    fun cancel() {
        val value = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            if (activeUses == 0) resource.also { resource = null } else null
        }
        value?.let(release)
    }

    private fun finishUse(value: T) {
        val releaseAfterUse = synchronized(lock) {
            check(activeUses > 0) { "stream_resource_use_underflow" }
            activeUses -= 1
            if (activeUses == 0 && cancelled && resource === value) {
                resource = null
                true
            } else {
                false
            }
        }
        if (releaseAfterUse) release(value)
    }
}

internal class OfficialResourceRegistry<T : Any>(
    private val releaseResource: (T) -> Unit,
) {
    private val lock = Any()
    private val resources = IdentityHashMap<T, ResourceState>()
    private var closed = false

    fun register(value: T): Boolean {
        val accepted = synchronized(lock) {
            if (closed) false else {
                check(!resources.containsKey(value)) { "official_resource_already_registered" }
                resources[value] = ResourceState()
                true
            }
        }
        if (!accepted) releaseResource(value)
        return accepted
    }

    fun runIfActive(value: T, block: (T) -> Unit): Boolean {
        val state = synchronized(lock) {
            val current = if (closed) null else resources[value]
            current?.takeUnless { it.releaseRequested }?.also { it.activeUses += 1 }
        } ?: return false
        try {
            block(value)
        } finally {
            finishUse(value, state)
        }
        return true
    }

    fun release(value: T) {
        val releaseNow = synchronized(lock) {
            val state = resources[value] ?: return
            if (state.releaseRequested) {
                false
            } else {
                state.releaseRequested = true
                (state.activeUses == 0).also { ready -> if (ready) resources.remove(value) }
            }
        }
        if (releaseNow) releaseResource(value)
    }

    fun close() {
        val values = synchronized(lock) {
            if (closed) return
            closed = true
            resources.entries.forEach { it.value.releaseRequested = true }
            resources.entries
                .filter { it.value.activeUses == 0 }
                .map { it.key }
                .also { ready -> ready.forEach(resources::remove) }
        }
        values.forEach(releaseResource)
    }

    fun isOpen(): Boolean = synchronized(lock) { !closed }

    private fun finishUse(value: T, state: ResourceState) {
        val releaseAfterUse = synchronized(lock) {
            check(state.activeUses > 0) { "official_resource_use_underflow" }
            state.activeUses -= 1
            if (state.activeUses == 0 && state.releaseRequested && resources[value] === state) {
                resources.remove(value)
                true
            } else {
                false
            }
        }
        if (releaseAfterUse) releaseResource(value)
    }

    private data class ResourceState(
        var activeUses: Int = 0,
        var releaseRequested: Boolean = false,
    )
}
