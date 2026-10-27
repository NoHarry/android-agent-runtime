package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge

/**
 * Per-provider hard-cap limiter keyed by provider object identity, not serverId/string.
 *
 * Direct [ProviderConcurrencyMode.SESSION_FACTORY] instances fail closed because factory
 * registration is required. Cancellation releases the acquired permit, including waiters
 * still blocked on acquire.
 *
 * Serialized providers are fail-safe non-reentrant: a same-coroutine nested [withPermit] waits
 * on the same permit. Do not add a mutable coroutine-context bypass; a sound same-coroutine
 * or non-child reentrancy model remains factory composition debt.
 *
 * [close] is terminal: new permits are rejected and the map cannot be resurrected.
 * Drain in-flight work first. [evict] is race-safe for a provider identity so a
 * concurrent permit cannot leave a surviving entry after session close.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
class ProviderConcurrencyLimiter {
    private val closed = AtomicBoolean(false)
    private val evicted = ConcurrentHashMap<WeakIdentityKey, Boolean>()
    private val semaphores = ConcurrentHashMap<IdentityKey, Semaphore>()

    suspend fun <T> withPermit(provider: ToolProvider, action: suspend () -> T): T =
        withPermit(provider, provider.providerConcurrency, action)

    suspend fun <T> withPermit(client: ModelClient, action: suspend () -> T): T =
        withPermit(client, client.providerConcurrency, action)

    suspend fun <T> withPermit(engine: ExecEngine, action: suspend () -> T): T =
        withPermit(engine, engine.providerConcurrency, action)

    suspend fun <T> withPermit(
        provider: Any,
        declaration: ProviderConcurrencyDeclaration,
        action: suspend () -> T,
    ): T {
        requireSupportedDirectProvider(declaration)
        return semaphoreFor(provider, declaration).withPermit { action() }
    }

    fun close() {
        closed.set(true)
        semaphores.clear()
        evicted.clear()
    }

    fun evict(provider: Any) {
        val key = IdentityKey(provider)
        evicted[WeakIdentityKey(provider)] = true
        semaphores.remove(key)
    }

    fun cachedEntryCountForTests(): Int = semaphores.size

    private fun semaphoreFor(
        provider: Any,
        declaration: ProviderConcurrencyDeclaration,
    ): Semaphore {
        ensureAccepting(provider)
        val key = IdentityKey(provider)
        val semaphore = semaphores.compute(key) { _, existing ->
            ensureAccepting(provider)
            existing ?: Semaphore(declaration.maxConcurrentCalls)
        }
        if (closed.get() || isEvicted(provider)) {
            if (semaphore != null) {
                semaphores.remove(key, semaphore)
            }
            ensureAccepting(provider)
        }
        return checkNotNull(semaphore) {
            if (closed.get()) LIMITER_CLOSED_MESSAGE else LIMITER_EVICTED_MESSAGE
        }
    }

    private fun ensureAccepting(provider: Any) {
        check(!closed.get()) { LIMITER_CLOSED_MESSAGE }
        check(!isEvicted(provider)) { LIMITER_EVICTED_MESSAGE }
    }

    private fun isEvicted(provider: Any): Boolean = evicted[WeakIdentityKey(provider)] == true
}

/**
 * Enforces a runtime-global model semaphore and the delegate client's declaration around the
 * full Flow collection. Acquire order is global then provider. Builder installs one shared
 * limiter so multi-session model and compaction calls share identity caps.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
class ConcurrencyLimitedModelClient(
    private val delegate: ModelClient,
    private val limiter: ProviderConcurrencyLimiter = ProviderConcurrencyLimiter(),
    maxConcurrentModelCalls: Int = DEFAULT_MAX_CONCURRENT_MODEL_CALLS,
) : ModelClient {
    private val globalModelSemaphore: Semaphore

    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency

    init {
        require(maxConcurrentModelCalls > 0) { "maxConcurrentModelCalls must be positive." }
        requireSupportedDirectProvider(delegate.providerConcurrency)
        globalModelSemaphore = Semaphore(maxConcurrentModelCalls)
    }

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        globalModelSemaphore.withPermit {
            limiter.withPermit(delegate) {
                delegate.complete(request).collect { emit(it) }
            }
        }
    }
}

/**
 * Enforces the delegate engine's declaration around the full suspend [run]. The existing
 * global tool permit stays outer: session → tool → provider. Direct SESSION_FACTORY fails
 * closed at construction.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
class ConcurrencyLimitedExecEngine(
    private val delegate: ExecEngine,
    private val limiter: ProviderConcurrencyLimiter = ProviderConcurrencyLimiter(),
) : ExecEngine {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency

    init {
        requireSupportedDirectProvider(delegate.providerConcurrency)
    }

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult =
        limiter.withPermit(delegate) {
            delegate.run(request, bridge)
        }
}

const val DEFAULT_MAX_CONCURRENT_MODEL_CALLS = 4

@OptIn(ExperimentalAgentRuntimeApi::class)
fun requireSupportedDirectProvider(declaration: ProviderConcurrencyDeclaration) {
    check(declaration.mode != ProviderConcurrencyMode.SESSION_FACTORY) {
        PluginContractMessages.DIRECT_SESSION_FACTORY
    }
}

private class IdentityKey(private val ref: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && ref === other.ref

    override fun hashCode(): Int = System.identityHashCode(ref)
}

private class WeakIdentityKey(ref: Any) {
    private val identity = System.identityHashCode(ref)
    private val ref = WeakReference(ref)

    override fun equals(other: Any?): Boolean {
        if (other !is WeakIdentityKey) return false
        val left = ref.get()
        val right = other.ref.get()
        return left != null && left === right
    }

    override fun hashCode(): Int = identity
}

private const val LIMITER_CLOSED_MESSAGE = "ProviderConcurrencyLimiter is closed."
private const val LIMITER_EVICTED_MESSAGE = "ProviderConcurrencyLimiter evicted this provider."
