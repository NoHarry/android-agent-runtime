package cc.noharry.agent.androiduse

/**
 * One actor per screen. A run holds the screen while it keeps acting; a run of another session (another
 * conversation or A2A caller) gets DEVICE_BUSY until the holder has been idle for [idleMillis]. A later run of the
 * same session takes over at once: a session runs one turn at a time, so the holder has ended or is being stopped,
 * and a follow-up ("然后返回") must not wait out the previous turn. Process-wide because the screen is, while each
 * runtime builds its own provider.
 *
 * Times come from a monotonic clock (elapsed realtime on the device), so a wall-clock correction such as the device's
 * GNSS time sync can neither stretch nor skip the idle wait.
 */
internal class AndroidUseLease(private val idleMillis: Long = LEASE_IDLE_MILLIS) {
    private var owner: String? = null
    private var ownerSession: String? = null
    private var lastUsedAt = 0L

    @Synchronized
    fun acquire(sessionId: String, runId: String, nowMillis: Long): Boolean {
        val holder = owner
        val sameSession = ownerSession == sessionId
        if (holder != null && holder != runId && !sameSession && nowMillis - lastUsedAt < idleMillis) return false
        owner = runId
        ownerSession = sessionId
        lastUsedAt = nowMillis
        return true
    }

    /** The holder's call ended at [nowMillis]: the idle wait starts when its action finished, not when it began. */
    @Synchronized
    fun touch(runId: String, nowMillis: Long) {
        if (owner == runId) lastUsedAt = maxOf(lastUsedAt, nowMillis)
    }

    companion object {
        /** The lease every provider in this process shares. */
        val process = AndroidUseLease()
    }
}

private const val LEASE_IDLE_MILLIS = 10_000L
