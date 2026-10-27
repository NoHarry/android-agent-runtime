package cc.noharry.agent.kernel.core.state
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Keeps mutations for one run atomic without serializing unrelated runs. */
class RunStateLockRegistry {
    private val entries = ConcurrentHashMap<String, LockEntry>()

    fun <T> withRunLock(runId: String, block: () -> T): T {
        val entry = retain(runId)
        entry.lock.lock()
        return try {
            block()
        } finally {
            entry.lock.unlock()
            release(runId, entry)
        }
    }

    fun retire(runId: String) {
        entries.computeIfPresent(runId) { _, entry ->
            entry.takeUnless { it.references == 0 }
        }
    }

    fun cachedLockCount(): Int = entries.size

    private fun retain(runId: String): LockEntry =
        requireNotNull(
            entries.compute(runId) { _, current ->
                (current ?: LockEntry()).also { it.references += 1 }
            },
        )

    private fun release(runId: String, retained: LockEntry) {
        entries.computeIfPresent(runId) { _, current ->
            if (current !== retained) {
                current
            } else {
                current.references -= 1
                current.takeUnless { current.references == 0 }
            }
        }
    }
}

private class LockEntry {
    val lock = ReentrantLock()
    var references: Int = 0
}
