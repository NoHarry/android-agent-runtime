package cc.noharry.agent.appserver.runtime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Collision-safe request identifiers.
 * Millisecond prefixes stay readable; a monotonic sequence plus a UUID suffix
 * prevents same-millis collisions under concurrent starts.
 */
internal object RequestIds {
    private val sequence = AtomicLong(0)

    fun next(prefix: String = "debug"): String {
        val millis = System.currentTimeMillis()
        val seq = sequence.incrementAndGet()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(UUID_SUFFIX_CHARS)
        return "$prefix-$millis-$seq-$suffix"
    }

    fun resetForTest() {
        sequence.set(0)
    }
}

private const val UUID_SUFFIX_CHARS = 8
