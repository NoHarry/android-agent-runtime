@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.runtime.DefaultAgentRuntime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import cc.noharry.agent.kernel.core.state.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.state.defaultSessionRunSchedulerConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** The conversation facade owns handles and their single active turn. */
internal class DefaultThreadRuntime(
    internal val engine: DefaultAgentRuntime,
    internal val store: ThreadStore,
    internal val imageInputEnabled: Boolean = true,
    internal val confirmationTimeoutMillis: Long = 60_000L,
    internal val nowEpochMillis: () -> Long = System::currentTimeMillis,
    internal val supportsImages: (ModelRef?) -> Boolean = { true },
    concurrency: SessionRunSchedulerConfig = defaultSessionRunSchedulerConfig(),
) : AgentRuntime {
    private val closed = AtomicBoolean()
    private val threads = ConcurrentHashMap<String, DefaultAgentThread>()
    override val diagnostics: RuntimeDiagnostics = DefaultRuntimeDiagnostics(engine)
    internal val admission = store.coordination.admission

    init { admission.register(this, concurrency) }

    private fun handle(record: ThreadRecord): DefaultAgentThread = synchronized(threads) {
        threads[record.id]?.takeIf { it.isCurrent() } ?: DefaultAgentThread(this, record).also { threads[record.id] = it }
    }

    override fun startThread(options: ThreadOptions): AgentThread {
        checkOpen()
        val id = options.threadId ?: UUID.randomUUID().toString()
        val record = store.create(id, options)
        return handle(record)
    }

    override fun resumeThread(threadId: String, options: ThreadOptions): AgentThread {
        checkOpen()
        require(threadId.isNotBlank()) { "Thread id must not be blank" }
        require(options.threadId == null || options.threadId == threadId) { "Thread id does not match options" }
        val saved = store.load(threadId) ?: store.create(threadId, options)
        val merged = saved.options.copy(
            model = options.model ?: saved.options.model, workspace = options.workspace ?: saved.options.workspace,
            scene = options.scene ?: saved.options.scene, instructions = options.instructions ?: saved.options.instructions,
            approvalPolicy = if (options == ThreadOptions()) saved.options.approvalPolicy else options.approvalPolicy,
        )
        return handle(saved.copy(options = merged)).also { it.configure(merged) }
    }

    override suspend fun listThreads(): List<ThreadInfo> = withContext(Dispatchers.IO) {
        checkOpen()
        store.list().map { info ->
            val active = store.executionSlot(info.id).active
            if (active == null) info else info.copy(status = if (active.approval == null) ThreadStatus.RUNNING else ThreadStatus.WAITING_FOR_APPROVAL)
        }
    }

    override suspend fun deleteThread(threadId: String) {
        checkOpen()
        val executing = currentCoroutineContext()[ThreadExecutionContext]
        check(executing?.ownsActiveTurn(store.coordination, threadId) != true) {
            "Cannot delete a thread from its own active turn. Delete it after the turn completes."
        }
        val slot = store.executionSlot(threadId)
        slot.deletionMutex.withLock { withContext(NonCancellable) { deleteConversation(threadId, slot) } }
    }

    private suspend fun deleteConversation(threadId: String, slot: ThreadExecutionSlot) {
        val drain = synchronized(store.coordination.lock) {
            slot.deleting = true
            slot.generation += 1
            admission.invalidate(threadId)
        }
        slot.active?.let { turn -> turn.suppressDelivery = true; turn.cancel() }
        try {
            drain.await()
            withContext(Dispatchers.IO) {
                admission.owners().forEach { it.engine.clearThreadHistory(threadId) }
                store.delete(threadId)
            }
            threads.remove(threadId)
        } finally { synchronized(store.coordination.lock) { slot.deleting = false } }
    }

    internal fun checkOpen() { check(!closed.get()) { "Runtime is closed" } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        admission.unregister(this)
        threads.values.forEach { it.cancel() }
        engine.close()
    }
}
